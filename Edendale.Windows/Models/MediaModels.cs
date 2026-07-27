// Windows media and TMDB domain models.

using System.Text.Json.Serialization;

namespace Edendale.Windows.Models;

public sealed class MediaRef
{
    public int Id { get; set; }
    public string MediaType { get; set; } = "movie";

    public bool IsShow => MediaType == "tv";
}

public sealed class MediaItem
{
    public int Id { get; set; }
    public string MediaType { get; set; } = "movie";
    public string Title { get; set; } = "";
    public string? Overview { get; set; }
    public string? PosterPath { get; set; }
    public string? BackdropPath { get; set; }
    public string? PosterUrl { get; set; }
    public string? BackdropUrl { get; set; }
    public double? VoteAverage { get; set; }
    public string? ReleaseDate { get; set; }
    public int? Year { get; set; }

    public MediaRef Ref => new() { Id = Id, MediaType = MediaType };
}

public sealed class Genre
{
    public int Id { get; set; }
    public string Name { get; set; } = "";
}

/// <summary>One day cell of the release heatmap; null slots pad the year's edges.</summary>
public sealed class ReleaseDaySlot
{
    public string DateKey { get; set; } = "";
    public int Year { get; set; }
    public int Month { get; set; }
    public int Day { get; set; }
    public bool IsFirstOfMonth { get; set; }
}

/// <summary>One week of the heatmap: seven slots, Sunday first.</summary>
public sealed class ReleaseWeekColumn
{
    public string? MonthLabel { get; set; }
    public List<ReleaseDaySlot?> Slots { get; set; } = [];
}

/// <summary>A year unrolled into week columns — the heatmap's layout.</summary>
public sealed class ReleaseYearGrid
{
    public int Year { get; set; }
    public List<ReleaseWeekColumn> Columns { get; set; } = [];
}

public sealed class CastMember
{
    public int Id { get; set; }
    public string Name { get; set; } = "";
    public string? Character { get; set; }
    public string? ProfilePath { get; set; }
    public string? ProfileUrl { get; set; }
}

/// <summary>An actor/actress row from people search.</summary>
public sealed class PersonItem
{
    public int Id { get; set; }
    public string Name { get; set; } = "";
    public string? ProfilePath { get; set; }
    public string? ProfileUrl { get; set; }
    public List<string> KnownFor { get; set; } = [];

    public string KnownForText => string.Join(" · ", KnownFor);
}

/// <summary>
/// /person/{id} — the biography header of a person page. The filmography is
/// a separate call so the two can be fetched concurrently.
/// </summary>
public sealed class PersonDetail
{
    public int Id { get; set; }
    public string Name { get; set; } = "";
    public string? Biography { get; set; }
    public string? ProfilePath { get; set; }
    public string? ProfileUrl { get; set; }
    public string? Birthday { get; set; }
    public string? Deathday { get; set; }
    public string? PlaceOfBirth { get; set; }
    public string? KnownForDepartment { get; set; }

    /// <summary>"1956 – 2016 · Concord, California"; empty when unknown.</summary>
    public string? Vitals { get; set; }

    public bool HasBiography => !string.IsNullOrWhiteSpace(Biography);
    public bool HasVitals => !string.IsNullOrWhiteSpace(Vitals);
    public bool HasDepartment => !string.IsNullOrWhiteSpace(KnownForDepartment);
}

/// <summary>
/// One scoped search: titles and people, plus the scope its keyword prefix
/// asked for. <see cref="LeadsWithPeople"/> decides which section renders first.
/// </summary>
public sealed class ScopedSearchResult
{
    /// <summary>all | people | movies | shows.</summary>
    public string Scope { get; set; } = "all";

    /// <summary>The query with any keyword prefix stripped.</summary>
    public string Term { get; set; } = "";

    public bool LeadsWithPeople { get; set; }
    public List<MediaItem> Titles { get; set; } = [];
    public List<PersonItem> People { get; set; } = [];

    public bool IsEmpty => Titles.Count == 0 && People.Count == 0;
}

