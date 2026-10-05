using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Hosting.WindowsServices;
using VisionAgent;
using VisionAgent.Logging;
using VisionAgent.State;
using VisionAgent.Transport;
using VisionAgent.Workers;

// Config is resolved before the host is built so a missing or contradictory setup fails
// with a plain message, rather than as a dependency-injection error buried in service
// startup - which on a factory PC means an event-log entry nobody will read.
var runOnce = args.Contains("--once", StringComparer.OrdinalIgnoreCase);
var positional = args.Where(a => !a.StartsWith("--", StringComparison.Ordinal)).ToArray();
var configPath = positional.Length > 0 ? positional[0] : "agent.json";

var appDir = AppContext.BaseDirectory;
configPath = Path.IsPathRooted(configPath) ? configPath : Path.Combine(appDir, configPath);

if (!File.Exists(configPath))
{
    Console.Error.WriteLine($"Missing config: {configPath}");
    Console.Error.WriteLine("Copy agent.example.json to agent.json and set line/visionKeys/server.");
    return 2;
}

AgentConfig config;
AgentCatalog catalog;
try
{
    config = AgentConfig.Load(configPath);
    var catalogPath = Path.Combine(appDir, "vision-catalog.json");
    if (!File.Exists(catalogPath))
    {
        Console.Error.WriteLine($"Missing catalog: {catalogPath}");
        return 2;
    }
    catalog = AgentCatalog.Load(catalogPath);

    // Fail now rather than at the first CSV row: an unknown key would otherwise produce
    // events the server rejects for the lifetime of the deployment.
    foreach (var visionKey in config.VisionKeys)
    {
        catalog.Require(visionKey);
    }
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    return 2;
}

var statePath = Absolute(config.StateFile ?? "state.json", appDir);
var logPath = Absolute(config.LogFile ?? "agent.log", appDir);
var logger = new FileLogger(logPath);
var state = AgentState.Load(statePath);
var sender = new EventSender(config, logger);

logger.Info($"VisionAgent {AgentVersion.Current} starting");
logger.Info($"  line       : {config.Line}");
logger.Info($"  agents     : {string.Join(", ", config.VisionKeys.Select(config.AgentId))}");
logger.Info($"  server     : {(config.DryRun ? "(dry run - nothing is sent)" : config.Server.EventsUrl)}");
logger.Info($"  state      : {statePath}");

var builder = Host.CreateApplicationBuilder();
// The host's own startup banner would only repeat, less usefully, what the lines above
// already logged.
builder.Logging.ClearProviders();

builder.Services.AddSingleton(config);
builder.Services.AddSingleton(catalog);
builder.Services.AddSingleton(state);
builder.Services.AddSingleton(sender);
builder.Services.AddSingleton(logger);
builder.Services.AddHostedService(sp => new CollectorWorker(config, catalog, state, sender, logger, runOnce));
if (!runOnce)
{
    builder.Services.AddHostedService(sp => new HeartbeatWorker(config, logger));
}
// A no-op when launched from a console, a real service when started by the SCM - one
// binary covers both, so what an engineer tests by hand is what runs in production.
builder.Services.AddWindowsService(options => options.ServiceName = "VisionDashboardAgent");

using var host = builder.Build();

if (runOnce)
{
    // Single pass, then exit - used to verify parsing against real CSVs.
    await host.StartAsync();
    await Task.Delay(TimeSpan.FromMilliseconds(500));
    await host.StopAsync();
    state.SaveIfChanged();
    return 0;
}

await host.RunAsync();
state.SaveIfChanged();
return 0;

static string Absolute(string path, string baseDir) =>
    Path.IsPathRooted(path) ? path : Path.Combine(baseDir, path);
