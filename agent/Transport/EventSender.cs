using System.Net.Http.Json;
using System.Text.Json;
using VisionAgent.Logging;

namespace VisionAgent.Transport;

/// <summary>
/// Posts batches to the server.
///
/// Batching rather than one request per CSV row: a busy line produces thousands of rows
/// a minute, and each request was previously its own HTTP round trip and its own database
/// transaction. It also makes a restart's catch-up cheap instead of a burst of thousands
/// of individually-rejected duplicates.
/// </summary>
public sealed class EventSender
{
    public const int MaxEventsPerBatch = 500;

    private readonly HttpClient _http;
    private readonly AgentConfig _config;
    private readonly FileLogger _log;

    public EventSender(AgentConfig config, FileLogger log)
    {
        _config = config;
        _log = log;
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(config.Server.TimeoutSeconds) };
    }

    /// <summary>
    /// Sends everything or reports failure; the caller must not advance its file offset
    /// unless this returns true. Re-sending is safe - the server filters replays - so
    /// erring towards sending twice is always better than skipping.
    /// </summary>
    public async Task<bool> SendAsync(string visionKey, IReadOnlyList<object> events, CancellationToken token)
    {
        if (events.Count == 0) return true;

        for (var offset = 0; offset < events.Count; offset += MaxEventsPerBatch)
        {
            var chunk = events.Skip(offset).Take(MaxEventsPerBatch).ToList();
            var batch = new EventBatch
            {
                AgentId = _config.AgentId(visionKey),
                Line = _config.Line,
                VisionKey = visionKey,
                AgentVersion = AgentVersion.Current,
                SentAt = DateTimeOffset.Now,
                Events = chunk
            };

            if (_config.DryRun)
            {
                Console.WriteLine(JsonSerializer.Serialize(batch,
                    new JsonSerializerOptions(JsonDefaults.Options) { WriteIndented = true }));
                continue;
            }

            if (!await PostAsync(batch, token))
            {
                return false;
            }
        }
        return true;
    }

    private async Task<bool> PostAsync(EventBatch batch, CancellationToken token)
    {
        try
        {
            using var response = await _http.PostAsJsonAsync(_config.Server.EventsUrl, batch, JsonDefaults.Options, token);
            if (!response.IsSuccessStatusCode)
            {
                var body = await response.Content.ReadAsStringAsync(token);
                // 4xx means this batch is malformed and will be rejected identically
                // forever; retrying it would wedge the agent on one bad row. Skip past it
                // so later rows keep flowing, and leave the reason in the log.
                var permanent = (int)response.StatusCode is >= 400 and < 500;
                _log.Warn($"server returned {(int)response.StatusCode}: {Truncate(body, 300)}"
                          + (permanent ? " - skipping this batch" : " - will retry"));
                return permanent;
            }

            var result = await response.Content.ReadFromJsonAsync<IngestResult>(JsonDefaults.Options, token);
            if (result is { Rejected.Count: > 0 })
            {
                foreach (var rejected in result.Rejected)
                {
                    _log.Warn($"server rejected event #{rejected.Index}: {rejected.Reason}");
                }
            }
            return true;
        }
        // HttpClient reports its own timeout as a cancellation, indistinguishable by type
        // from the one that means "the service is stopping". Only the caller's token can
        // tell them apart. Letting a timeout through as cancellation killed the collector
        // loop outright: the service stayed up, kept heartbeating, and silently never read
        // another CSV row - the whole fleet went quiet the first time the server paused
        // longer than the timeout.
        catch (OperationCanceledException) when (!token.IsCancellationRequested)
        {
            _log.Warn($"send timed out after {_config.Server.TimeoutSeconds}s, will retry");
            return false;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            _log.Warn($"send failed, will retry: {ex.Message}");
            return false;
        }
    }

    private static string Truncate(string value, int max) =>
        value.Length <= max ? value : value[..max] + "...";
}

public static class AgentVersion
{
    public static readonly string Current =
        typeof(AgentVersion).Assembly.GetName().Version?.ToString(3) ?? "0.0.0";
}
