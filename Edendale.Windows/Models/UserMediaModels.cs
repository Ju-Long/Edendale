// Windows user-media and TMDB account models.

using System.Text.Json.Serialization;

namespace Edendale.Windows.Models;

/// <summary>
/// Per-title user state keyed by TMDB id + media type ("movie"/"tv"). The
/// record carries one sync stamp, <see cref="UpdateTime"/>, and the device
/// that wrote it (SYNC-PROTOCOL.md §6.2); the newest record wins as a whole.
/// The per-field times and dirty flags stay for the TMDB account push. The
/// watchlist lives in its own local-first <see cref="WatchlistRecord"/> store
/// (TMDB is its only cloud) and is mirrored here as <see cref="Watchlist"/>
/// so the sync record is complete. A record with nothing set but a stamp is a
/// tombstone and is kept, not dropped.
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
    public double? Rating { get; set; }
    public long RatingUpdatedAt { get; set; }
    public bool RatingDirty { get; set; }

    /// <summary>Mirrors the watchlist store for the sync record; never pushed to TMDB from here.</summary>
    public bool Watchlist { get; set; }

    /// <summary>Review text from another platform, carried verbatim; Windows has no review editor yet.</summary>
    public string? Review { get; set; }

    /// <summary>The sync stamp (§7.2); 0 for records from before sync, which then use the per-field times.</summary>
    public long UpdateTime { get; set; }

    /// <summary>The sync device ID that wrote this version; null for records from before sync.</summary>
    public string? Device { get; set; }

    public string StorageKey => $"{MediaType}:{TmdbId}";

    /// <summary>Whether anything is set, or waiting to be pushed to TMDB.</summary>
    public bool HasState =>
        Favourite || Rating is not null || Watchlist || Review is not null || FavouriteDirty || RatingDirty;

    /// <summary>
    /// Cleared on purpose, with the stamp of the clear (§6.2): every synced
    /// value is unset. A pending TMDB push does not make it live again.
    /// </summary>
    [JsonIgnore]
    public bool IsTombstone => !Favourite && Rating is null && !Watchlist && Review is null && UpdateTime > 0;

    /// <summary>The stamp the merge compares: the sync stamp, or for pre-sync records the newest per-field time (§8.4).</summary>
    [JsonIgnore]
    public long EffectiveUpdateTime => UpdateTime > 0 ? UpdateTime : Math.Max(FavouriteUpdatedAt, RatingUpdatedAt);
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
