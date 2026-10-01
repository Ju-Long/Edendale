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

        ["Watchlist_FallbackMovie"] = "Movie",
        ["Watchlist_FallbackShow"] = "TV Show",

        ["Tmdb_NotConfigured"] = "TMDB credentials are not configured. Run the Edendale.Secrets tool from the repository root.",
        ["Tmdb_InvalidJson"] = "TMDB returned invalid JSON: {0}",
        ["Tmdb_RequestFailed"] = "TMDB request failed (HTTP {0})",

        ["Subtitles_NotConfigured"] = "The subtitle service is not set up for this build.",
        ["Subtitles_Offline"] = "Could not reach the subtitle service.",
        ["Subtitles_RequestFailed"] = "The subtitle service returned an unexpected response.",
        ["Subtitles_RequestFailedCode"] = "Subtitle search failed (HTTP {0}).",
        ["Subtitles_KeyRejected"] = "The subtitle service rejected this build's API key.",
        ["Subtitles_QuotaReached"] = "Today's subtitle request limit is used up. Try again tomorrow.",
        ["Subtitles_NoResults"] = "No subtitles found for this title.",
        ["Subtitles_DownloadFailed"] = "That subtitle could not be downloaded.",

        ["Collection_AllArchives"] = "All Archives",
        ["Collection_FeatureFilms"] = "Feature Films",
        ["Collection_Series"] = "Series",

        ["Credit_Untitled"] = "Untitled",
        ["Credit_Unknown"] = "Unknown",
        ["Credit_DirectedBy"] = "Directed by {0}",
        ["Credit_CreatedBy"] = "Created by {0}",

        ["Track_Mono"] = "Mono",
        ["Track_Stereo"] = "Stereo",
        ["Track_Channels"] = "{0}ch",
        ["Track_Numbered"] = "Track {0}",

        ["Audio_ProfileFlat"] = "Flat",
        ["Audio_ProfileMovies"] = "Movies",
        ["Audio_ProfileMusic"] = "Music",
        ["Audio_ProfileDialogue"] = "Dialogue",
        ["Audio_ProfileNightMode"] = "Night Mode",

        ["Picture_Brightness"] = "Brightness",
        ["Picture_Contrast"] = "Contrast",
        ["Picture_Gamma"] = "Gamma",
        ["Picture_Saturation"] = "Saturation",
        ["Picture_Hue"] = "Hue",

        ["SubtitleFont_System"] = "System",
        ["SubtitleFont_Serif"] = "Serif",
        ["SubtitleFont_Monospaced"] = "Monospaced",
        ["SubtitleColor_Parchment"] = "Parchment",
        ["SubtitleColor_White"] = "White",
        ["SubtitleColor_Yellow"] = "Yellow",
        ["SubtitleColor_Cyan"] = "Cyan",
        ["SubtitleColor_Green"] = "Green",
        ["SubtitleColor_Black"] = "Black",
        ["SubtitleColor_Ink"] = "Ink",
        ["SubtitleColor_Charcoal"] = "Charcoal",
        ["SubtitleColor_Navy"] = "Navy",

        ["Enhancement_PresetOff"] = "Off",
        ["Enhancement_PresetBalanced"] = "Balanced",
        ["Enhancement_PresetHighQuality"] = "High Quality",

        ["SourceKind_Local"] = "Local Folder",
        ["SourceKind_S3"] = "S3-Compatible Storage",
        ["Source_Unavailable"] = "Unavailable",
        ["Account_Guest"] = "Guest",

        ["Connector_InvalidAddress"] = "That server address doesn’t look right. Enter a hostname like nas.local or an IP address.",
        ["Connector_Unreachable"] = "Can’t reach {0}. Check that the server is on, on the same network, and that the name or login is correct.",
        ["Connector_ListingFailed"] = "Couldn’t read the folder {0}. It may need a different login or permissions.",
        ["Connector_SignInRequired"] = "Sign in to {0} again to reach this source.",
        ["Connector_NotConfigured"] = "{0} isn’t set up in this build of Edendale.",
        ["Connector_AccessDenied"] = "{0} denied access to this item.",
        ["Connector_NotFound"] = "This file is no longer in {0}.",
        ["Connector_RateLimited"] = "{0} is limiting requests right now. Try again in a minute.",
        ["Connector_ServerError"] = "{0} returned an error (HTTP {1}).",
        ["Connector_InsecureConnection"] = "Use HTTPS for servers outside your local network. Plain HTTP works only for local addresses such as nas.local or 192.168.1.10.",
        ["Connector_CertificateInvalid"] = "{0} didn’t present a valid HTTPS certificate. Edendale connects only to servers with a trusted certificate.",
        ["Connector_RangeUnsupported"] = "{0} doesn’t support seeking in this file.",
        ["Connector_HostKeyMismatch"] = "The SSH host key for {0} has changed. Edendale won’t connect until you approve the new key by linking the server again.",
        ["Connector_HostKeyUnverified"] = "The SSH host key for {0} hasn’t been approved yet.",
        ["Connector_AuthenticationFailed"] = "{0} didn’t accept the username and password.",
        ["Connector_SecureConnectionFailed"] = "Couldn’t set up a secure connection with {0}. Its SSH server offers only older encryption, which Edendale doesn’t support.",
        ["Connector_PasswordLoginUnavailable"] = "{0} doesn’t accept password logins. Allow password authentication in its SSH settings to link it.",
        ["Connector_SftpUnavailable"] = "{0} doesn’t offer SFTP for this account.",
        ["Connector_BucketInAnotherRegion"] = "This bucket is in the {0} region. Change the region and connect again.",
        ["Connector_BucketInAnotherRegionUnknown"] = "This bucket is in a different region. Check the region and connect again.",
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
