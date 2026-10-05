using System.Globalization;
using System.Text.RegularExpressions;
using VisionAgent.Catalog;
using VisionAgent.Csv;
using VisionAgent.Transport;

namespace VisionAgent.Parsing;

/// <summary>
/// One CSV row becomes one UnitInspectedEvent - always exactly one, however many
/// inspection items that unit failed. The failed items travel inside the event, so
/// there is no rule anywhere about "only the first one counts".
/// </summary>
public static class UnitBuilder
{
    private const string Unspecified = "UNSPECIFIED";

    public static UnitInspectedEvent? Build(VisionTypeDef type, CsvHeader header, CsvRow row,
                                            string sourceFileName, DateTimeOffset occurredAt)
    {
        var source = type.Source;
        var seqText = row.Get(source.Columns.UnitSeq);
        if (!long.TryParse(seqText, out var unitSeq))
        {
            // No usable NO means no way to order or de-duplicate this row; the caller logs it.
            return null;
        }

        var warnings = new List<string>();
        var unit = new UnitInspectedEvent
        {
            SourceFile = sourceFileName,
            UnitSeq = unitSeq,
            LotId = NullIfBlank(row.Get(source.Columns.LotId)),
            CellId = NullIfBlank(row.Get(source.Columns.CellId)),
            ModelId = ResolveModelId(source, row, sourceFileName, warnings),
            OccurredAt = ReadTime(source, row, sourceFileName, warnings) ?? occurredAt
        };

        var judgementRaw = row.Get(source.Columns.Judgement);
        if (type.IsOk(judgementRaw))
        {
            unit.Judgement = type.Judgements.Ok;
            unit.Warnings = warnings;
            return unit;
        }

        var defectCode = type.DefectCode(judgementRaw);
        if (defectCode is null)
        {
            // Counted as production but never as a defect - the server decides what to do
            // with UNKNOWN. Dropping the row would quietly shrink the denominator.
            unit.Judgement = "UNKNOWN";
            warnings.Add($"judgement '{judgementRaw}' is not defined for {type.Key}");
            unit.Warnings = warnings;
            return unit;
        }

        unit.Judgement = defectCode;
        unit.Items = source.ItemDiscovery.Mode.Equals("SCAN_SUFFIX", StringComparison.OrdinalIgnoreCase)
            ? ScanItems(source.ItemDiscovery, header, row, warnings)
            : NamedItems(source.ItemDiscovery, header, row, defectCode, warnings);
        unit.Images = SelectImages(source.Images, row, unit.Items, warnings);
        unit.Warnings = warnings;
        return unit;
    }

    /// <summary>
    /// SCAN_SUFFIX: every column ending in the suffix is one inspection item's verdict,
    /// and a unit can fail several at once.
    /// </summary>
    private static List<DefectItem> ScanItems(ItemDiscoveryDef discovery, CsvHeader header, CsvRow row,
                                              List<string> warnings)
    {
        var items = new List<DefectItem>();
        foreach (var column in header.NamesEndingWith(discovery.Suffix))
        {
            // Exact match on purpose: "Bypass_NG" contains "NG" but means the check was
            // deliberately overridden, which is not a defect.
            if (!string.Equals(row.Get(column).Trim(), discovery.TriggerValue, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var itemName = column[..^discovery.Suffix.Length];
            var valueColumn = discovery.ValueColumnFormat.Replace("{ITEM}", itemName);
            items.Add(new DefectItem
            {
                Name = itemName,
                // The item name is the grouping key (a raw measurement is almost never the
                // same twice), but the measurement is kept because it cannot be recovered later.
                RawValue = NullIfBlank(row.Get(valueColumn))
            });
        }

        if (items.Count == 0)
        {
            warnings.Add($"judgement was a defect but no column ending in '{discovery.Suffix}' held '{discovery.TriggerValue}'");
            items.Add(new DefectItem { Name = Unspecified });
        }
        return items;
    }

    /// <summary>
    /// NAMED_COLUMN: the row already names its one defect, and the dynamic per-side
    /// columns only say which face of the unit caused it. The same defect failing on
    /// both faces is two items, because those are two distinct failure locations.
    /// </summary>
    private static List<DefectItem> NamedItems(ItemDiscoveryDef discovery, CsvHeader header, CsvRow row,
                                               string judgement, List<string> warnings)
    {
        var itemName = row.Get(discovery.ItemNameColumn ?? "");
        if (string.IsNullOrWhiteSpace(itemName))
        {
            warnings.Add($"column '{discovery.ItemNameColumn}' was empty");
            return new List<DefectItem> { new() { Name = Unspecified } };
        }

        var formats = discovery.SideColumnFormats.TryGetValue(judgement, out var configured)
            ? configured
            : new List<string>();
        if (formats.Count == 0)
        {
            warnings.Add($"no sideColumnFormats configured for judgement '{judgement}'");
            return new List<DefectItem> { new() { Name = itemName } };
        }

        var anyColumnFound = false;
        // Formats are tried whole rather than per side: if the first naming style yields no
        // defective side anywhere, the vision wrote this defect in the other style, and
        // switching per-side would mix verdicts from two different columns.
        foreach (var format in formats)
        {
            var matched = new List<DefectItem>();
            foreach (var side in discovery.Sides)
            {
                var column = format.Replace("{SIDE}", side).Replace("{ITEM}", itemName);
                if (!header.Has(column)) continue;

                anyColumnFound = true;
                var value = row.Get(column);
                if (discovery.SideDefectValues.Contains(value, StringComparer.OrdinalIgnoreCase))
                {
                    matched.Add(new DefectItem { Name = itemName, Side = side, RawValue = NullIfBlank(value) });
                }
            }
            if (matched.Count > 0) return matched;
        }

        warnings.Add(anyColumnFound
            ? $"no side column marked '{itemName}' defective"
            : $"no side column exists for '{itemName}' in any configured format");
        return new List<DefectItem> { new() { Name = itemName } };
    }

    /// <summary>
    /// Images belong to the unit, not to an individual item - the paths in a row are
    /// fixed for the whole physical unit. Selecting once per unit is why the same photo
    /// can no longer be attached several times when several items fail together.
    /// </summary>
    private static List<DefectImage> SelectImages(ImagesDef images, CsvRow row, List<DefectItem> items,
                                                  List<string> warnings)
    {
        var itemNames = items.Select(i => i.Name).ToList();
        var labels = images.Selection.ToUpperInvariant() switch
        {
            "BY_DEFECT_SIDE" => items.Where(i => i.Side is not null).Select(i => i.Side!)
                                     .Distinct(StringComparer.OrdinalIgnoreCase).ToList(),
            "BY_ITEM_MARKER" => MarkedLabels(images, itemNames),
            _ => images.Sets.Keys.ToList()
        };

        var selected = new List<DefectImage>();
        foreach (var label in labels)
        {
            var pair = images.Resolve(label, itemNames);
            if (pair is null)
            {
                warnings.Add($"no image columns configured for set '{label}'");
                continue;
            }

            AddIfPresent(selected, label, "MAIN", row.Get(pair.Main), pair.Main, warnings);
            AddIfPresent(selected, label, "OVERLAY", row.Get(pair.Overlay), pair.Overlay, warnings);
        }
        return selected;
    }

    /// <summary>An item whose name carries a polarity marker selects only that side's
    /// images; an item carrying no marker at all is not side-specific, so every set applies.</summary>
    private static List<string> MarkedLabels(ImagesDef images, List<string> itemNames)
    {
        var matched = images.Markers
            .Where(m => itemNames.Any(name => name.Contains(m.Value, StringComparison.OrdinalIgnoreCase)))
            .Select(m => m.Key)
            .ToList();
        return matched.Count > 0 ? matched : images.Sets.Keys.ToList();
    }

    private static void AddIfPresent(List<DefectImage> images, string label, string kind, string path,
                                     string column, List<string> warnings)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            warnings.Add($"image column '{column}' was empty");
            return;
        }
        images.Add(new DefectImage { Set = label, Kind = kind, Path = path });
    }

