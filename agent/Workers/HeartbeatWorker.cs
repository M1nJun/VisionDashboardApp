using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Hosting;
using VisionAgent.Logging;
using VisionAgent.Transport;

namespace VisionAgent.Workers;

/// <summary>
/// A small UDP packet per agent every couple of seconds, so a vision that is simply
/// quiet (running, producing nothing but OK cells) is distinguishable from one whose
/// PC has died. UDP because a dropped heartbeat costs nothing - the next is seconds away.
/// </summary>
public sealed class HeartbeatWorker : BackgroundService
{
    private readonly AgentConfig _config;
    private readonly FileLogger _log;

    public HeartbeatWorker(AgentConfig config, FileLogger log)
    {
        _config = config;
        _log = log;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (_config.DryRun) return;

        var interval = _config.Server.HeartbeatIntervalMs <= 0 ? 2000 : _config.Server.HeartbeatIntervalMs;
        using var udp = new UdpClient();

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                // One packet per vision key: an Example A PC hosts two agents, and a single
                // packet would leave the other one showing OFFLINE forever.
                foreach (var visionKey in _config.VisionKeys)
                {
                    var payload = JsonSerializer.Serialize(new
                    {
                        agentId = _config.AgentId(visionKey),
                        line = _config.Line,
                        visionKey,
                        agentVersion = AgentVersion.Current,
                        ts = DateTimeOffset.Now
                    }, JsonDefaults.Options);

                    var bytes = Encoding.UTF8.GetBytes(payload);
                    await udp.SendAsync(bytes, _config.Server.HeartbeatHost, _config.Server.HeartbeatPort,
                        stoppingToken);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.Warn("heartbeat send failed: " + ex.Message);
            }

            try
            {
                await Task.Delay(interval, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }
}
