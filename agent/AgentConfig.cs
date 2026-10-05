using System.Text.Json;
using VisionAgent.Catalog;

namespace VisionAgent;

/// <summary>
/// agent.json - everything that differs between one inspection PC and the next, and
/// nothing else. Parsing rules, judgements and image columns all come from the shared
/// catalog, so this file stays five lines and cannot contradict the server.
///
/// VisionKeys is a list because one Example A PC runs a single process covering two
/// separate inspectors (Anode and Cathode), each with its own CSV and its own agent_id.
/// </summary>
public sealed class AgentConfig
{
    public string Line { get; set; } = "";
    public List<string> VisionKeys { get; set; } = new();
    public string ModelToken { get; set; } = "";
    public int PollIntervalMs { get; set; } = 1000;
    public ServerConfig Server { get; set; } = new();
    /// <summary>Print events instead of sending them - used to verify parsing against
    /// real CSVs before a PC is ever pointed at the live server.</summary>
    public bool DryRun { get; set; }
    public string? StateFile { get; set; }
    public string? LogFile { get; set; }
    /// <summary>Overrides the catalog's folder, for testing against a local copy of a
    /// vision PC's output. Left unset in production.</summary>
    public string? SourceFolderOverride { get; set; }

    public static AgentConfig Load(string path)
    {
        var json = File.ReadAllText(path);
        var config = JsonSerializer.Deserialize<AgentConfig>(json, JsonDefaults.Options)
                     ?? throw new InvalidOperationException($"{path} is empty or invalid");
        if (string.IsNullOrWhiteSpace(config.Line))
        {
            throw new InvalidOperationException($"{path}: line is required");
        }
        if (config.VisionKeys.Count == 0)
        {
            throw new InvalidOperationException($"{path}: visionKeys must list at least one vision type");
        }
        return config;
    }

    public string AgentId(string visionKey) => $"{Line}_{visionKey}";
}

public sealed class ServerConfig
{
    public string EventsUrl { get; set; } = "http://127.0.0.1:8080/dashboard/api/ingest/events";
    public string HeartbeatHost { get; set; } = "127.0.0.1";
    public int HeartbeatPort { get; set; } = 6002;
    public int HeartbeatIntervalMs { get; set; } = 2000;
    public int TimeoutSeconds { get; set; } = 10;
}

public static class JsonDefaults
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };
}

/// <summary>Loads the shared catalog and hands out the vision types this PC serves.</summary>
public sealed class AgentCatalog
{
    private readonly Dictionary<string, VisionTypeDef> _byKey;

    private AgentCatalog(Dictionary<string, VisionTypeDef> byKey) => _byKey = byKey;

    public static AgentCatalog Load(string path)
    {
        var file = JsonSerializer.Deserialize<CatalogFile>(File.ReadAllText(path), JsonDefaults.Options)
                   ?? throw new InvalidOperationException($"{path} is empty or invalid");
        var byKey = file.VisionTypes.ToDictionary(v => v.Key, v => v, StringComparer.OrdinalIgnoreCase);
        return new AgentCatalog(byKey);
    }

    /// <summary>Fails startup rather than running with a key the server would reject -
    /// a typo here used to surface only as a permanently empty cell on the dashboard.</summary>
    public VisionTypeDef Require(string visionKey) =>
        _byKey.TryGetValue(visionKey, out var type)
            ? type
            : throw new InvalidOperationException(
                $"visionKey '{visionKey}' is not in the catalog. Known keys: {string.Join(", ", _byKey.Keys)}");
}
