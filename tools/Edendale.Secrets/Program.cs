// Writes the gitignored root secrets.json that a local build embeds.
//
//   dotnet run --project tools/Edendale.Secrets              prompt for each key
//   dotnet run --project tools/Edendale.Secrets -- --show    report what is set
//   dotnet run --project tools/Edendale.Secrets -- --wyzie-key -
//                                                           read one value from stdin
//
// Values are never accepted as command-line arguments: an argument is visible
// in shell history and to anything that can list processes. A flag takes only
// "-", meaning "read this one from a line of stdin".
//
// Existing values are kept when the prompt is answered with Enter, so adding
// one key does not mean re-entering the others.

using System.Text.RegularExpressions;

namespace Edendale.Secrets;

internal static class Program
{
    /// <summary>One credential: how it is named, validated, and described.</summary>
    private sealed record SecretDefinition(
        string Name,
        string Flag,
        string Label,
        string Pattern,
        string Guidance,
        bool Required,
        string Source);

    private static readonly SecretDefinition[] Definitions =
    [
        new(
            Name: "TMDB_READ_ACCESS_TOKEN",
            Flag: "--tmdb-token",
            Label: "TMDB read access token",
            Pattern: @"\A[A-Za-z0-9._~-]+\z",
            Guidance: "Use the API Read Access Token exactly as TMDB shows it, without a Bearer prefix.",
            Required: true,
            Source: "https://www.themoviedb.org/settings/api"),
        new(
            Name: "TMDB_API_KEY",
            Flag: "--tmdb-key",
            Label: "TMDB API key",
            Pattern: @"\A[A-Fa-f0-9]{32}\z",
            Guidance: "The TMDB v3 API key is exactly 32 hexadecimal characters.",
            Required: true,
            Source: "https://www.themoviedb.org/settings/api"),
        new(
            Name: "WYZIE_API_KEY",
            Flag: "--wyzie-key",
            Label: "Wyzie Subs API key",
            Pattern: @"\A[A-Za-z0-9._-]+\z",
            Guidance: "The Wyzie key is letters, digits, dots, dashes, and underscores.",
            Required: false,
            Source: "https://store.wyzie.io/redeem"),
    ];

    private static int Main(string[] arguments)
    {
        try
        {
            return Run(arguments);
        }
        catch (OperationCanceledException)
        {
            Console.Error.WriteLine();
            Console.Error.WriteLine("Cancelled. No files changed.");
            return 1;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine($"Error: {error.Message}");
            return 1;
        }
    }

    private static int Run(string[] arguments)
    {
        if (arguments.Any(argument => argument is "-h" or "--help" or "/?"))
        {
            PrintUsage();
            return 0;
        }

        var root = RepositoryRoot();
        var path = Path.Combine(root, "secrets.json");
        var values = SecretsFile.Read(path);

        if (arguments.Contains("--show"))
        {
            PrintStatus(path, values);
            return 0;
        }

        var fromStdin = ParseStdinFlags(arguments);
        var updated = new Dictionary<string, string>(values, StringComparer.Ordinal);

        if (fromStdin.Count > 0)
        {
            // Walk the flags as given, not as declared: each one consumes the
            // matching line, so the order the caller wrote is the order the
            // values arrive in.
            foreach (var flag in fromStdin)
            {
                var definition = Definitions.Single(d => d.Flag == flag);
                var value = (Console.ReadLine() ?? "").Trim();
                Apply(updated, definition, value);
            }
        }
        else
        {
            if (Console.IsInputRedirected)
            {
                Console.Error.WriteLine(
                    "Input is redirected but no value flag was given. See --help.");
                return 1;
            }
            PromptForAll(updated, values);
        }

        foreach (var definition in Definitions.Where(d => d.Required))
        {
            if (!updated.TryGetValue(definition.Name, out var value) || value.Length == 0)
            {
                Console.Error.WriteLine($"Error: {definition.Label} is required.");
                return 1;
            }
        }

        SecretsFile.Write(path, updated);

        Console.WriteLine();
        Console.WriteLine($"Wrote {path} (owner-only)");
        PrintStatus(null, updated);
        Console.WriteLine();
        Console.WriteLine("Rebuild the app to embed the credentials; no runtime key entry is required.");
        return 0;
    }

    // ------------------------------------------------------------------
    // Interactive
    // ------------------------------------------------------------------

    private static void PromptForAll(
        Dictionary<string, string> updated, IReadOnlyDictionary<string, string> existing)
    {
        Console.WriteLine("Edendale local credentials. Input is hidden and secrets.json is gitignored.");
        Console.WriteLine("Press Enter to keep a value that is already set, or type - to clear it.");
        Console.WriteLine();

        foreach (var definition in Definitions)
        {
            var current = existing.GetValueOrDefault(definition.Name, "");
            var optional = definition.Required ? "" : ", optional";
            var state = current.Length > 0 ? "keep existing" : $"not set{optional}";

            Console.WriteLine($"{definition.Label}  ({definition.Source})");

            while (true)
            {
                var typed = ReadHidden($"  [{state}]: ");

                if (typed.Length == 0)
                {
                    // Enter keeps whatever is there, including "nothing" for an
                    // optional key.
                    if (current.Length > 0 || !definition.Required) break;
                    Console.Error.WriteLine("  A value is required.");
                    continue;
                }

                if (typed == "-")
                {
                    if (definition.Required)
                    {
                        Console.Error.WriteLine("  A value is required.");
                        continue;
                    }
                    updated.Remove(definition.Name);
                    Console.WriteLine("  Cleared.");
                    break;
                }

                if (!Regex.IsMatch(typed, definition.Pattern))
                {
                    Console.Error.WriteLine($"  {definition.Guidance}");
                    continue;
                }

                updated[definition.Name] = typed;
                break;
            }

            Console.WriteLine();
        }
    }