public sealed class MediaDetail
{
    public MediaRef Ref { get; set; } = new();
    public string Title { get; set; } = "";
    public string? Tagline { get; set; }
    public string? Overview { get; set; }
    public string? PosterPath { get; set; }
    public string? BackdropPath { get; set; }
    public string? PosterUrl { get; set; }
    public string? BackdropUrl { get; set; }
    public int? Year { get; set; }
    public int? RuntimeMinutes { get; set; }
    public List<string> Genres { get; set; } = [];
    public string? Attribution { get; set; }
    public double? Score { get; set; }
    public int? VoteCount { get; set; }
    public List<CastMember> Cast { get; set; } = [];
    public int? SeasonCount { get; set; }
    public int? EpisodeCount { get; set; }

    /// <summary>
    /// Seasons in picker order — numbered ascending, Specials last, empty
    /// seasons already dropped by the Windows TMDB mapping.
    /// </summary>
    public List<SeasonSummary> Seasons { get; set; } = [];
}

public sealed class SeasonSummary
{
    public int SeasonNumber { get; set; }
    public string Name { get; set; } = "";
    public int? EpisodeCount { get; set; }
    public string? AirDate { get; set; }
    public string? PosterPath { get; set; }
    public string? PosterUrl { get; set; }
    public string? Overview { get; set; }

    /// <summary>"Season 2" for numbered seasons, TMDB's own name for Specials.</summary>
    public string DisplayTitle => SeasonNumber == 0 ? Name : $"Season {SeasonNumber}";
}

public sealed class SeasonDetail
{
    public int SeasonNumber { get; set; }
    public string Name { get; set; } = "";
    public string? Overview { get; set; }
    public string? AirDate { get; set; }
    public string? PosterPath { get; set; }
    public string? PosterUrl { get; set; }
    public List<EpisodeDetail> Episodes { get; set; } = [];
}

public sealed class WatchProgress
{
    public int TmdbId { get; set; }
    /// <summary>"movie" or "episode".</summary>
    public string MediaType { get; set; } = "movie";
    public double Position { get; set; }
    public double NormalizedPosition { get; set; }
    public double WatchedSeconds { get; set; }
    public long LastWatchedEpochMillis { get; set; }
    public bool IsCompleted { get; set; }
    public int? ShowTmdbId { get; set; }
    public int? SeasonNumber { get; set; }
    public int? EpisodeNumber { get; set; }
    public string StorageKey => $"{MediaType}:{TmdbId}";
}

public sealed class HeroScene
{
    public MediaDetail Detail { get; set; } = new();
    public WatchProgress? Progress { get; set; }
    public bool IsContinueWatching { get; set; }
    public string? RemainingText { get; set; }
}

public sealed class CollectionFilter
{
    public string Id { get; set; } = "all";
    public string Title { get; set; } = "";
    public int? GenreId { get; set; }
}

public sealed class HomeCatalog
{
    public List<HeroScene> HeroScenes { get; set; } = [];
    public List<MediaItem> Trending { get; set; } = [];
    public List<MediaItem> PopularMovies { get; set; } = [];
    public List<MediaItem> PopularShows { get; set; } = [];
    public List<MediaItem> TopRated { get; set; } = [];
    public List<Genre> Genres { get; set; } = [];
    public List<CollectionFilter> Collections { get; set; } = [];
}

public sealed class EpisodeDetail
{
    public int Id { get; set; }
    public string Name { get; set; } = "";
    public string? Overview { get; set; }
    public string? StillPath { get; set; }
    public string? StillUrl { get; set; }
    public string? AirDate { get; set; }
    public int? RuntimeMinutes { get; set; }
    public int? SeasonNumber { get; set; }
    public int? EpisodeNumber { get; set; }
    public double? VoteAverage { get; set; }
}

public sealed class TrailerVideo
{
    public string Id { get; set; } = "";
    public string Key { get; set; } = "";
    public string? Name { get; set; }
    public string? Site { get; set; }
    public string? Type { get; set; }
    public bool? Official { get; set; }
}

/// <summary>Result of local filename classification: "movie" or "episode".</summary>
public sealed class ParsedMedia
{
    public string Kind { get; set; } = "movie";
    public string? Title { get; set; }
    public int? Year { get; set; }
    public string? ShowName { get; set; }
    public int? Season { get; set; }
    public int? Episode { get; set; }

    [JsonIgnore]
    public bool IsEpisode => Kind == "episode";
}
