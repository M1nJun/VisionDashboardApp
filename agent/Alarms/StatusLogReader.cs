using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using VisionAgent.Catalog;
using VisionAgent.Transport;

namespace VisionAgent.Alarms;

/// <summary>
/// Turns "[Alarm]" lines in the vision PC's daily status log into VISION_ALARM events.
///
/// A Example A PC has one status log shared by both of its inspectors, and nothing in
/// the line says which one it belongs to, so the same line is reported under both
/// agent ids. The event uid includes the agent id, so those stay two distinct events
/// rather than one being discarded as a duplicate of the other.
/// </summary>
public static class StatusLogReader
{
    private static readonly Regex LinePattern =
        new(@"^\[(?<ts>[^\]]+)\]\[Alarm\]\s*(?<msg>.*)$", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex MessagePattern =
        new(@"^(?<code>\d+)\.\s*(?<name>[^\(]+)(?:\((?<detail>[^\)]*)\))?.*$", RegexOptions.Compiled);

    public static IEnumerable<string> Discover(AlarmLogDef config, DateTime now)
    {
        foreach (var drive in config.Drives)
        {
            var letter = drive.Trim().TrimEnd(':', '\\', '/');
            if (letter.Length == 0) continue;

            // Yesterday first for the same reason the CSV reader does it: the log rolls at
            // midnight but alarms do not wait for the filename to change.
            foreach (var day in new[] { now.Date.AddDays(-1), now.Date })
            {
                string path;
                try
                {
                    var directory = Path.Combine(letter + @":\", config.Folder);
                    if (!Directory.Exists(directory)) continue;
                    path = Path.Combine(directory, FileName(config.FileNameFormat, day));
                    if (!File.Exists(path)) continue;
                }
                catch (Exception)
                {
                    continue;
                }
                yield return path;
            }
        }
    }

    public static string FileName(string format, DateTime day) => format
        .Replace("yyyyMMdd", day.ToString("yyyyMMdd"))
        .Replace("yyMMdd", day.ToString("yyMMdd"));

    /// <summary>Parses one alarm line into an event for a specific agent id.</summary>
    public static VisionAlarmEvent Build(string agentId, string filePath, long byteOffset, string rawLine,
                                         string marker)
    {
        string timestampRaw = "";
        string message = rawLine;
        DateTimeOffset? alarmAt = null;

        var match = LinePattern.Match(rawLine);
        if (match.Success)
        {
            timestampRaw = match.Groups["ts"].Value.Trim();
            message = match.Groups["msg"].Value.Trim();
            if (DateTime.TryParseExact(timestampRaw, "yyyy/MM/dd HH:mm:ss.fff", null,
                    System.Globalization.DateTimeStyles.None, out var parsed))
            {
                alarmAt = new DateTimeOffset(parsed, TimeZoneInfo.Local.GetUtcOffset(parsed));
            }
        }
        else
        {
            var index = rawLine.IndexOf(marker, StringComparison.OrdinalIgnoreCase);
            if (index >= 0) message = rawLine[(index + marker.Length)..].Trim();
        }

        string? code = null, name = null, detail = null;
        var parts = MessagePattern.Match(message);
        if (parts.Success)
        {
            code = parts.Groups["code"].Value.Trim();
            name = parts.Groups["name"].Value.Trim();
            detail = parts.Groups["detail"].Success ? parts.Groups["detail"].Value.Trim() : null;
        }
        else
        {
            name = message;
        }

        var root = Path.GetPathRoot(filePath) ?? "";
        return new VisionAlarmEvent
        {
            // The byte offset makes two identical alarm lines in the same file distinct
            // events, which a hash of the text alone could not.
            EventUid = Md5($"{agentId}|{Path.GetFileName(filePath)}|{byteOffset}|{rawLine}"),
            SourceDrive = root.TrimEnd('\\', '/', ':'),
            SourceFile = filePath,
            Code = Blank(code),
            Name = Blank(name),
            Detail = Blank(detail),
            RawMessage = Blank(message),
            RawLine = rawLine,
            AlarmAt = alarmAt,
            AlarmAtRaw = Blank(timestampRaw)
        };
    }

    /// <summary>
    /// Splits appended text into lines carrying their absolute byte offset in the file.
    /// The offsets are what make each alarm individually identifiable across restarts.
    /// </summary>
    public static IEnumerable<(string Line, long Offset, bool Complete)> SplitWithOffsets(
        string text, Encoding encoding, long baseOffset)
    {
        var position = baseOffset;
        var i = 0;
        while (i < text.Length)
        {
            var start = i;
            while (i < text.Length && text[i] != '\n' && text[i] != '\r') i++;
            var line = text[start..i];

            var terminator = 0;
            if (i < text.Length)
            {
                terminator = text[i] == '\r' && i + 1 < text.Length && text[i + 1] == '\n' ? 2 : 1;
            }

            yield return (line, position, terminator > 0);
            if (terminator == 0) yield break;

            position += encoding.GetByteCount(text.AsSpan(start, i - start))
                        + encoding.GetByteCount(text.AsSpan(i, terminator));
            i += terminator;
        }
    }

    private static string Md5(string value)
    {
        var hash = MD5.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static string? Blank(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
