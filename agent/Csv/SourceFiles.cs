using VisionAgent.Catalog;

namespace VisionAgent.Csv;

/// <summary>
/// Finds the result CSVs a vision type is currently writing.
///
/// Today's file *and* yesterday's are always considered. The date in the filename
/// rolls at midnight but the vision software does not stop mid-second, so matching
/// only today's pattern loses whatever lands in yesterday's file just after 00:00 -
/// silently and permanently, since nothing ever looks at that file again.
/// </summary>
public sealed class SourceFiles
{
    private readonly string _line;
    private readonly string _modelToken;
    private readonly string? _folderOverride;

    public SourceFiles(string line, string modelToken, string? folderOverride)
    {
        _line = line;
        _modelToken = modelToken;
        _folderOverride = folderOverride;
    }

    public IEnumerable<string> Discover(SourceDef source, DateTime now)
    {
        var folder = _folderOverride ?? source.Folder;
        if (!Directory.Exists(folder))
        {
            yield break;
        }

        // Yesterday first, so that just after midnight the tail of yesterday's file is
        // read before today's opening rows and lot changes stay in chronological order.
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var day in new[] { now.Date.AddDays(-1), now.Date })
        {
            var pattern = Render(source.FilePattern, day);
            IEnumerable<string> matches;
            try
            {
                matches = Directory.EnumerateFiles(folder, pattern, SearchOption.TopDirectoryOnly);
            }
            catch (Exception)
            {
                // An inaccessible folder is a transient condition on a shared drive;
                // the next poll retries rather than taking the agent down.
                yield break;
            }

            foreach (var file in matches.OrderBy(f => f, StringComparer.OrdinalIgnoreCase))
            {
                if (ShouldIgnore(Path.GetFileName(file), source.IgnoreSuffixes)) continue;
                if (seen.Add(Path.GetFullPath(file))) yield return file;
            }
        }
    }

    public string Render(string pattern, DateTime day) => pattern
        .Replace("{yyyyMMdd}", day.ToString("yyyyMMdd"))
        .Replace("{line}", _line)
        .Replace("{modelToken}", _modelToken);

    /// <summary>The per-defect summary CSV sits in the same folder and has the same date
    /// suffix; parsing it as a result file would double-count everything.</summary>
    private static bool ShouldIgnore(string fileName, List<string> ignoreSuffixes) =>
        ignoreSuffixes.Any(s => !string.IsNullOrWhiteSpace(s)
                                && fileName.EndsWith(s, StringComparison.OrdinalIgnoreCase));
}
