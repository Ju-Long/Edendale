// The sync and handoff log (SYNC-PROTOCOL.md §8.5, FEATURES-27.1.md H10):
// counts, file names, stamps, and error classes only. Nothing that calls it
// passes record contents, titles, codes, keys, tokens, passwords, or URLs,
// and the test suite's log-hygiene case holds every line to that.

namespace Edendale.Windows.Core.Sync;

public static class SyncLog
{
    private static readonly object Gate = new();
    private static readonly List<string> Recent = [];

    /// <summary>How many lines the in-memory tail keeps for Settings.</summary>
    public const int TailLength = 200;

    /// <summary>Receives every line; the test suite captures through it.</summary>
    public static event Action<string>? Sink;

    /// <summary>The last lines, oldest first.</summary>
    public static IReadOnlyList<string> Tail
    {
        get { lock (Gate) return [.. Recent]; }
    }

    public static void Info(string category, string message) => Write("info", category, message);

    public static void Warn(string category, string message) => Write("warn", category, message);

    /// <summary>An exception by class and failure kind only: messages can carry hosts and paths.</summary>
    public static void Error(string category, string context, Exception error)
    {
        var detail = error is ConnectorException connector ? $"{error.GetType().Name}/{connector.Failure}" : error.GetType().Name;
        Write("error", category, $"{context}: {detail}");
    }

    private static void Write(string level, string category, string message)
    {
        var line = $"{DateTimeOffset.UtcNow:yyyy-MM-ddTHH:mm:ss.fffZ} {level} {category}: {message}";
        lock (Gate)
        {
            Recent.Add(line);
            if (Recent.Count > TailLength) Recent.RemoveAt(0);
        }
        Sink?.Invoke(line);
    }

    public static void Clear()
    {
        lock (Gate) Recent.Clear();
    }
}
