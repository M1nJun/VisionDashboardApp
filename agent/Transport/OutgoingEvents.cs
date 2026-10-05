namespace VisionAgent.Transport;

/// <summary>
/// The wire shape from contracts/events.md. Serialised camelCase by JsonDefaults.
///
/// Note the absence of any delta or count field: one UnitInspectedEvent is one physical
/// unit, and the server derives every number from that. A unit that failed several
/// inspection items carries them in Items - it is never split into several events.
/// </summary>
public sealed class EventBatch
{
    public string AgentId { get; set; } = "";
    public string Line { get; set; } = "";
    public string VisionKey { get; set; } = "";
    public string AgentVersion { get; set; } = "";
    public DateTimeOffset SentAt { get; set; } = DateTimeOffset.Now;
    public List<object> Events { get; set; } = new();
}

public sealed class UnitInspectedEvent
{
    public string Type => "UNIT_INSPECTED";
    public string SourceFile { get; set; } = "";
    public long UnitSeq { get; set; }
    public string? LotId { get; set; }
    public string? ModelId { get; set; }
    public string? CellId { get; set; }
    public string Judgement { get; set; } = "";
    public DateTimeOffset OccurredAt { get; set; }
    public List<DefectItem> Items { get; set; } = new();
    public List<DefectImage> Images { get; set; } = new();
    public List<string> Warnings { get; set; } = new();
}

public sealed class DefectItem
{
    public string Name { get; set; } = "";
    public string? RawValue { get; set; }
    public string? Side { get; set; }
}

/// <summary>One image FILE. Main and overlay are separate so a missing overlay path
/// cannot discard a perfectly good main image.</summary>
public sealed class DefectImage
{
    public string Set { get; set; } = "";
    public string Kind { get; set; } = "";
    public string Path { get; set; } = "";
}

public sealed class LotChangedEvent
{
    public string Type => "LOT_CHANGED";
    public string SourceFile { get; set; } = "";
    public string? OldLotId { get; set; }
    public string NewLotId { get; set; } = "";
    public long DetectedAtUnitSeq { get; set; }
    public DateTimeOffset OccurredAt { get; set; }
}

public sealed class VisionAlarmEvent
{
    public string Type => "VISION_ALARM";
    public string EventUid { get; set; } = "";
    public string? SourceDrive { get; set; }
    public string SourceFile { get; set; } = "";
    public string? Code { get; set; }
    public string? Name { get; set; }
    public string? Detail { get; set; }
    public string? RawMessage { get; set; }
    public string RawLine { get; set; } = "";
    public DateTimeOffset? AlarmAt { get; set; }
    public string? AlarmAtRaw { get; set; }
}

public sealed class IngestResult
{
    public int Accepted { get; set; }
    public int Replayed { get; set; }
    public List<RejectedEvent> Rejected { get; set; } = new();

    public sealed class RejectedEvent
    {
        public int Index { get; set; }
        public string Reason { get; set; } = "";
    }
}
