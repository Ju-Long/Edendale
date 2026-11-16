// The library's TMDB sweep (apple-27.1 94a8b4f, LibraryController
// enrichPendingMetadata, sweepMetadata, enrichMovie, enrichShow,
// enrichEpisode, and lookup). It looks up each movie and show without a TMDB
// match and each episode without one whose show is matched, on a task of its
// own, so leaving the page that asked doesn't cancel it. Only already-parsed
// titles and numbers reach TMDB: classification stays local and comes first.
// WinUI-free, so the tests drive it with lookups of their own.

using Edendale.Windows.Core;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

/// <summary>What TMDB matched a movie file to.</summary>
public sealed record MovieMatch(
    int TmdbId,
    string? PosterUrl,
    string? BackdropUrl,
    string? Overview,
    int? Year,
    int? RuntimeMinutes);

/// <summary>What TMDB matched a show's name to.</summary>
public sealed record ShowMatch(
    int TmdbId,
    string? PosterUrl,
    string? BackdropUrl,
    string? Overview,
    int? FirstAirYear);

/// <summary>TMDB's episode at a matched show's season and episode numbers.</summary>
public sealed record EpisodeMatch(int TmdbId, string? Title, string? StillUrl, int? RuntimeMinutes);

/// <summary>
/// The TMDB lookups library enrichment makes (Apple MetadataLookup); tests
/// answer them without the network. Each returns null when TMDB answered
/// without a usable match (no result, a 404, an answer that isn't JSON) and
/// throws when the request failed; <see cref="LibraryEnrichment.HaltsEnrichment"/>
/// tells the two kinds of failure apart.
/// </summary>
public sealed class MetadataLookup
{
    /// <summary>A movie by its parsed title and year.</summary>
    public required Func<string, int?, Task<MovieMatch?>> SearchMovie { get; init; }

    /// <summary>A show by the name its files use.</summary>
    public required Func<string, Task<ShowMatch?>> SearchShow { get; init; }

    /// <summary>An episode by its show's TMDB id and its season and episode numbers.</summary>
    public required Func<int, int, int, Task<EpisodeMatch?>> FetchEpisode { get; init; }

    /// <summary>TMDB, through this build's credential.</summary>
    public static MetadataLookup Tmdb { get; } = FromTmdb(
        WindowsCore.SearchMediaAsync,
        WindowsCore.LoadMediaDetailAsync,
        WindowsCore.LoadEpisodeDetailAsync);

    /// <summary>
    /// The library's matching over TMDB's search, detail, and episode
    /// requests. A movie is the /search/multi movie result from the parsed
    /// year when there is one, otherwise the first, with its runtime and
    /// overview from its details; details that fail without ending the sweep
    /// leave the match without a runtime. A show is the first TV result. An
    /// episode is TMDB's at the show's season and episode numbers.
    /// </summary>
    public static MetadataLookup FromTmdb(
        Func<string, Task<List<MediaItem>>> search,
        Func<int, string, Task<MediaDetail>> detail,
        Func<int, int, int, Task<EpisodeDetail>> episode) => new()
        {
            SearchMovie = async (title, year) =>
            {
                var results = await search(title).ConfigureAwait(false);
                var match = results
                    .Where(item => item.MediaType == "movie" && item.Id > 0)
                    .OrderByDescending(item => year is int parsed && item.Year == parsed)
                    .FirstOrDefault();
                if (match is null) return null;

                MediaDetail? details = null;
                try
                {
                    details = await detail(match.Id, "movie").ConfigureAwait(false);
                }
                catch (Exception error) when (!LibraryEnrichment.HaltsEnrichment(error))
                {
                    // The match stands without a runtime. A failure that ends
                    // the sweep throws instead, so the next one asks again.
                }
                return new MovieMatch(
                    match.Id,
                    match.PosterUrl,
                    match.BackdropUrl,
                    details?.Overview ?? match.Overview,
                    match.Year,
                    details?.RuntimeMinutes);
            },
            SearchShow = async name =>
            {
                var results = await search(name).ConfigureAwait(false);
                var match = results.FirstOrDefault(item => item.MediaType == "tv" && item.Id > 0);
                return match is null
                    ? null
                    : new ShowMatch(match.Id, match.PosterUrl, match.BackdropUrl, match.Overview, match.Year);
            },
            FetchEpisode = async (showId, season, number) =>
            {
                var found = await episode(showId, season, number).ConfigureAwait(false);
                return found.Id <= 0
                    ? null
                    : new EpisodeMatch(found.Id, found.Name, found.StillUrl, found.RuntimeMinutes);
            },
        };
}

/// <summary>
/// Matches every title in the library that still lacks TMDB metadata. A
/// title is indexed once it has a TMDB id. One TMDB answered without a match
/// for carries <c>MetadataCheckedAt</c> and waits
/// <see cref="LibraryEnrichment.UnmatchedRetryInterval"/>, or a forced
/// rescan, before it is asked about again. A lookup that fails because TMDB
/// can't be reached, refuses the credential, or rate-limits marks nothing and
/// ends the pass; the next sweep (each launch, import, rescan, and Downloaded
/// visit) resumes there. Requests made while a sweep runs queue one more pass
/// (<see cref="CoalescingRunner"/>).
/// </summary>
public sealed class MetadataSweep
{
    /// <summary>
    /// Matches and marks reach library.json after this many titles and at the
    /// end of each pass, so quitting mid-sweep keeps most of them without a
    /// rewrite of the whole file per title.
    /// </summary>
    public const int PersistEvery = 10;

