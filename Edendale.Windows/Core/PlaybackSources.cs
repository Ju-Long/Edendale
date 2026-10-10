using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>
/// The same movie or show can be imported from several sources, a local
/// folder and an SMB share, say (DIFF.md §3.11, PlaybackSources.swift). The
/// Windows library keeps one record per file, tied to the others by TMDB id.
/// The detail page plays the preferred copy and offers the rest in a Play
/// From menu; these helpers decide the order, the default, and how a copy is
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

    /// <summary>
    /// <paramref name="primary"/> first, then copies in local folders, then
    /// the rest by source name (Explorer order), so the menu reads the same
    /// each time.
    /// </summary>
    public static IReadOnlyList<T> Order<T>(T primary, IEnumerable<T> others, Func<T, LibraryFolder?> folder)
    {
        var sorted = others
            .Select(item => (Item: item, Folder: folder(item)))
            .OrderByDescending(pair => pair.Folder is { IsRemote: false })
            .ThenBy(pair => pair.Folder?.Name ?? "", NaturalStringComparer.Instance)
            .Select(pair => pair.Item);
        return [primary, .. sorted];
    }

    /// <summary>
    /// The copy Play starts: the first whose source isn't known to be offline
    /// or waiting for sign-in, or the first of all when every one is.
    /// </summary>
    public static T? Preferred<T>(IReadOnlyList<T> copies, Func<T, bool> isUnavailable)
    {
        foreach (var copy in copies)
        {
            if (!isUnavailable(copy)) return copy;
        }
        return copies.Count > 0 ? copies[0] : default;
    }

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

    /// <summary>The file name of a stored path or credential-free URL, decoded.</summary>
    public static string FileName(string path) => SourceUrl.FileName(path);

    /// <summary>
    /// The second line of a menu row: the source's kind and the file name,
    /// which usually tells copies apart (2160p, a remux, a release group),
    /// plus "Unavailable" when the source is offline or needs sign-in.
    /// </summary>
    public static string Detail(LibraryFolder? folder, string filePath, bool unavailable = false)
    {
        var kind = (folder?.SourceKind ?? MediaSourceKinds.FromPath(filePath)).DisplayName();
        var detail = $"{kind} · {FileName(filePath)}";
        return unavailable ? $"{detail} · {AppText.Get("Source_Unavailable")}" : detail;
    }
}
