using System.Globalization;
using System.Text;
using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>
/// The same movie or show can be imported from several sources, a local
/// folder and an SMB share, say, or one share linked through two endpoints
/// (its LAN host name and its Tailscale address) (DIFF.md §3.11,
/// PlaybackSources.swift at apple-27.1 94a8b4f). The Windows library keeps
/// one record per movie file and one show record per parsed show name, which
/// can hold episodes from several sources; copies of one title are tied by
/// <see cref="CopyKey(LibraryMovie)"/>: the TMDB match, or the parsed title
/// until there is one. The library shows one card per title; the detail page
/// plays the preferred copy and offers the rest in a Play From menu. These
/// helpers decide the grouping, the order, the default, and how a copy is
/// described.
/// </summary>
public static class PlaybackSources
{
    /// <summary>One episode of a show across every imported copy of the show.</summary>
    public sealed record EpisodeSlot(int Season, int Number, IReadOnlyList<LibraryEpisode> Copies)
    {
        public string Id => $"{Season}-{Number}";

        /// <summary>The page's own copy when it has one; see <see cref="Order{T}"/>.</summary>
        public LibraryEpisode Primary => Copies[0];
    }

    // ------------------------------------------------------------------
    // Grouping copies
    // ------------------------------------------------------------------

    /// <summary>
    /// What ties copies of one movie together: its TMDB match, or before it
    /// has one, its parsed title and year. The same file reached through two
    /// endpoints parses the same, so it is one title before TMDB answers. The
    /// title and year are the parser's, which a match never rewrites.
    /// </summary>
    public static string CopyKey(LibraryMovie movie) =>
        movie.TmdbId is int id
            ? "tmdb-" + id.ToString(CultureInfo.InvariantCulture)
            : $"local-{Normalized(movie.Title)}-{movie.Year?.ToString(CultureInfo.InvariantCulture)}";

    /// <summary>
    /// What ties copies of one show together: its TMDB match, or before it has
    /// one, its parsed name (no year). Two different unmatched shows with one
    /// name stay together until TMDB tells them apart.
    /// </summary>
    public static string CopyKey(LibraryShow show) =>
        show.TmdbId is int id
            ? "tmdb-" + id.ToString(CultureInfo.InvariantCulture)
            : "local-" + Normalized(show.Name);