    private readonly LibraryData _library;
    private readonly object _gate;
    private readonly MetadataLookup _lookup;
    private readonly Func<DateTimeOffset> _now;
    private readonly Action _persist;
    private readonly CoalescingRunner _runner;

    /// <param name="library">The library's records, read and written only under <paramref name="gate"/>.</param>
    /// <param name="gate">The library's lock.</param>
    /// <param name="lookup">TMDB, or a test's answers.</param>
    /// <param name="now">The clock unmatched titles are marked and retried by.</param>
    /// <param name="persist">Saves the library; called outside <paramref name="gate"/>.</param>
    public MetadataSweep(LibraryData library, object gate, MetadataLookup lookup, Func<DateTimeOffset> now, Action persist)
    {
        _library = library;
        _gate = gate;
        _lookup = lookup;
        _now = now;
        _persist = persist;
        _runner = new CoalescingRunner(SweepAsync, retrying => HasDue(_now(), retrying));
    }

    /// <summary>A sweep started or ended; read <see cref="IsRunning"/>. Raised on any thread.</summary>
    public event EventHandler? RunningChanged
    {
        add => _runner.RunningChanged += value;
        remove => _runner.RunningChanged -= value;
    }

    /// <summary>Whether a pass is running or queued.</summary>
    public bool IsRunning => _runner.IsRunning;

    /// <summary>
    /// Sweeps the library, or queues one more pass when a sweep is running.
    /// <paramref name="retryingUnmatched"/> (a forced rescan) also asks again
    /// about titles TMDB had no match for. Returns the sweep's task, or a
    /// completed task when nothing is due. Never call it holding the library's lock.
    /// </summary>
    public Task Request(bool retryingUnmatched = false) => _runner.Request(retryingUnmatched);

    /// <summary>
    /// Titles a sweep would look up at <paramref name="at"/>: no TMDB match
    /// yet, and not marked unmatched within the retry interval. Episodes count
    /// once their show is matched. Zero means everything matchable is indexed.
    /// </summary>
    public int PendingCount(DateTimeOffset at, bool retryingUnmatched = false)
    {
        lock (_gate)
        {
            return DueMovies(at, retryingUnmatched).Count()
                + DueShows(at, retryingUnmatched).Count()
                + DueEpisodes(at, retryingUnmatched).Count();
        }
    }

    private bool HasDue(DateTimeOffset at, bool retryingUnmatched)
    {
        lock (_gate)
        {
            return DueMovies(at, retryingUnmatched).Any()
                || DueShows(at, retryingUnmatched).Any()
                || DueEpisodes(at, retryingUnmatched).Any();
        }
    }

    /// <summary>Call with the library's lock held.</summary>
    private IEnumerable<LibraryMovie> DueMovies(DateTimeOffset at, bool retryingUnmatched) =>
        _library.Movies.Where(movie => movie.TmdbId is null
            && LibraryEnrichment.IsDue(movie.MetadataCheckedAt, at, retryingUnmatched));

    /// <summary>Call with the library's lock held.</summary>
    private IEnumerable<LibraryShow> DueShows(DateTimeOffset at, bool retryingUnmatched) =>
        _library.Shows.Where(show => show.TmdbId is null
            && LibraryEnrichment.IsDue(show.MetadataCheckedAt, at, retryingUnmatched));

    /// <summary>
    /// Episodes without a match whose show has one; an unmatched show's
    /// episodes have nothing to be looked up against yet. Call with the
    /// library's lock held.
    /// </summary>
    private IEnumerable<(LibraryShow Show, int ShowId, LibraryEpisode Episode)> DueEpisodes(DateTimeOffset at, bool retryingUnmatched) =>
        _library.Shows
            .Where(show => show.TmdbId is not null)
            .SelectMany(show => show.Episodes
                .Where(episode => episode.TmdbId is null
                    && LibraryEnrichment.IsDue(episode.MetadataCheckedAt, at, retryingUnmatched))
                .Select(episode => (show, show.TmdbId!.Value, episode)));

    // ------------------------------------------------------------------
    // One pass
    // ------------------------------------------------------------------

