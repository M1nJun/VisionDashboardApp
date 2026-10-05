using System.Text;

namespace VisionAgent.Csv;

public static class CsvLine
{
    /// <summary>Splits one CSV line, honouring quoted fields and doubled quotes.</summary>
    public static List<string> Split(string line, char delimiter)
    {
        var result = new List<string>();
        var field = new StringBuilder();
        var inQuotes = false;

        for (var i = 0; i < line.Length; i++)
        {
            var c = line[i];
            if (c == '"')
            {
                if (inQuotes && i + 1 < line.Length && line[i + 1] == '"')
                {
                    field.Append('"');
                    i++;
                }
                else
                {
                    inQuotes = !inQuotes;
                }
            }
            else if (c == delimiter && !inQuotes)
            {
                result.Add(field.ToString());
                field.Clear();
            }
            else
            {
                field.Append(c);
            }
        }

        result.Add(field.ToString());
        return result;
    }
}

/// <summary>Header names to positions, built once per file and reused for every row.</summary>
public sealed class CsvHeader
{
    private readonly Dictionary<string, int> _index;
    public IReadOnlyList<string> Names { get; }

    public CsvHeader(IEnumerable<string> names)
    {
        Names = names.Select(n => n.Trim()).ToList();
        _index = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < Names.Count; i++)
        {
            // First occurrence wins; a repeated header name is the vision software's
            // doing and picking the later one would silently shift every read.
            _index.TryAdd(Names[i], i);
        }
    }

    public bool Has(string column) => !string.IsNullOrEmpty(column) && _index.ContainsKey(column);

    public int IndexOf(string column) => _index.TryGetValue(column, out var i) ? i : -1;

    public IEnumerable<string> NamesEndingWith(string suffix) =>
        Names.Where(n => n.EndsWith(suffix, StringComparison.OrdinalIgnoreCase));
}

public sealed class CsvRow
{
    private readonly CsvHeader _header;
    private readonly List<string> _fields;

    public CsvRow(CsvHeader header, List<string> fields)
    {
        _header = header;
        _fields = fields;
    }

    public string Get(string? column)
    {
        if (string.IsNullOrWhiteSpace(column)) return "";
        var i = _header.IndexOf(column);
        return i >= 0 && i < _fields.Count ? _fields[i].Trim() : "";
    }

    public bool Has(string column) => _header.Has(column);
}