    /// <summary>The first item of each key, in the given order: one card per title.</summary>
    public static IReadOnlyList<T> Distinct<T>(IEnumerable<T> items, Func<T, string> key)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        return [.. items.Where(item => seen.Add(key(item)))];
    }

    /// <summary>
    /// A title as keys compare it: composed, so a decomposed name from a
    /// macOS share matches its twin, then lowercased and trimmed.
    /// </summary>
    private static string Normalized(string title)
    {
        string composed;
        try
        {
            composed = title.Normalize(NormalizationForm.FormC);
        }
        catch (ArgumentException)
        {
            // An unpaired surrogate can't be normalized; compare it as written.
            composed = title;
        }
        return composed.ToLowerInvariant().Trim();
    }

    // ------------------------------------------------------------------
    // Order and default
    // ------------------------------------------------------------------

    /// <summary>
    /// <paramref name="primary"/> first, then copies in local folders, then
    /// the rest by source name (Explorer order) and then by source path, so
    /// the menu reads the same each time, also for one share linked through
    /// two endpoints under one name.
    /// </summary>
    public static IReadOnlyList<T> Order<T>(T primary, IEnumerable<T> others, Func<T, LibraryFolder?> folder)
    {
        var sorted = others
            .Select(item => (Item: item, Folder: folder(item)))
            .OrderByDescending(pair => pair.Folder is { IsRemote: false })
            .ThenBy(pair => pair.Folder?.Name ?? "", NaturalStringComparer.Instance)
            .ThenBy(pair => pair.Folder?.Path ?? "", NaturalStringComparer.Instance)
            .Select(pair => pair.Item);
        return [primary, .. sorted];
    }

    /// <summary>
    /// The copy Play starts: the reachable copy played most recently (the
    /// source the viewer chose last), else the first whose source isn't known
    /// to be offline or waiting for sign-in, or the first of all when every
    /// one is.
    /// </summary>
    public static T? Preferred<T>(IReadOnlyList<T> copies, Func<T, bool> isUnavailable, Func<T, DateTimeOffset?> lastPlayed)
    {
        var reachable = copies.Where(copy => !isUnavailable(copy)).ToList();
        if (MostRecentlyPlayedIndex(reachable, lastPlayed) is int index) return reachable[index];
        if (reachable.Count > 0) return reachable[0];
        return copies.Count > 0 ? copies[0] : default;
    }

    /// <summary>The first reachable copy, or the first of all: <see cref="Preferred{T}(IReadOnlyList{T}, Func{T, bool}, Func{T, DateTimeOffset?})"/> with nothing played.</summary>
    public static T? Preferred<T>(IReadOnlyList<T> copies, Func<T, bool> isUnavailable) =>
        Preferred(copies, isUnavailable, _ => null);

    /// <summary>The copy played most recently, the first of them on a tie; default when none has been played.</summary>
    public static T? MostRecentlyPlayed<T>(IReadOnlyList<T> copies, Func<T, DateTimeOffset?> lastPlayed) =>
        MostRecentlyPlayedIndex(copies, lastPlayed) is int index ? copies[index] : default;

    /// <summary>
    /// <paramref name="copies"/> with the one played most recently moved to
    /// the front, as Play From lists them; the rest keep their order.
    /// </summary>
    public static IReadOnlyList<T> LastPlayedFirst<T>(IReadOnlyList<T> copies, Func<T, DateTimeOffset?> lastPlayed)
    {
        var reordered = copies.ToList();
        if (MostRecentlyPlayedIndex(copies, lastPlayed) is int index && index > 0)
        {
            var led = reordered[index];
            reordered.RemoveAt(index);
            reordered.Insert(0, led);
        }
        return reordered;
    }

    private static int? MostRecentlyPlayedIndex<T>(IReadOnlyList<T> copies, Func<T, DateTimeOffset?> lastPlayed)
    {
        int? best = null;
        DateTimeOffset bestAt = default;
        for (var index = 0; index < copies.Count; index++)
        {
            if (lastPlayed(copies[index]) is not { } at) continue;
            if (best is null || at > bestAt)
            {
                best = index;
                bestAt = at;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Shows: sources and episodes
    // ------------------------------------------------------------------

    /// <summary>
    /// Every episode of <paramref name="primary"/> and of <paramref name="others"/>
    /// (other records of the same show), one slot per season and episode
    /// number, in airing order. Within a slot the page's own show comes
    /// first, and copies in one show follow <see cref="Order{T}"/>.
    /// </summary>
    public static IReadOnlyList<EpisodeSlot> EpisodeSlots(
        LibraryShow primary,
        IEnumerable<LibraryShow> others,
        Func<Guid, LibraryFolder?> folder)
    {
        var shows = new List<LibraryShow> { primary };
        shows.AddRange(others.Where(show => show.Id != primary.Id));

        var slots = new Dictionary<(int Season, int Number), List<LibraryEpisode>>();
        foreach (var show in shows)
        {
            var episodes = show.Episodes
                .Select(episode => (Episode: episode, Folder: folder(episode.FolderId)))
                .OrderBy(pair => pair.Episode.Season)
                .ThenBy(pair => pair.Episode.Episode)
                .ThenByDescending(pair => pair.Folder is { IsRemote: false })
                .ThenBy(pair => pair.Folder?.Name ?? "", NaturalStringComparer.Instance)
                .ThenBy(pair => pair.Episode.FilePath, StringComparer.Ordinal)
                .Select(pair => pair.Episode);
            foreach (var episode in episodes)
            {
                var key = (episode.Season, episode.Episode);
                if (!slots.TryGetValue(key, out var copies)) slots[key] = copies = [];
                copies.Add(episode);
            }
        }

        return slots
            .OrderBy(pair => pair.Key.Season)
            .ThenBy(pair => pair.Key.Number)
            .Select(pair => new EpisodeSlot(pair.Key.Season, pair.Key.Number, pair.Value))
            .ToList();
    }

    /// <summary>
    /// Seasons and episodes across every copy of a show, one per season and
    /// episode number: an episode held by two sources, or twice by one
    /// record, counts once.
    /// </summary>
    public static (int Seasons, int Episodes) EpisodeCounts(IEnumerable<LibraryShow> copies)
    {
        var slots = copies
            .SelectMany(show => show.Episodes)
            .Select(episode => (episode.Season, episode.Episode))
            .ToHashSet();
        return (slots.Select(slot => slot.Season).Distinct().Count(), slots.Count);
    }

    /// <summary>A show card's subtitle, "2 seasons · 14 episodes", counted by <see cref="EpisodeCounts"/>.</summary>
    public static string ShowSubtitle(IEnumerable<LibraryShow> copies)
    {
        var (seasons, episodes) = EpisodeCounts(copies);
        return $"{AppText.Plural("Plural_SeasonOne", "Plural_SeasonOther", seasons)} · {AppText.Plural("Plural_EpisodeOne", "Plural_EpisodeOther", episodes)}";
    }

    /// <summary>
    /// The sources (folder ids) holding an episode of <paramref name="copies"/>,
    /// the records of one show title: local folders first, then by name, then
    /// by path, as <see cref="Order{T}"/> lists copies. On Windows a source is
    /// what Apple calls a copy of the show, since one record spans sources.
    /// </summary>
    public static IReadOnlyList<Guid> ShowSources(IEnumerable<LibraryShow> copies, Func<Guid, LibraryFolder?> folder) =>
    [
        .. copies
            .SelectMany(show => show.Episodes)
            .Select(episode => episode.FolderId)
            .Distinct()
            .Select(id => (Id: id, Folder: folder(id)))
            .OrderByDescending(pair => pair.Folder is { IsRemote: false })
            .ThenBy(pair => pair.Folder?.Name ?? "", NaturalStringComparer.Instance)
            .ThenBy(pair => pair.Folder?.Path ?? "", NaturalStringComparer.Instance)
            .Select(pair => pair.Id),
    ];

    /// <summary>
    /// When an episode of the show last started playing from <paramref name="folderId"/>,
    /// the latest across every record of the title (Apple's TVShow.lastPlayedAt,
    /// kept per source); null when it never has.
    /// </summary>
    public static DateTimeOffset? SourceLastPlayedAt(IEnumerable<LibraryShow> copies, Guid folderId)
    {
        DateTimeOffset? latest = null;
        foreach (var show in copies)
        {
            if (show.LastPlayedAtBySource is { } stamps
                && stamps.TryGetValue(folderId, out var at)
                && (latest is null || at > latest))
            {
                latest = at;
            }
        }
        return latest;
    }

    /// <summary>
    /// The order to try a show's sources in when nothing is playing to stay
    /// on (Continue Watching and play routes): the reachable source played
    /// last, then the other reachable ones, then those offline or waiting for
    /// sign-in, each in <see cref="ShowSources"/> order.
    /// </summary>
    public static IReadOnlyList<Guid> SourcePreference(
        IReadOnlyList<LibraryShow> copies,
        Func<Guid, LibraryFolder?> folder,
        Func<Guid, bool> isUnavailable)
    {
        var sources = ShowSources(copies, folder);
        var reachable = sources.Where(id => !isUnavailable(id)).ToList();
        return
        [
            .. LastPlayedFirst(reachable, id => SourceLastPlayedAt(copies, id)),
            .. sources.Where(isUnavailable),
        ];
    }

    /// <summary>
    /// Of the files of one episode, the one in the source that comes first in
    /// <paramref name="sourcePreference"/>. A source missing from it comes
    /// last, and two files in one source go by path.
    /// </summary>
    public static ShowEpisode? PreferredEpisode(IEnumerable<ShowEpisode> candidates, IReadOnlyList<Guid> sourcePreference) =>
        candidates
            .OrderBy(candidate => Rank(sourcePreference, candidate.Episode.FolderId))
            .ThenBy(candidate => candidate.Episode.FilePath, StringComparer.Ordinal)
            .FirstOrDefault();

    /// <summary>Where a source stands in a preference list; one not in it sorts after every listed one.</summary>
    internal static int Rank(IReadOnlyList<Guid> sourcePreference, Guid folderId)
    {
        for (var index = 0; index < sourcePreference.Count; index++)
        {
            if (sourcePreference[index] == folderId) return index;
        }
        return int.MaxValue;
    }

    // ------------------------------------------------------------------
    // Describing a copy
    // ------------------------------------------------------------------

    /// <summary>The file name of a stored path or credential-free URL, decoded.</summary>
    public static string FileName(string path) => SourceUrl.FileName(path);

    /// <summary>
    /// Where a copy is reached from: the server's host, with a port only when
    /// the stored URL gives one (<c>longs-mac-studio.local</c>, <c>100.64.0.7</c>,
    /// <c>nas:2222</c>), or for accounts and buckets the account (an S3
    /// source's is its bucket), never the account-key hash their URLs use as
    /// a host. An SMB copy names the server of its UNC path. Null for local
    /// files and for an account linked without a name.
    /// </summary>
    public static string? Endpoint(LibraryFolder? folder, string filePath)
    {
        var kind = folder?.SourceKind ?? MediaSourceKinds.FromPath(filePath);
        if (!kind.IsRemote()) return null;
        if (kind.IsCloudAccount() || kind == MediaSourceKind.S3)
        {
            return folder?.Username is { Length: > 0 } account ? account : null;
        }

        if (SourceUrl.TrySplit(filePath, out var parts) || (folder is not null && SourceUrl.TrySplit(folder.Path, out parts)))
        {
            if (parts.Host.Length == 0) return null;
            // A bare IPv6 address reads as one only in brackets.
            var host = parts.Host.Contains(':') ? $"[{parts.Host}]" : parts.Host;
            return parts.Port is int port ? $"{host}:{port.ToString(CultureInfo.InvariantCulture)}" : host;
        }
        return UncServer(filePath) ?? (folder is null ? null : UncServer(folder.Path));
    }

    /// <summary>The server of a UNC path as it was typed, or null for any other path.</summary>
    private static string? UncServer(string path)
    {
        if (!path.StartsWith(@"\\", StringComparison.Ordinal)) return null;
        var server = path[2..].Split('\\', '/')[0];
        return server.Length > 0 ? server : null;
    }

    /// <summary>
    /// The first line of a menu row: the source's name and its
    /// <see cref="Endpoint"/>, "Media (longs-mac-studio.local)", so one share
    /// linked through two endpoints reads as two choices. Not localized, as on
    /// Apple; a local copy is just the folder's name.
    /// </summary>
    public static string Title(LibraryFolder? folder, string filePath)
    {
        var name = folder?.Name is { Length: > 0 } folderName ? folderName : FileName(filePath);
        return Endpoint(folder, filePath) is { } endpoint ? $"{name} ({endpoint})" : name;
    }

    /// <summary>
    /// The second line of a menu row: the source's kind and the file name,
    /// which usually tells copies apart (2160p, a remux, a release group),
    /// then "Last Played" on the copy whose source was played last, and
    /// "Unavailable" when the source is offline or needs sign-in.
    /// </summary>
    public static string Detail(LibraryFolder? folder, string filePath, bool unavailable = false, bool isLastPlayed = false)
    {
        var kind = (folder?.SourceKind ?? MediaSourceKinds.FromPath(filePath)).DisplayName();
        var notes = new List<string> { $"{kind} · {FileName(filePath)}" };
        if (isLastPlayed) notes.Add(AppText.Get("Source_LastPlayed"));
        if (unavailable) notes.Add(AppText.Get("Source_Unavailable"));
        return string.Join(" · ", notes);
    }
}
