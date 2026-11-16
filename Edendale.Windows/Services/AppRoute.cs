// Typed contract for external entry points — the Windows edition of
// AppRouter.swift (Apple Phase 10). The same edendale:// grammar on every
// platform: search (?q=), media/<movie|tv>/<tmdbId>,
// library/<movie|show>/<guid>, play/<movie|episode>/<tmdbId> and
// play/<local-movie|local-episode>/<guid>. A route carries intent only;
// MainWindow.OpenRoute performs navigation/playback through the existing
// NavigationService + PlayerSession, so external links can never bypass
// the shell. edendale://handoff is the pairing QR code (SYNC-PROTOCOL.md
// §9.3), opened from a camera app or a link; it asks which way the logins go
// before anything connects (RootView.swift on apple-27.1).

using Edendale.Windows.Core.Handoff;

namespace Edendale.Windows.Services;

public abstract record AppRoute
{
    public sealed record Search(string Query) : AppRoute;
    public sealed record Media(int TmdbId, string MediaType) : AppRoute;
    public sealed record LocalMovie(Guid Id) : AppRoute;
    public sealed record LocalShow(Guid Id) : AppRoute;
    public sealed record PlayMovie(int TmdbId) : AppRoute;
    public sealed record PlayEpisode(int TmdbId) : AppRoute;
    public sealed record PlayLocalMovie(Guid Id) : AppRoute;
    public sealed record PlayLocalEpisode(Guid Id) : AppRoute;

    /// <summary>A pairing code opened as a link. The code carries the pairing secret, so this never prints it.</summary>
    public sealed record Handoff(HandoffQrCode Code) : AppRoute
    {
        public override string ToString() => nameof(Handoff);
    }

    public const string Scheme = "edendale";
    public const string Host = "edendale.babasama.com";

    public static AppRoute? Parse(Uri uri)
    {
        string head;
        string[] tail;

        if (string.Equals(uri.Scheme, Scheme, StringComparison.OrdinalIgnoreCase))
        {
            head = uri.Host.ToLowerInvariant();
            tail = uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
            // Only the app's own scheme: a web link never starts a pairing.
            if (head == HandoffQrCode.Path)
            {
                return HandoffQrCode.Parse(uri.OriginalString) is { } code ? new Handoff(code) : null;
            }
        }
        else if (string.Equals(uri.Scheme, "https", StringComparison.OrdinalIgnoreCase))
        {
            if (!string.Equals(uri.Host, Host, StringComparison.OrdinalIgnoreCase)) return null;
            var pathSegments = uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (pathSegments.Length == 0) return null;
            head = pathSegments[0].ToLowerInvariant();
            tail = pathSegments.Skip(1).ToArray();
        }
        else
        {
            return null;
        }

        switch (head)
        {
            case "search":
            {
                var query = QueryValue(uri, "q")?.Trim();
                return string.IsNullOrEmpty(query) ? null : new Search(query);
            }

            case "media" when tail.Length == 2
                && tail[0] is "movie" or "tv"
                && int.TryParse(tail[1], out var mediaId):
                return new Media(mediaId, tail[0]);

            case "library" when tail.Length == 2 && Guid.TryParse(tail[1], out var localId):
                return tail[0] switch
                {
                    "movie" => new LocalMovie(localId),
                    "show" => new LocalShow(localId),
                    _ => null,
                };

            case "play" when tail.Length == 2:
                if (int.TryParse(tail[1], out var tmdbId))
                {
                    return tail[0] switch
                    {
                        "movie" => new PlayMovie(tmdbId),
                        "episode" => new PlayEpisode(tmdbId),
                        _ => null,
                    };
                }
                if (Guid.TryParse(tail[1], out var guid))
                {
                    return tail[0] switch
                    {
                        "local-movie" => new PlayLocalMovie(guid),
                        "local-episode" => new PlayLocalEpisode(guid),
                        _ => null,
                    };
                }
                return null;

            default:
                return null;
        }
    }

    private static string? QueryValue(Uri uri, string name)
    {
        foreach (var pair in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var separator = pair.IndexOf('=');
            if (separator < 0) continue;
            if (!string.Equals(Uri.UnescapeDataString(pair[..separator]), name, StringComparison.OrdinalIgnoreCase)) continue;
            return Uri.UnescapeDataString(pair[(separator + 1)..].Replace('+', ' '));
        }
        return null;
    }
}