    /// <summary>
    /// Reads a line without echoing it. The keystroke buffer is cleared
    /// afterwards; the returned string cannot be scrubbed, because .NET
    /// strings are immutable — the file ACL and gitignore are what actually
    /// protect the value.
    /// </summary>
    private static string ReadHidden(string prompt)
    {
        Console.Write(prompt);

        var buffer = new List<char>();
        while (true)
        {
            var key = Console.ReadKey(intercept: true);

            if (key.Key == ConsoleKey.Enter)
            {
                Console.WriteLine();
                break;
            }
            if (key.Key == ConsoleKey.Escape) throw new OperationCanceledException();
            if (key.Key == ConsoleKey.Backspace)
            {
                if (buffer.Count > 0)
                {
                    buffer.RemoveAt(buffer.Count - 1);
                    Console.Write("\b \b");
                }
                continue;
            }
            if (char.IsControl(key.KeyChar)) continue;

            buffer.Add(key.KeyChar);
            Console.Write('*');
        }

        var value = new string([.. buffer]).Trim();
        buffer.Clear();
        return value;
    }

    // ------------------------------------------------------------------
    // Non-interactive
    // ------------------------------------------------------------------

    /// <summary>
    /// Collects the flags given as <c>--flag -</c>, preserving their order so
    /// each consumes the matching line of stdin. Anything else is rejected,
    /// which is what keeps a secret off the command line.
    /// </summary>
    private static List<string> ParseStdinFlags(string[] arguments)
    {
        var flags = new List<string>();

        for (var index = 0; index < arguments.Length; index++)
        {
            var argument = arguments[index];
            if (argument is "--show") continue;

            var definition = Definitions.FirstOrDefault(d => d.Flag == argument)
                ?? throw new ArgumentException($"Unknown option \"{argument}\". See --help.");

            if (index + 1 >= arguments.Length || arguments[index + 1] != "-")
            {
                throw new ArgumentException(
                    $"{definition.Flag} takes only \"-\", meaning read the value from stdin. " +
                    "Passing a secret as an argument would leave it in shell history.");
            }

            flags.Add(definition.Flag);
            index++;
        }

        return flags;
    }

    private static void Apply(
        Dictionary<string, string> updated, SecretDefinition definition, string value)
    {
        if (value.Length == 0 || value == "-")
        {
            if (definition.Required)
            {
                throw new ArgumentException($"{definition.Label} is required.");
            }
            updated.Remove(definition.Name);
            return;
        }

        if (!Regex.IsMatch(value, definition.Pattern))
        {
            throw new ArgumentException($"{definition.Label}: {definition.Guidance}");
        }

        updated[definition.Name] = value;
    }

    // ------------------------------------------------------------------
    // Reporting — length only, never any part of a value
    // ------------------------------------------------------------------

    private static void PrintStatus(string? path, IReadOnlyDictionary<string, string> values)
    {
        if (path is not null)
        {
            Console.WriteLine(File.Exists(path) ? path : $"{path} (not created yet)");
        }

        foreach (var definition in Definitions)
        {
            var value = values.GetValueOrDefault(definition.Name, "");
            var state = value.Length > 0
                ? $"set ({value.Length} characters)"
                : definition.Required ? "not set" : "not set (online subtitles disabled)";
            Console.WriteLine($"  {definition.Name,-24} {state}");
        }
    }

    private static void PrintUsage()
    {
        Console.WriteLine("""
            Writes the gitignored root secrets.json that a local build embeds.

              dotnet run --project tools/Edendale.Secrets
                  Prompt for each credential. Enter keeps an existing value,
                  "-" clears an optional one.

              dotnet run --project tools/Edendale.Secrets -- --show
                  Report which credentials are set. Never prints a value.

              dotnet run --project tools/Edendale.Secrets -- --tmdb-token - --wyzie-key -
                  Read those values from stdin, one line each, in the order the
                  flags appear. Flags take only "-": a secret passed as an
                  argument would end up in shell history.

            Environment variables of the same name override the file at runtime.
            """);
    }

    /// <summary>
    /// The directory holding Edendale.Windows.sln, found by walking up from
    /// the build output so the tool works from any working directory.
    /// </summary>
    private static string RepositoryRoot()
    {
        foreach (var start in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            for (var directory = new DirectoryInfo(start); directory is not null; directory = directory.Parent)
            {
                if (File.Exists(Path.Combine(directory.FullName, "Edendale.Windows.sln")))
                {
                    return directory.FullName;
                }
            }
        }

        throw new InvalidOperationException(
            "Could not find Edendale.Windows.sln. Run this from inside the repository.");
    }
}