    private async Task SweepAsync(bool retryingUnmatched)
    {
        var at = _now();
        var unsaved = 0;
        void CountRecorded()
        {
            if (++unsaved < PersistEvery) return;
            unsaved = 0;
            _persist();
        }

        try
        {
            // Newest first, as the Downloaded grids list them.
            List<(LibraryMovie Movie, string Title, int? Year)> movies;
            lock (_gate)
            {
                movies = [.. DueMovies(at, retryingUnmatched)
                    .OrderByDescending(movie => movie.DateAdded)
                    .Select(movie => (movie, movie.Title, movie.Year))];
            }
            foreach (var (movie, title, year) in movies)
            {
                var match = await LookupAsync(() => _lookup.SearchMovie(title, year)).ConfigureAwait(false);
                if (Record(movie, match, at)) CountRecorded();
            }

            List<(LibraryShow Show, string Name)> shows;
            lock (_gate)
            {
                shows = [.. DueShows(at, retryingUnmatched)
                    .OrderByDescending(show => show.DateAdded)
                    .Select(show => (show, show.Name))];
            }
            foreach (var (show, name) in shows)
            {
                var match = await LookupAsync(() => _lookup.SearchShow(name)).ConfigureAwait(false);
                if (Record(show, match, at)) CountRecorded();
            }

            // Taken after the shows, so the ones just matched are included,
            // along with episodes added to a show matched in an earlier sweep.
            List<(LibraryShow Show, int ShowId, LibraryEpisode Episode, int Season, int Number)> episodes;
            lock (_gate)
            {
                episodes = [.. DueEpisodes(at, retryingUnmatched)
                    .OrderByDescending(due => due.Show.DateAdded)
                    .ThenBy(due => due.Episode.Season)
                    .ThenBy(due => due.Episode.Episode)
                    .Select(due => (due.Show, due.ShowId, due.Episode, due.Episode.Season, due.Episode.Episode))];
            }
            foreach (var (show, showId, episode, season, number) in episodes)
            {
                var match = await LookupAsync(() => _lookup.FetchEpisode(showId, season, number)).ConfigureAwait(false);
                if (Record(show, showId, episode, match, at)) CountRecorded();
            }
        }
        catch (EnrichmentHalted)
        {
            // What's left stays unmarked for the next sweep.
        }
        finally
        {
            if (unsaved > 0) _persist();
        }
    }

    /// <summary>
    /// One lookup: null when TMDB answered without a usable match;
    /// <see cref="EnrichmentHalted"/> when it couldn't be reached or refused
    /// the request.
    /// </summary>
    private static async Task<T?> LookupAsync<T>(Func<Task<T?>> request) where T : class
    {
        try
        {
            return await request().ConfigureAwait(false);
        }
        catch (Exception error) when (LibraryEnrichment.HaltsEnrichment(error))
        {
            throw new EnrichmentHalted(error);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Records TMDB's answer for a movie: its match, or the time it had none.
    /// False when the movie left the library (or was matched) while TMDB
    /// answered, which records nothing.
    /// </summary>
    private bool Record(LibraryMovie movie, MovieMatch? match, DateTimeOffset at)
    {
        lock (_gate)
        {
            if (movie.TmdbId is not null || !_library.Movies.Contains(movie)) return false;
            if (match is null)
            {
                movie.MetadataCheckedAt = at;
                return true;
            }
            movie.TmdbId = match.TmdbId;
            movie.PosterUrl = match.PosterUrl;
            movie.BackdropUrl = match.BackdropUrl;
            movie.Overview = match.Overview ?? movie.Overview;
            movie.Year ??= match.Year;
            movie.RuntimeMinutes = match.RuntimeMinutes ?? movie.RuntimeMinutes;
            movie.MetadataCheckedAt = null;
            return true;
        }
    }

    /// <summary>Records TMDB's answer for a show, as for a movie.</summary>
    private bool Record(LibraryShow show, ShowMatch? match, DateTimeOffset at)
    {
        lock (_gate)
        {
            if (show.TmdbId is not null || !_library.Shows.Contains(show)) return false;
            if (match is null)
            {
                show.MetadataCheckedAt = at;
                return true;
            }
            show.TmdbId = match.TmdbId;
            show.PosterUrl = match.PosterUrl;
            show.BackdropUrl = match.BackdropUrl;
            show.Overview = match.Overview;
            show.FirstAirYear = match.FirstAirYear;
            show.MetadataCheckedAt = null;
            return true;
        }
    }

    /// <summary>
    /// Records TMDB's answer for an episode; null means TMDB has no such
    /// episode under that numbering. Nothing is recorded when the episode or
    /// its show left the library, or the show's match changed, while TMDB answered.
    /// </summary>
    private bool Record(LibraryShow show, int showId, LibraryEpisode episode, EpisodeMatch? match, DateTimeOffset at)
    {
        lock (_gate)
        {
            if (episode.TmdbId is not null || show.TmdbId != showId
                || !_library.Shows.Contains(show) || !show.Episodes.Contains(episode))
            {
                return false;
            }
            if (match is null)
            {
                episode.MetadataCheckedAt = at;
                return true;
            }
            episode.TmdbId = match.TmdbId;
            episode.Title = match.Title;
            episode.StillUrl = match.StillUrl;
            episode.RuntimeMinutes = match.RuntimeMinutes;
            episode.MetadataCheckedAt = null;
            return true;
        }
    }

    /// <summary>
    /// TMDB can't be reached or refuses the request, so every lookup after
    /// this one would fail the same way.
    /// </summary>
    private sealed class EnrichmentHalted(Exception cause) : Exception(cause.Message, cause);
}
