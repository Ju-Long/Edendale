using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

/// <summary>A skippable range reported by TheIntroDB (DIFF.md §3.5).</summary>
public enum MediaSegmentKind
{
    Intro,
    Recap,
    Credits,
}

/// <summary>
/// One intro, recap, or credits range in milliseconds. A credits range with
/// <see cref="ReachesEnd"/> runs to the end of the file, so skipping it
/// finishes the item rather than seeking.
/// </summary>
public sealed record MediaSegment(MediaSegmentKind Kind, long StartMilliseconds, long EndMilliseconds, bool ReachesEnd = false)
{
    public bool Contains(long timeMilliseconds) =>
        timeMilliseconds >= StartMilliseconds && timeMilliseconds < EndMilliseconds;
}

/// <summary>
/// What the player knows about the item it is presenting, beyond the
/// request: the library show and episode behind it (for Up Next and
/// auto-advance), the skip-prompt segments once they arrive, and which
/// subtitle tracks were attached from outside the file. One per presentation;
/// a reopen at the same position keeps it.
/// </summary>
public sealed class PlayerContext
{
    private PlayerContext(PlaybackRequest request, LibraryShow? show, LibraryEpisode? episode)
    {
        Request = request;
        Show = show;
        Episode = episode;
    }

    public PlaybackRequest Request { get; }

    /// <summary>The library show for an episode; null for movies and loose files.</summary>
    public LibraryShow? Show { get; }

    public LibraryEpisode? Episode { get; }

    /// <summary>Per-title memory key (TitlePlaybackMemory), or null for unidentified files.</summary>
    public string? ContentKey =>
        TitlePlaybackMemory.ContentKey(Request.MediaType, Request.TmdbId, Request.ShowTmdbId);

    /// <summary>Skip-prompt ranges; empty until (and unless) the lookup answers.</summary>
    public IReadOnlyList<MediaSegment> Segments { get; set; } = [];

    /// <summary>Subtitle track ids that came from AddSlave (downloads and side files).</summary>
    public HashSet<int> ExternalSubtitleIds { get; } = [];

    /// <summary>The attached subtitle files, so a reopen can attach them again.</summary>
    public List<string> ExternalSubtitleUris { get; } = [];

    /// <summary>AddSlave calls whose new track hasn't been reported yet.</summary>
    public int PendingExternalSubtitles { get; set; }

    /// <summary>Resolves the library records behind <paramref name="request"/>.</summary>
    public static PlayerContext Resolve(PlaybackRequest request, IEnumerable<LibraryShow> shows)
    {
        if (request.MediaType != "episode") return new PlayerContext(request, null, null);

        foreach (var show in shows)
        {
            var episode = show.Episodes.FirstOrDefault(candidate =>
                string.Equals(candidate.FilePath, request.FilePath, StringComparison.OrdinalIgnoreCase));
            if (episode is not null) return new PlayerContext(request, show, episode);
        }
        return new PlayerContext(request, null, null);
    }

    /// <summary>The stored successor, for auto-advance and the Up Next card.</summary>
    public LibraryEpisode? NextEpisode =>
        Show is not null && Episode is not null ? EpisodeProgression.NextEpisode(Episode, Show) : null;

    /// <summary>The play request for the stored successor, or null.</summary>
    public PlaybackRequest? NextRequest =>
        Show is not null && NextEpisode is { } next ? PlayerSession.RequestFor(Show, next) : null;
}
