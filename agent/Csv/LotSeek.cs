using System.Text;
using VisionAgent.Catalog;

namespace VisionAgent.Csv;

/// <summary>
/// Works out where the lot that is running right now starts inside a CSV.
///
/// An agent with no saved position must not simply read the file from the top. Doing that
/// replays every lot the line ran today, and each old lot change tells the server to close
/// the lot that is actually running and zero its counters - a redeploy wiped the live
/// numbers and then counted up from nothing.
///
/// The dashboard only ever shows the current lot, so that is exactly how far back a fresh
/// agent needs to look: find where the current lot began and start there. An agent that
/// was down for three days does not replay three days; it picks up the lot in progress.
/// </summary>
public static class LotSeek
{
    /// <param name="Header">Carried out with the offset: seeking past the top of the file
    /// means the reader never sees the header line, and would take the first data row for it.</param>
    public sealed record Result(string? LastLot, long LotStartOffset, long EndOffset,
                                bool ContainsLot, IReadOnlyList<string>? Header);

    /// <param name="wantedLot">null to report the file's own last lot and where it begins.</param>
    public static Result Scan(string path, SourceDef source, string? wantedLot)
    {
        var encoding = GetEncoding(source.Encoding);
        byte[] bytes;
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete);
            using var buffer = new MemoryStream();
            stream.CopyTo(buffer);
            bytes = buffer.ToArray();
        }
        catch (Exception)
        {
            return new Result(null, 0, 0, false, null);
        }
        long length = bytes.LongLength;

        CsvHeader? header = null;
        // One entry per run of the numbering: where it starts, and what it turned out to
        // be called. The name is filled in by the first row that carries one, which is
        // not always the run's first row.
        var runs = new List<Run>();
        long? previousSeq = null;

        foreach (var (line, offset, complete) in Rows(bytes, encoding))
        {
            if (!complete) break;
            if (string.IsNullOrWhiteSpace(line)) continue;

            var fields = CsvLine.Split(line, source.DelimiterChar);
            if (header is null)
            {
                // These files carry a byte order mark, which decodes into the first
                // column's name and would make every lookup of "NO" miss.
                header = new CsvHeader(CsvLine.Split(line.TrimStart('\uFEFF'), source.DelimiterChar));
                continue;
            }

            var row = new CsvRow(header, fields);
            if (!long.TryParse(row.Get(source.Columns.UnitSeq), out var seq)) continue;

            var lot = row.Get(source.Columns.LotId);
            var named = string.IsNullOrWhiteSpace(lot) ? null : lot.Trim();

            // The numbering marks the boundary; the name only says what the run is
            // called. This is the collector's rule, and it has to be the same rule - the
            // seek decides where the collector begins reading, so if the two disagree
            // about where a lot starts, a cold start lands in the middle of the running
            // lot and counts it from zero.
            //
            // Reading a name change as a boundary broke it twice over. The vision renames
            // the lot a row or two early, so those rows carry the outgoing lot's
            // numbering; and this inspector misreads LOT-ID often enough that a single
            // wrong name mid-lot opened a phantom run and moved the seek to it.
            if (previousSeq is null || seq < previousSeq.Value)
            {
                runs.Add(new Run(offset, named));
            }
            else if (named is not null && runs.Count > 0 && runs[^1].Lot is null)
            {
                // The run opened with rows the inspector could not read a name for; this
                // is the first row that says what it is.
                runs[^1] = runs[^1] with { Lot = named };
            }

            previousSeq = seq;
        }

        var headerNames = header?.Names;
        // The lot in progress is the last run that has a name. A final run still waiting
        // for its first readable name falls back to the one before it: the collector
        // announces the change itself when it reaches the restart, so starting a little
        // early costs a replay the server filters, and never a missed lot.
        var lastLot = runs.LastOrDefault(r => r.Lot is not null)?.Lot;

        if (wantedLot is null)
        {
            return new Result(lastLot, 0, length, lastLot is not null, headerNames);
        }

        // The last run to start under that name is the one still going.
        var wanted = runs.LastOrDefault(r => r.Lot is not null
                                             && string.Equals(r.Lot, wantedLot, StringComparison.OrdinalIgnoreCase));
        return new Result(lastLot, wanted?.Offset ?? length, length, wanted is not null, headerNames);
    }

    private sealed record Run(long Offset, string? Lot);

    /// <summary>
    /// Lines with the byte offset they start at, so a seek lands on a row boundary.
    ///
    /// Split on the bytes, not on decoded text. Counting the bytes back out of a decoded
    /// string looked equivalent and was not: the reader strips the byte order mark these
    /// files carry, so every offset came out three bytes short and a cold start began
    /// mid-row - the agent logged "line 0: no usable 'NO' value" on every deploy and
    /// threw that row away. CR and LF cannot occur inside a character in any encoding
    /// these files use, so splitting on them is safe.
    /// </summary>
    private static IEnumerable<(string Line, long Offset, bool Complete)> Rows(byte[] bytes, Encoding encoding)
    {
        var i = 0;
        while (i < bytes.Length)
        {
            var start = i;
            while (i < bytes.Length && bytes[i] != (byte)'\n' && bytes[i] != (byte)'\r') i++;

            var terminator = 0;
            if (i < bytes.Length)
            {
                terminator = bytes[i] == (byte)'\r' && i + 1 < bytes.Length
                             && bytes[i + 1] == (byte)'\n' ? 2 : 1;
            }

            yield return (encoding.GetString(bytes, start, i - start), start, terminator > 0);
            if (terminator == 0) yield break;

            i += terminator;
        }
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
