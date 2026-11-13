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
/// request: the library show and episode behind it and the other copies of
/// that show (for Up Next, auto-advance, and the Episodes panel), the
/// skip-prompt segments once they arrive, and which
/// subtitle tracks were attached from outside the file. One per presentation;
/// a reopen at the same position keeps it.
/// </summary>
public sealed class PlayerContext
{
    private PlayerContext(
        PlaybackRequest request,
        LibraryShow? show,
        LibraryEpisode? episode,
        IReadOnlyList<LibraryShow> library,
        Func<Guid, bool>? isUnavailable,
        Func<Guid, LibraryFolder?>? folder)
    {
        Request = request;
        Show = show;
        Episode = episode;
        _library = library;
        _isUnavailable = isUnavailable ?? (_ => false);
        _folder = folder ?? (_ => null);
    }

    /// <summary>The show records the request was resolved against, for <see cref="ShowCopies"/>.</summary>
    private readonly IReadOnlyList<LibraryShow> _library;

    /// <summary>Whether a source is offline or waiting for sign-in, asked live: a scan can change it mid-episode.</summary>
    private readonly Func<Guid, bool> _isUnavailable;

    private readonly Func<Guid, LibraryFolder?> _folder;

    /// <summary>The copies and the key they were gathered under (PlayerSession.showCopiesCache on Apple).</summary>
    private (string Key, IReadOnlyList<LibraryShow> Copies)? _copies;

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

    /// <summary>
    /// Resolves the library records behind <paramref name="request"/>.
    /// <paramref name="isUnavailable"/> and <paramref name="folder"/> let the
    /// next episode stay on the playing source and, when that source lacks
    /// it, take a reachable one (EpisodeProgression); without them every
    /// source counts as reachable and unnamed.
    /// </summary>
    public static PlayerContext Resolve(
        PlaybackRequest request,
        IEnumerable<LibraryShow> shows,
        Func<Guid, bool>? isUnavailable = null,
        Func<Guid, LibraryFolder?>? folder = null)
    {
        var library = shows as IReadOnlyList<LibraryShow> ?? [.. shows];
        if (request.MediaType != "episode") return new PlayerContext(request, null, null, library, isUnavailable, folder);

        foreach (var show in library)
        {
            var episode = show.Episodes.FirstOrDefault(candidate =>
                string.Equals(candidate.FilePath, request.FilePath, StringComparison.OrdinalIgnoreCase));
            if (episode is not null) return new PlayerContext(request, show, episode, library, isUnavailable, folder);
        }
        return new PlayerContext(request, null, null, library, isUnavailable, folder);
    }

    /// <summary>
    /// Every record of the playing show's title (PlaybackSources.CopyKey), the
    /// playing one first: other sources, the same share through another
    /// endpoint, and other names one TMDB match ties together. Gathered once
    /// per presentation, because Up Next asks on every tick, and again only
    /// when a TMDB match during playback changes the playing record's key.
    /// </summary>
    public IReadOnlyList<LibraryShow> ShowCopies
    {
        get
        {
            if (Show is not { } show) return [];
            var key = PlaybackSources.CopyKey(show);
            if (_copies is { } cached && cached.Key == key) return cached.Copies;
            IReadOnlyList<LibraryShow> copies =
            [
                show,
                .. _library.Where(other => other.Id != show.Id && PlaybackSources.CopyKey(other) == key),
            ];
            _copies = (key, copies);
            return copies;
        }
    }

    /// <summary>The stored successor across every copy, for auto-advance and the media keys.</summary>
    public ShowEpisode? Next =>
        Episode is not null ? EpisodeProgression.NextEpisode(Episode, ShowCopies, _isUnavailable, _folder) : null;

    /// <summary>The stored predecessor across every copy, for the Previous media key.</summary>
    public ShowEpisode? Previous =>
        Episode is not null ? EpisodeProgression.PreviousEpisode(Episode, ShowCopies, _isUnavailable, _folder) : null;

    /// <summary>The play request for the stored predecessor, or null.</summary>
    public PlaybackRequest? PreviousRequest =>
        Previous is { } previous ? PlayerSession.RequestFor(previous.Show, previous.Episode) : null;

    /// <summary>
    /// The play request for the stored successor, or null. It names the
    /// record holding the episode, which can be another record of the title.
    /// </summary>
    public PlaybackRequest? NextRequest =>
        Next is { } next ? PlayerSession.RequestFor(next.Show, next.Episode) : null;

    /// <summary>What the Up Next card offers at this point of the episode; see EpisodeProgression.UpcomingEpisode.</summary>
    public ShowEpisode? Upcoming(TimeSpan time, TimeSpan? duration, bool loopEnabled) =>
        Episode is not null
            ? EpisodeProgression.UpcomingEpisode(time, duration, loopEnabled, Episode, ShowCopies, _isUnavailable, _folder)
            : null;

    /// <summary>The Episodes panel's rows, one per episode across every copy; see EpisodeProgression.PlaylistEpisodes.</summary>
    public IReadOnlyList<ShowEpisode> PlaylistEpisodes() =>
        Episode is not null
            ? EpisodeProgression.PlaylistEpisodes(Episode, ShowCopies, _isUnavailable, _folder)
            : [];
}
