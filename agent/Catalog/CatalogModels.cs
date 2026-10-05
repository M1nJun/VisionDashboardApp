namespace VisionAgent.Catalog;

/// <summary>
/// contracts/vision-catalog.json as the agent sees it. Deserialised case-insensitively,
/// so the file's camelCase maps onto these names, and any "$comment_*" keys the file
/// carries for human readers are simply ignored.
/// </summary>
public sealed class CatalogFile
{
    public List<VisionTypeDef> VisionTypes { get; set; } = new();
}

public sealed class VisionTypeDef
{
    public string Key { get; set; } = "";
    public string DisplayName { get; set; } = "";
    public JudgementsDef Judgements { get; set; } = new();
    public SourceDef Source { get; set; } = new();

    public bool IsOk(string judgement) =>
        string.Equals(Judgements.Ok, judgement, StringComparison.OrdinalIgnoreCase);

    public string? DefectCode(string judgement) => Judgements.Defect
        .FirstOrDefault(d => string.Equals(d.Code, judgement, StringComparison.OrdinalIgnoreCase))?.Code;
}

public sealed class JudgementsDef
{
    public string Ok { get; set; } = "OK";
    public List<DefectDef> Defect { get; set; } = new();
}

public sealed class DefectDef
{
    public string Code { get; set; } = "";
    public bool DrivesColor { get; set; }
}

public sealed class SourceDef
{
    public string Folder { get; set; } = ".";
    public string FilePattern { get; set; } = "*.csv";
    public List<string> IgnoreSuffixes { get; set; } = new();
    public string Encoding { get; set; } = "utf-8";
    public string Delimiter { get; set; } = ",";
    public ColumnsDef Columns { get; set; } = new();

    /// <summary>Accepted layouts for a time column that carries its own date.</summary>
    public List<string> TimeFormats { get; set; } = new()
    {
        "yyyy-MM-dd HH:mm:ss.fff", "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss.fff", "yyyy/MM/dd HH:mm:ss",
        "yyyyMMddHHmmssfff", "yyyyMMddHHmmss"
    };

    /// <summary>Accepted layouts for a time column with no date in it; the day then comes
    /// from the file the row is in.</summary>
    public List<string> TimeOnlyFormats { get; set; } = new()
    {
        "HH:mm:ss.fff", "HH:mm:ss", "H:mm:ss.fff", "H:mm:ss"
    };
    public ModelIdDef ModelId { get; set; } = new();
    public ItemDiscoveryDef ItemDiscovery { get; set; } = new();
    public ImagesDef Images { get; set; } = new();
    public AlarmLogDef AlarmLog { get; set; } = new();

    public char DelimiterChar => string.IsNullOrEmpty(Delimiter) ? ',' : Delimiter[0];
}

public sealed class ColumnsDef
{
    public string UnitSeq { get; set; } = "NO";
    public string? ModelId { get; set; }
    public string LotId { get; set; } = "LOT-ID";
    public string CellId { get; set; } = "CELL-ID";
    public string Judgement { get; set; } = "JUDGE";

    /// <summary>
    /// Optional: the column holding the moment the machine inspected the unit.
    ///
    /// Leave it unset and every row is stamped with the moment the agent read it, which
    /// is only the same thing while the agent is keeping up. It is not the same thing
    /// after a stoppage, a redeploy or a resync - the whole backlog then arrives stamped
    /// with one identical instant.
    /// </summary>
    public string? OccurredAt { get; set; }
}

public sealed class ModelIdDef
{
    /// <summary>COLUMN or FILENAME.</summary>
    public string From { get; set; } = "COLUMN";
    public string? Regex { get; set; }
}

public sealed class ItemDiscoveryDef
{
    /// <summary>NAMED_COLUMN (the row names its one defect) or SCAN_SUFFIX (every
    /// column ending in Suffix is a separate pass/fail item).</summary>
    public string Mode { get; set; } = "NAMED_COLUMN";

    public string? ItemNameColumn { get; set; }
    public List<string> Sides { get; set; } = new();
    public Dictionary<string, List<string>> SideColumnFormats { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public List<string> SideDefectValues { get; set; } = new();

    public string Suffix { get; set; } = "-OK/NG";
    public string TriggerValue { get; set; } = "NG";
    public string ValueColumnFormat { get; set; } = "{ITEM}";
}

public sealed class ImagesDef
{
    /// <summary>ALL, BY_ITEM_MARKER or BY_DEFECT_SIDE.</summary>
    public string Selection { get; set; } = "ALL";

    /// <summary>Set label -> the substring in a failing item's name that selects it.</summary>
    public Dictionary<string, string> Markers { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Variant name -> item names that should use it instead of "default".</summary>
    public Dictionary<string, List<string>> VariantByItem { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Set label -> variant -> column pair. Every set has a "default".</summary>
    public Dictionary<string, Dictionary<string, ImagePair>> Sets { get; set; } =
        new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Which column pair to read for this set given the failing item's name.</summary>
    public ImagePair? Resolve(string setLabel, IEnumerable<string> itemNames)
    {
        if (!Sets.TryGetValue(setLabel, out var variants)) return null;

        foreach (var (variant, items) in VariantByItem)
        {
            if (!variants.ContainsKey(variant)) continue;
            if (itemNames.Any(name => items.Contains(name, StringComparer.OrdinalIgnoreCase)))
            {
                return variants[variant];
            }
        }
        return variants.TryGetValue("default", out var fallback) ? fallback : null;
    }
}

public sealed class ImagePair
{
    public string Main { get; set; } = "";
    public string Overlay { get; set; } = "";
}

public sealed class AlarmLogDef
{
    public List<string> Drives { get; set; } = new() { "E" };
    public string Folder { get; set; } = @"Example\LOG";
    public string FileNameFormat { get; set; } = "yyMMdd.Status.log";
    public string Marker { get; set; } = "[Alarm]";
    public string Encoding { get; set; } = "utf-8";
}
