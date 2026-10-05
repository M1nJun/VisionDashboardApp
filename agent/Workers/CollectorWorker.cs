using System.Text;
using Microsoft.Extensions.Hosting;
using VisionAgent.Alarms;
using VisionAgent.Catalog;
using VisionAgent.Csv;
using VisionAgent.Logging;
using VisionAgent.Parsing;
using VisionAgent.State;
using VisionAgent.Transport;

namespace VisionAgent.Workers;

/// <summary>
/// Polls the vision PC's result CSVs and status log and forwards what is new.
///
/// The one invariant everything here protects: a file's offset only moves after the
/// server has accepted everything read from it. Re-sending is harmless (the server
/// filters replays by unit sequence), losing rows is not.
/// </summary>
public sealed class CollectorWorker : BackgroundService
{
    private readonly AgentConfig _config;
    private readonly AgentCatalog _catalog;
    private readonly AgentState _state;
    private readonly EventSender _sender;
    private readonly SourceFiles _files;
    private readonly FileLogger _log;
    private readonly bool _runOnce;

    public CollectorWorker(AgentConfig config, AgentCatalog catalog, AgentState state,
                           EventSender sender, FileLogger log, bool runOnce)
    {
        _config = config;
        _catalog = catalog;
        _state = state;
        _sender = sender;
        _log = log;
        _runOnce = runOnce;
        _files = new SourceFiles(config.Line, config.ModelToken, config.SourceFolderOverride);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var interval = _config.PollIntervalMs <= 0 ? 1000 : _config.PollIntervalMs;

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                foreach (var visionKey in _config.VisionKeys)
                {
                    await CollectVisionAsync(visionKey, stoppingToken);
                }
                await CollectAlarmsAsync(stoppingToken);
                _state.SaveIfChanged();
            }
            // Only a real shutdown ends the loop. Anything else - including a cancellation
            // raised by something other than stoppingToken - costs this pass and no more.
            // Treating every cancellation as shutdown meant one slow reply from the server
            // stopped the agent for good while it went on looking perfectly healthy.
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.Error("collection pass failed: " + ex);
            }

            if (_runOnce) return;

            try
            {
                await Task.Delay(interval, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }

        // A collector that stops reading is invisible from the outside: the service still
        // runs and the heartbeat still arrives, so the dashboard shows the inspector alive
        // and merely idle. If this loop ever ends, say so.
        _log.Info(stoppingToken.IsCancellationRequested
            ? "collector stopped: service is shutting down"
            : "collector stopped unexpectedly - no further CSV rows will be read");
    }

    private async Task CollectVisionAsync(string visionKey, CancellationToken token)
    {
        var type = _catalog.Require(visionKey);
        var files = _files.Discover(type.Source, DateTime.Now).ToList();
        PrimeColdStart(type, files);
        foreach (var file in files)
        {
            await CollectFileAsync(visionKey, type, file, token);
        }
    }

    /// <summary>
    /// Decides where a never-before-read file should be started from.
    ///
    /// Reading it from the top would replay every lot the line has already run, and each
    /// of those old lot changes tells the server to close the lot actually in progress and
    /// zero its counters - which is what made a redeploy wipe the live numbers. The
    /// dashboard only ever shows the current lot, so a fresh agent starts where that lot
    /// begins and files that predate it are skipped outright.
    /// </summary>
    private void PrimeColdStart(VisionTypeDef type, List<string> files)
    {
        var cold = files.Where(f => !_state.File(f).Primed).ToList();
        if (cold.Count == 0) return;

        // Newest file with any rows names the lot that is running now.
        string? currentLot = null;
        foreach (var file in files)
        {
            var lot = LotSeek.Scan(file, type.Source, null).LastLot;
            if (!string.IsNullOrWhiteSpace(lot)) currentLot = lot;
        }

        foreach (var file in cold)
        {
            var progress = _state.File(file);
            progress.Primed = true;

            if (currentLot is null)
            {
                progress.Offset = 0;
                _log.Info($"{Path.GetFileName(file)}: no lot recorded yet, starting from the top");
                continue;
            }

            var scan = LotSeek.Scan(file, type.Source, currentLot);
            progress.Offset = scan.LotStartOffset;
            progress.PendingPartialLine = "";
            progress.Header = scan.Header?.ToList();

            _log.Info(scan.ContainsLot
                ? $"{Path.GetFileName(file)}: starting at lot {currentLot} (offset {scan.LotStartOffset})"
                : $"{Path.GetFileName(file)}: predates lot {currentLot}, skipping to end");
        }
        _state.MarkDirty();
    }

    private async Task CollectFileAsync(string visionKey, VisionTypeDef type, string path, CancellationToken token)
    {
        var progress = _state.File(path);
        var encoding = GetEncoding(type.Source.Encoding);
        var fileName = Path.GetFileName(path);

        FileInfo info;
        try
        {
            info = new FileInfo(path);
            if (!info.Exists) return;
        }
        catch (Exception ex)
        {
            _log.Warn($"cannot stat {fileName}: {ex.Message}");
            return;
        }

        if (info.Length < progress.Offset)
        {
            _log.Info($"{fileName} shrank; re-reading from the start");
            progress.Offset = 0;
            progress.PendingPartialLine = "";
            progress.Header = null;
            _state.MarkDirty();
        }
        if (info.Length == progress.Offset) return;

        string appended;
        long newOffset;
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete);
            stream.Seek(progress.Offset, SeekOrigin.Begin);
            using var reader = new StreamReader(stream, encoding, detectEncodingFromByteOrderMarks: false,
                bufferSize: 16 * 1024, leaveOpen: true);
            appended = await reader.ReadToEndAsync(token);
            newOffset = stream.Position;
        }
        catch (Exception ex)
        {
            _log.Warn($"cannot read {fileName}: {ex.Message}");
            return;
        }

        if (string.IsNullOrEmpty(appended)) return;

        var combined = progress.PendingPartialLine + appended;
        var complete = combined.EndsWith('\n') || combined.EndsWith('\r');
        var lines = SplitLines(combined).ToList();
        var pending = "";
        if (!complete && lines.Count > 0)
        {
            pending = lines[^1];
            lines.RemoveAt(lines.Count - 1);
        }

        var header = progress.Header is null ? null : new CsvHeader(progress.Header);
        var start = 0;
        if (header is null && lines.Count > 0)
        {
            header = new CsvHeader(CsvLine.Split(lines[0], type.Source.DelimiterChar));
            start = 1;
            _log.Info($"{fileName}: header has {header.Names.Count} columns");
        }
        if (header is null)
        {
            return;
        }

        // Parsed first, resolved second: deciding which lot a row belongs to sometimes
        // needs the rows after it, because the lot's name can arrive later than its
        // first unit.
        var units = new List<UnitInspectedEvent>();
        var now = DateTimeOffset.Now;

        for (var i = start; i < lines.Count; i++)
        {
            if (string.IsNullOrWhiteSpace(lines[i])) continue;

            CsvRow row;
            try
            {
                row = new CsvRow(header, CsvLine.Split(lines[i], type.Source.DelimiterChar));
            }
            catch (Exception ex)
            {
                _log.Warn($"{fileName} line {i}: unparseable ({ex.Message})");
                continue;
            }

            var unit = UnitBuilder.Build(type, header, row, fileName, now);
            if (unit is null)
            {
                _log.Warn($"{fileName} line {i}: no usable '{type.Source.Columns.UnitSeq}' value");
                continue;
            }
            units.Add(unit);
        }

        var events = new List<object>();
        var lot = _state.LotOf(visionKey);
        var highestSeq = _state.SeqOf(visionKey);

        for (var i = 0; i < units.Count; i++)
        {
            var unit = units[i];

            // The unit number marks the boundary; the name only says what the lot is
            // called. Reading it the other way round broke twice, in opposite directions:
            //
            //   No 11933  06:01:31  LOTID025KZ   <- renamed early, still the old numbering
            //   No     1  06:03:21  LOTID025KZ   <- the lot really starts here
            //
            //   No 15380  06:01:04  (blank)      <- the inspector failed to read the name
            //   No     1  06:02:38  (blank)      <- the lot really starts here, still blank
            //   No     2  06:02:42  LOTID053KA   <- and this is what it is called
            //
            // A blank name is not "no lot": it is a row the inspector could not read, and
            // it belongs to whatever lot is running. Both cases now fall out of one rule.
            var restarted = highestSeq is null || unit.UnitSeq < highestSeq.Value;

            if (restarted)
            {
                // The name may be a few rows away, so look for it rather than believing
                // this row's blank.
                var name = unit.LotId ?? NameAfterRestart(units, i);
                if (name is null)
                {
                    if (WaitForLotName(visionKey, fileName, unit.UnitSeq))
                    {
                        // Leave the offset where it is and read these rows again once the
                        // inspector has written a name. Nothing is sent this pass.
                        return;
                    }
                    // Waited long enough. Open the lot under a name that says what
                    // happened rather than stalling the inspector for the rest of the run.
                    name = $"NOREAD-{now:yyyyMMdd-HHmm}";
                    _log.Error($"{fileName}: NO restarted at {unit.UnitSeq} but no lot name ever "
                               + $"followed - counting under {name}");
                }

                if (!string.Equals(name, lot, StringComparison.OrdinalIgnoreCase))
                {
                    // Lot state is per vision key, not per file, so a lot that changes while
                    // the filename rolls over at midnight is still announced.
                    events.Add(new LotChangedEvent
                    {
                        SourceFile = fileName,
                        OldLotId = lot,
                        NewLotId = name,
                        DetectedAtUnitSeq = unit.UnitSeq,
                        OccurredAt = now
                    });
                    _log.Info($"{fileName}: NO restarted at {unit.UnitSeq} - lot {lot} -> {name}");
                }
                lot = name;
                highestSeq = unit.UnitSeq;
            }
            else
            {
                highestSeq = Math.Max(highestSeq!.Value, unit.UnitSeq);
            }

            // Blank names and early renames alike: the row belongs to the lot that is
            // running, which is the only thing the numbering can be measured against.
            unit.LotId = lot;
            events.Add(unit);
        }

        _waitingForLotName.Remove(visionKey);

        if (!await _sender.SendAsync(visionKey, events, token))
        {
            _log.Warn($"{fileName}: not advancing offset, will retry {events.Count} event(s)");
            return;
        }

        progress.Offset = newOffset;
        progress.PendingPartialLine = pending;
        progress.Header = header.Names.ToList();
        if (lot is not null) _state.SetLot(visionKey, lot);
        if (highestSeq is not null) _state.SetSeq(visionKey, highestSeq.Value);
        _state.MarkDirty();
    }

    /** The first lot name at or after a restart, or null if none of these rows carry one. */
    private static string? NameAfterRestart(List<UnitInspectedEvent> units, int from)
    {
        for (var i = from; i < units.Count; i++)
        {
            if (units[i].LotId is not null) return units[i].LotId;
        }
        return null;
    }

    /// <summary>
    /// How many passes each vision key has spent waiting for a lot name to appear.
    ///
    /// The inspector leaves LOT-ID blank whenever it fails to read one, and that can land
    /// on the very row where the numbering restarts. Waiting a moment costs nothing - the
    /// name has always turned up a row or two later - but waiting forever would be the
    /// silent stall all over again, so the wait is bounded.
    /// </summary>
    private readonly Dictionary<string, int> _waitingForLotName = new();

    private bool WaitForLotName(string visionKey, string fileName, long seq)
    {
        var waited = _waitingForLotName.GetValueOrDefault(visionKey) + 1;
        _waitingForLotName[visionKey] = waited;

        if (waited == 1)
        {
            _log.Info($"{fileName}: NO restarted at {seq} with no lot name yet - waiting for "
                      + "the inspector to write one");
        }
        return waited <= LotNameWaitPasses;
    }

    /// <summary>Passes to wait for a lot name before giving up. At a one-second poll this
    /// is two minutes - far longer than the row or two it has ever actually taken.</summary>
    private const int LotNameWaitPasses = 120;

    private async Task CollectAlarmsAsync(CancellationToken token)
    {
        // Every vision key on this PC shares the same status log, so the file is read once
        // and each new line is reported under each agent id.
        var alarmConfig = _catalog.Require(_config.VisionKeys[0]).Source.AlarmLog;
        var encoding = GetEncoding(alarmConfig.Encoding);

        foreach (var path in StatusLogReader.Discover(alarmConfig, DateTime.Now))
        {
            var progress = _state.Log(path);
            progress.LastSeenUtc = DateTime.UtcNow;

            FileInfo info;
            try
            {
                info = new FileInfo(path);
                if (!info.Exists) continue;
            }
            catch (Exception)
            {
                continue;
            }

            // On the very first pass over a log that already existed, start at the end:
            // replaying a whole day of alarms every time the service restarts would bury
            // whatever is actually happening now.
            if (!progress.Initialised)
            {
                progress.Initialised = true;
                progress.Offset = info.Length;
                progress.PendingPartialLine = "";
                _state.MarkDirty();
                continue;
            }

            if (info.Length < progress.Offset)
            {
                progress.Offset = 0;
                progress.PendingPartialLine = "";
                _state.MarkDirty();
            }
            if (info.Length == progress.Offset) continue;

            string appended;
            long newOffset;
            try
            {
                using var stream = new FileStream(path, FileMode.Open, FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete);
                stream.Seek(progress.Offset, SeekOrigin.Begin);
                using var reader = new StreamReader(stream, encoding, false, 8 * 1024, leaveOpen: true);
                appended = await reader.ReadToEndAsync(token);
                newOffset = stream.Position;
            }
            catch (Exception ex)
            {
                _log.Warn($"cannot read {Path.GetFileName(path)}: {ex.Message}");
                continue;
            }

            if (string.IsNullOrEmpty(appended)) continue;

            var pendingBytes = encoding.GetByteCount(progress.PendingPartialLine);
            var combined = progress.PendingPartialLine + appended;
            var baseOffset = progress.Offset - pendingBytes;

            var pending = "";
            var byKey = _config.VisionKeys.ToDictionary(k => k, _ => new List<object>());

            foreach (var (line, offset, isComplete) in
                     StatusLogReader.SplitWithOffsets(combined, encoding, baseOffset))
            {
                if (!isComplete)
                {
                    pending = line;
                    break;
                }
                if (!line.Contains(alarmConfig.Marker, StringComparison.OrdinalIgnoreCase)) continue;

                foreach (var visionKey in _config.VisionKeys)
                {
                    byKey[visionKey].Add(StatusLogReader.Build(
                        _config.AgentId(visionKey), path, offset, line, alarmConfig.Marker));
                }
            }

            var allSent = true;
            foreach (var (visionKey, events) in byKey)
            {
                if (!await _sender.SendAsync(visionKey, events, token))
                {
                    allSent = false;
                    break;
                }
            }

            if (!allSent)
            {
                _log.Warn($"{Path.GetFileName(path)}: not advancing alarm offset, will retry");
                continue;
            }

            progress.Offset = newOffset;
            progress.PendingPartialLine = pending;
            _state.MarkDirty();
        }
    }

    private static IEnumerable<string> SplitLines(string text)
    {
        using var reader = new StringReader(text);
        while (reader.ReadLine() is { } line) yield return line;
    }

    private static Encoding GetEncoding(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return new UTF8Encoding(false);
        try
        {
            return Encoding.GetEncoding(name);
        }
        catch (Exception)
        {
            return new UTF8Encoding(false);
        }
    }
}
