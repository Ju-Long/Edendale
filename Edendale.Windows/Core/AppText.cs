using System.Globalization;

namespace Edendale.Windows.Core;

/// <summary>
/// Localization seam for the code <c>Edendale.Windows.Tests</c> compiles
/// directly (Core, Models, and the data services listed in its csproj).
///
/// Those files cannot call <c>Loc</c>: it is built on MRT Core, which the test
/// library neither references nor has a resource map for. So the app installs
/// <see cref="Resolver"/> at startup and everything routes through here;
/// anywhere without the resource system — the test host above all — falls
/// through to the English defaults below, which keeps those tests hermetic and
/// their assertions unchanged.
///
/// Keep <see cref="Fallback"/> in step with the matching entries in
/// Strings/en-US/Resources.resw.
/// </summary>
internal static class AppText
{
    /// <summary>Set once at startup to <c>Loc.Get</c>; null anywhere without MRT.</summary>
    public static Func<string, string>? Resolver;

    private static readonly Dictionary<string, string> Fallback = new(StringComparer.Ordinal)
    {
        ["Month_1"] = "Jan", ["Month_2"] = "Feb", ["Month_3"] = "Mar",
        ["Month_4"] = "Apr", ["Month_5"] = "May", ["Month_6"] = "Jun",
        ["Month_7"] = "Jul", ["Month_8"] = "Aug", ["Month_9"] = "Sep",
        ["Month_10"] = "Oct", ["Month_11"] = "Nov", ["Month_12"] = "Dec",

        ["Plural_DayOne"] = "{0} day",
        ["Plural_DayOther"] = "{0} days",

        ["Season_Number"] = "Season {0}",
        ["Season_Label"] = "Season",

        ["Library_AwaitingMetadata"] = "Awaiting metadata",

        ["Tmdb_NotConfigured"] = "TMDB credentials are not configured. Run init.ps1 from the repository root.",
        ["Tmdb_InvalidJson"] = "TMDB returned invalid JSON: {0}",
        ["Tmdb_RequestFailed"] = "TMDB request failed (HTTP {0})",

        ["Collection_AllArchives"] = "All Archives",
        ["Collection_FeatureFilms"] = "Feature Films",
        ["Collection_Series"] = "Series",

        ["Credit_Untitled"] = "Untitled",
        ["Credit_Unknown"] = "Unknown",
        ["Credit_DirectedBy"] = "Directed by {0}",
        ["Credit_CreatedBy"] = "Created by {0}",
    };

    /// <summary>The localized string for <paramref name="key"/>, or the English default.</summary>
    public static string Get(string key)
    {
        // Loc returns the key itself when a resource is missing, so treat that
        // as "not localized" and fall through rather than showing the key.
        var resolved = Resolver?.Invoke(key);
        if (!string.IsNullOrEmpty(resolved) && resolved != key)
        {
            return resolved;
        }

        return Fallback.TryGetValue(key, out var fallback) ? fallback : key;
    }

    public static string Format(string key, params object?[] args) =>
        string.Format(CultureInfo.CurrentCulture, Get(key), args);

    /// <summary>Singular or plural copy — English rules, matching the Android plurals.</summary>
    public static string Plural(string singularKey, string pluralKey, int count) =>
        Format(count == 1 ? singularKey : pluralKey, count);
}
