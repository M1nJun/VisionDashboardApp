using System.Text.Json;

namespace VisionAgent.State;

/// <summary>
/// Where the agent left off. Two deliberate differences from the previous design:
///
///  - The current lot is tracked per vision key, not per file. Tracking it per file
///    meant the first row of a new day's CSV never compared against anything, so a lot
///    that changed overnight was never announced and the server's counters carried the
///    old lot's totals into the new one.
///  - It is written only when something actually changed, and stale file entries are
///    pruned. The previous agent rewrote the whole state file every second and never
///    removed a day's entry, so the file grew without bound on a PC that never reboots.
/// </summary>
public sealed class AgentState
{
    public Dictionary<string, FileProgress> Files { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, LogProgress> Logs { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, string> CurrentLot { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Last unit number seen per vision key. The vision software renames the lot
    /// a row or two before it restarts the numbering, so the number - not the name - is
    /// what says where one lot ends and the next begins.</summary>
    public Dictionary<string, long> CurrentSeq { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    private static readonly TimeSpan KeepFileEntries = TimeSpan.FromDays(7);

    private string _path = "state.json";
    private bool _dirty;

    public static AgentState Load(string path)
    {
        AgentState state;
        try
        {
            state = System.IO.File.Exists(path)
                ? JsonSerializer.Deserialize<AgentState>(System.IO.File.ReadAllText(path), JsonDefaults.Options)
                  ?? new AgentState()
                : new AgentState();
        }
        catch (Exception)
        {
            // A truncated state file (power loss mid-write) must not stop the agent from
            // starting; re-reading from the top is safe because the server filters replays.
            state = new AgentState();
        }
        state._path = path;
        return state;
    }

    public FileProgress File(string path)
    {
        var key = System.IO.Path.GetFullPath(path);
        if (!Files.TryGetValue(key, out var progress))
        {
            progress = new FileProgress();
            Files[key] = progress;
            _dirty = true;
        }
        progress.LastSeenUtc = DateTime.UtcNow;
        return progress;
    }

    public LogProgress Log(string path)
    {
        var key = System.IO.Path.GetFullPath(path);
        if (!Logs.TryGetValue(key, out var progress))
        {
            progress = new LogProgress();
            Logs[key] = progress;
            _dirty = true;
        }
        return progress;
    }

    public string? LotOf(string visionKey) => CurrentLot.GetValueOrDefault(visionKey);

    public long? SeqOf(string visionKey) =>
        CurrentSeq.TryGetValue(visionKey, out var seq) ? seq : null;

    public void SetSeq(string visionKey, long seq)
    {
        if (CurrentSeq.TryGetValue(visionKey, out var existing) && existing == seq) return;
        CurrentSeq[visionKey] = seq;
        _dirty = true;
    }

    public void SetLot(string visionKey, string lotId)
    {
        if (CurrentLot.TryGetValue(visionKey, out var existing) && existing == lotId) return;
        CurrentLot[visionKey] = lotId;
        _dirty = true;
    }

    public void MarkDirty() => _dirty = true;

    public void SaveIfChanged()
    {
        if (!_dirty) return;
        Prune();

        var tmp = _path + ".tmp";
        System.IO.File.WriteAllText(tmp, JsonSerializer.Serialize(this, new JsonSerializerOptions(JsonDefaults.Options)
        {
            WriteIndented = true
        }));
        System.IO.File.Move(tmp, _path, overwrite: true);
        _dirty = false;
    }

    private void Prune()
    {
        var cutoff = DateTime.UtcNow - KeepFileEntries;
        foreach (var key in Files.Where(kv => kv.Value.LastSeenUtc < cutoff).Select(kv => kv.Key).ToList())
        {
            Files.Remove(key);
        }
        foreach (var key in Logs.Where(kv => kv.Value.LastSeenUtc < cutoff).Select(kv => kv.Key).ToList())
        {
            Logs.Remove(key);
        }
    }
}

public sealed class FileProgress
{
    /// <summary>False until this agent has decided where in the file to start. A fresh
    /// agent seeks to the current lot rather than reading the whole day - see LotSeek.</summary>
    public bool Primed { get; set; }
    public long Offset { get; set; }
    public string PendingPartialLine { get; set; } = "";
    public List<string>? Header { get; set; }
    public DateTime LastSeenUtc { get; set; } = DateTime.UtcNow;
}

public sealed class LogProgress
{
    public long Offset { get; set; }
    public string PendingPartialLine { get; set; } = "";
    public bool Initialised { get; set; }
    public DateTime LastSeenUtc { get; set; } = DateTime.UtcNow;
}
