#if DEBUG
using Microsoft.Extensions.Configuration;

namespace Edendale.Windows.Services;

/// <summary>
/// Loads Visual Studio/.NET User Secrets into this Debug process before any
/// service singleton reads its credentials. Nothing is persisted or logged.
/// </summary>
internal static class DevelopmentSecrets
{
    private static readonly string[] Keys =
    [
        "TMDB_READ_ACCESS_TOKEN",
        "TMDB_API_KEY",
        "WYZIE_API_KEY",
    ];

    public static void ApplyToEnvironment()
    {
        var configuration = new ConfigurationBuilder()
            .AddUserSecrets(typeof(DevelopmentSecrets).Assembly, optional: true)
            .Build();

        foreach (var key in Keys)
        {
            // A deliberately supplied process/user/machine environment value
            // remains the highest-priority local override.
            if (!string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable(key))) continue;

            var value = configuration[key]?.Trim();
            if (!string.IsNullOrWhiteSpace(value))
            {
                Environment.SetEnvironmentVariable(key, value, EnvironmentVariableTarget.Process);
            }
        }
    }
}
#endif
