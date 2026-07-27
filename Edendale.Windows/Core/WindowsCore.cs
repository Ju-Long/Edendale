using Edendale.Windows.Models;
using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>Thrown when a Windows domain or TMDB operation cannot complete.</summary>
public sealed class WindowsCoreException(string message) : Exception(message);

/// <summary>
/// Stable Windows facade used by the WinUI pages. Every implementation behind this
/// surface is managed C# owned by the Windows app.
/// </summary>
public static class WindowsCore
{
    private static readonly TmdbRepository Tmdb = new();

    public static string CoreVersion => ".NET 8 (native C#)";
    public static bool HasTmdbCredentials => Tmdb.IsConfigured;

    public static ParsedMedia ParseMediaFile(string fileName) => MediaParser.Parse(fileName);

    public static double NormalizeProgress(double position) => Math.Clamp(position, 0, 1);

    public static string ProgressStorageKey(int tmdbId, string mediaType)
    {
        if (mediaType is not ("movie" or "episode"))
        {
            throw new WindowsCoreException($"Unsupported watch media type \"{mediaType}\".");
        }
        return $"{mediaType}:{tmdbId}";
    }

    public static ReleaseYearGrid ReleaseYearGrid(int year) =>
        ReleaseCalendar.CreateYearGrid(year);

    public static string SelectionSummary(string from, string to) =>
        ReleaseCalendar.SelectionSummary(from, to);

    public static Task<HomeCatalog> LoadHomeCatalogAsync(IReadOnlyList<WatchProgress> progress) =>
        Tmdb.LoadHomeAsync(progress);

    public static Task<List<MediaItem>> LoadCollectionAsync(string filter) =>
        Tmdb.LoadCollectionAsync(filter);

    public static Task<List<MediaItem>> SearchMediaAsync(string query) =>
        Tmdb.SearchMediaAsync(query);

    public static Task<List<PersonItem>> SearchPeopleAsync(string query) =>
        Tmdb.SearchPeopleAsync(query);

    public static Task<ScopedSearchResult> SearchScopedAsync(string query) =>
        Tmdb.SearchScopedAsync(query);

    public static Task<List<MediaItem>> LoadTrendingAsync() => Tmdb.TrendingAsync();

    public static Task<PersonDetail> LoadPersonDetailAsync(int personId) =>
        Tmdb.PersonDetailAsync(personId);

    public static Task<Dictionary<string, int>> ReleaseCountsAsync(int year) =>
        Tmdb.ReleaseCountsAsync(year);

    public static Task<List<MediaItem>> DiscoverReleasedAsync(string from, string to) =>
        Tmdb.DiscoverReleasedAsync(from, to);

    public static Task<MediaDetail> LoadMediaDetailAsync(int id, string mediaType) =>
        Tmdb.MediaDetailAsync(id, mediaType);

    public static Task<EpisodeDetail> LoadEpisodeDetailAsync(int showId, int season, int episode) =>
        Tmdb.EpisodeDetailAsync(showId, season, episode);

    public static Task<SeasonDetail> LoadSeasonDetailAsync(int showId, int seasonNumber) =>
        Tmdb.SeasonDetailAsync(showId, seasonNumber);

    public static Task<List<MediaItem>> LoadPersonFilmographyAsync(int personId) =>
        Tmdb.FilmographyAsync(personId);

    public static Task<TrailerVideo?> LoadBestTrailerAsync(int id, string mediaType) =>
        Tmdb.BestTrailerAsync(id, mediaType);

    public static Task<TmdbAuthStart> TmdbAuthStartAsync() =>
        Tmdb.BeginAuthenticationAsync();

    public static Task<TmdbSessionInfo> TmdbAuthFinishAsync(string requestToken) =>
        Tmdb.FinishAuthenticationAsync(requestToken);

    public static Task TmdbLogoutAsync(string sessionId) => Tmdb.LogoutAsync(sessionId);

    public static Task<UserMediaSyncOutcome> SyncUserMediaAsync(
        string sessionId,
        int accountId,
        IReadOnlyList<UserMediaRecord> local) =>
        Tmdb.SyncUserMediaAsync(sessionId, accountId, local);

    public static List<UserMediaRecord> MergeUserMedia(
        IReadOnlyList<UserMediaRecord> first,
        IReadOnlyList<UserMediaRecord> second) =>
        UserMediaMerger.MergeUserMedia(first, second);

    public static List<WatchProgress> MergeWatchProgress(
        IReadOnlyList<WatchProgress> first,
        IReadOnlyList<WatchProgress> second) =>
        UserMediaMerger.MergeWatchProgress(first, second);
}
