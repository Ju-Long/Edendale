// Windows user-media and TMDB account models.

namespace Edendale.Windows.Models;

/// <summary>
/// Per-title user state keyed by TMDB id + media type ("movie"/"tv").
/// Each field carries its own last-write timestamp (cloud-replica merge) and
/// dirty flag (pending TMDB account push).
/// </summary>
public sealed class UserMediaRecord
{
    public int TmdbId { get; set; }
    public string MediaType { get; set; } = "movie";
    public string? Title { get; set; }
    public string? PosterPath { get; set; }
    public string? PosterUrl { get; set; }
    public bool Favourite { get; set; }
    public long FavouriteUpdatedAt { get; set; }
    public bool FavouriteDirty { get; set; }
    public bool Watchlist { get; set; }
    public long WatchlistUpdatedAt { get; set; }
    public bool WatchlistDirty { get; set; }
    public double? Rating { get; set; }
    public long RatingUpdatedAt { get; set; }
    public bool RatingDirty { get; set; }

    public string StorageKey => $"{MediaType}:{TmdbId}";

    /// <summary>Stateless records are pruned from local and remote replicas.</summary>
    public bool HasState =>
        Favourite || Watchlist || Rating is not null ||
        FavouriteDirty || WatchlistDirty || RatingDirty;
}

/// <summary>Step one of the TMDB connect flow.</summary>
public sealed class TmdbAuthStart
{
    public string RequestToken { get; set; } = "";
    public string ApprovalUrl { get; set; } = "";
}

/// <summary>A signed-in TMDB session; persisted DPAPI-protected on disk.</summary>
public sealed class TmdbSessionInfo
{
    public string SessionId { get; set; } = "";
    public int AccountId { get; set; }
    public string? Username { get; set; }
    public string? Name { get; set; }
}

/// <summary>Result of one TMDB sync round; Records replaces the local store.</summary>
public sealed class UserMediaSyncOutcome
{
    public List<UserMediaRecord> Records { get; set; } = [];
    public int Pushed { get; set; }
    public int Pulled { get; set; }
}
