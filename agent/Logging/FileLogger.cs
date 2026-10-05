namespace VisionAgent.Logging;

/// <summary>
/// Console plus a rolling file next to the exe. Deliberately tiny: this runs as a
/// Windows Service on a factory PC with no log collector, so the file beside the exe
/// is the only thing an engineer can look at over SMB.
/// </summary>
public sealed class FileLogger
{
    private const long MaxBytes = 8 * 1024 * 1024;

    private readonly string _path;
    private readonly object _gate = new();

    public FileLogger(string path) => _path = path;

    public void Info(string message) => Write("INFO", message);
    public void Warn(string message) => Write("WARN", message);
    public void Error(string message) => Write("ERROR", message);

    private void Write(string level, string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level}] {message}";
        Console.WriteLine(line);
        lock (_gate)
        {
            try
            {
                Roll();
                File.AppendAllText(_path, line + Environment.NewLine);
            }
            catch (Exception)
            {
                // Losing a log line must never take the agent down - the console copy above
                // has already gone out.
            }
        }
    }

    /// <summary>Keeps one previous file. Unbounded growth on a PC that never reboots
    /// eventually fills the system drive.</summary>
    private void Roll()
    {
        var info = new FileInfo(_path);
        if (!info.Exists || info.Length < MaxBytes) return;

        var previous = _path + ".1";
        if (File.Exists(previous)) File.Delete(previous);
        File.Move(_path, previous);
    }
}