    /// <summary>
    /// When the machine inspected this unit, read from the row itself.
    ///
    /// The alternative - the moment the agent happened to read the file - is only the
    /// same thing while the agent is keeping up. Every row of a backlog is read in one
    /// pass, so a stoppage or a resync put hundreds of defects on the dashboard at one
    /// identical second and drew a spike in the trend where the machine had been running
    /// evenly for hours. Falls back to the reading time when no time column is mapped or
    /// the vision left it blank.
    /// </summary>
    private static DateTimeOffset? ReadTime(SourceDef source, CsvRow row, string fileName,
                                            List<string> warnings)
    {
        var column = source.Columns.OccurredAt;
        if (string.IsNullOrWhiteSpace(column)) return null;

        var raw = row.Get(column).Trim();
        // A blank time is not an error, the same way a blank lot is not: it is a field the
        // vision did not fill in on this row.
        if (raw.Length == 0) return null;

        if (DateTime.TryParseExact(raw, source.TimeFormats.ToArray(), CultureInfo.InvariantCulture,
                                   DateTimeStyles.None, out var stamped))
        {
            return Local(stamped);
        }

        if (DateTime.TryParseExact(raw, source.TimeOnlyFormats.ToArray(), CultureInfo.InvariantCulture,
                                   DateTimeStyles.None, out var timeOnly))
        {
            // A time with no date belongs to the day its file is named for. Taking today's
            // date instead would move every row of yesterday's file forward a day.
            return Local((DateOf(fileName) ?? DateTime.Today) + timeOnly.TimeOfDay);
        }

        warnings.Add($"could not read a time out of '{column}' value '{raw}'");
        return null;
    }

    private static DateTimeOffset Local(DateTime at) =>
        new(at, TimeZoneInfo.Local.GetUtcOffset(at));

    /// <summary>The yyyyMMdd a file is named for, if its name carries one.</summary>
    private static DateTime? DateOf(string fileName)
    {
        for (var i = 0; i + 8 <= fileName.Length; i++)
        {
            if (DateTime.TryParseExact(fileName.Substring(i, 8), "yyyyMMdd",
                                       CultureInfo.InvariantCulture, DateTimeStyles.None, out var day))
            {
                return day;
            }
        }
        return null;
    }

    private static string? ResolveModelId(SourceDef source, CsvRow row, string fileName, List<string> warnings)
    {
        if (!source.ModelId.From.Equals("FILENAME", StringComparison.OrdinalIgnoreCase))
        {
            return NullIfBlank(row.Get(source.Columns.ModelId));
        }

        var pattern = source.ModelId.Regex;
        if (string.IsNullOrWhiteSpace(pattern)) return null;

        var match = Regex.Match(fileName, pattern, RegexOptions.IgnoreCase);
        if (match.Success && match.Groups["model"].Success)
        {
            return NullIfBlank(match.Groups["model"].Value);
        }
        warnings.Add($"could not read a model id out of '{fileName}'");
        return null;
    }

    private static string? NullIfBlank(string value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
