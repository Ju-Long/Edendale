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

    /// <summary>The attached subtitle files in attach order, so a reopen or loop can attach them again.</summary>
    public List<string> ExternalSubtitleUris { get; } = [];

    /// <summary>LibVLC track id → file, for subtitles that came from AddSlave (downloads and side files).</summary>
    public Dictionary<int, string> ExternalSubtitleTracks { get; } = [];

    /// <summary>Attached files whose track LibVLC hasn't reported yet, oldest first.</summary>
    public Queue<string> PendingExternalSubtitles { get; } = new();

    public bool IsExternalSubtitle(int trackId) => ExternalSubtitleTracks.ContainsKey(trackId);

    /// <summary>Records an AddSlave call; its track arrives later through ESAdded.</summary>
    public void ExternalSubtitleAttaching(string uri)
    {
        if (!ExternalSubtitleUris.Contains(uri)) ExternalSubtitleUris.Add(uri);
        PendingExternalSubtitles.Enqueue(uri);
    }

    /// <summary>
    /// A saved subtitle (SavedSubtitleStore) to attach once the input opens,
    /// with the other attached files.
    /// </summary>
    public void AddSavedSubtitle(string uri)
    {
        if (!ExternalSubtitleUris.Contains(uri)) ExternalSubtitleUris.Add(uri);
    }

    /// <summary>The LibVLC track an attached file became, or null until it appears.</summary>
    public int? TrackFor(string uri)
    {
        foreach (var (trackId, file) in ExternalSubtitleTracks)
        {
            if (file == uri) return trackId;
        }
        return null;
    }

    /// <summary>A new subtitle track appeared; true when it is an attached file's.</summary>
    public bool SubtitleTrackAdded(int trackId)
    {
        if (PendingExternalSubtitles.Count == 0) return false;
        ExternalSubtitleTracks[trackId] = PendingExternalSubtitles.Dequeue();
        return true;
    }

    /// <summary>A new LibVLC input starts: track ids are reassigned, the files are kept.</summary>
    public void ResetExternalTracks()
    {
        ExternalSubtitleTracks.Clear();
        PendingExternalSubtitles.Clear();
    }

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

    /// <summary>The stored predecessor, for the Previous media key.</summary>
    public PlaybackRequest? PreviousRequest =>
        Show is not null && Episode is not null && EpisodeProgression.PreviousEpisode(Episode, Show) is { } previous
            ? PlayerSession.RequestFor(Show, previous)
            : null;

    /// <summary>The play request for the stored successor, or null.</summary>
    public PlaybackRequest? NextRequest =>
        Show is not null && NextEpisode is { } next ? PlayerSession.RequestFor(Show, next) : null;
}
