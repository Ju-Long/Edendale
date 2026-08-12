// Windows watchlist domain models. The watchlist is a local-first mirror of a
// TMDB account watchlist: presentation metadata is stored with the reference so
// cards render offline, and a durable pending action survives relaunches until
// TMDB confirms it. Kept separate from UserMediaRecord (favourites/ratings),
// exactly as the Apple branch keeps WatchlistItem separate from CDUserMedia.

using System.Text.Json.Serialization;
using Edendale.Windows.Core;

namespace Edendale.Windows.Models;

/// <summary>An unconfirmed local mutation awaiting a TMDB account write.</summary>
public enum WatchlistPendingAction
{
    None,
    Add,
    Remove,
}

/// <summary>The metadata needed to render a watchlist card without a request.</summary>
public sealed class WatchlistMetadata
{
    public string? Title { get; init; }
    public string? Overview { get; init; }
    public string? PosterPath { get; init; }
    public string? BackdropPath { get; init; }
    public string? PosterUrl { get; init; }
    public double? VoteAverage { get; init; }
    public string? ReleaseDate { get; init; }

    public static WatchlistMetadata From(MediaItem item) => new()
    {
        Title = item.Title,
        Overview = item.Overview,
        PosterPath = item.PosterPath,
        BackdropPath = item.BackdropPath,
        PosterUrl = item.PosterUrl,
        VoteAverage = item.VoteAverage,
        ReleaseDate = item.ReleaseDate,
    };

    public static WatchlistMetadata From(MediaDetail detail) => new()
    {
        Title = detail.Title,
        Overview = detail.Overview,
        PosterPath = detail.PosterPath,
        BackdropPath = detail.BackdropPath,
        PosterUrl = detail.PosterUrl,
        VoteAverage = detail.Score,
        // MediaDetail carries a parsed year rather than a raw date string.
        ReleaseDate = detail.Year?.ToString(),
    };
}

/// <summary>
/// One saved title. Persisted to <c>watchlist.json</c>; device-local and, unlike
/// favourites/ratings, deliberately outside the OneDrive replica — TMDB is its
/// only cloud (parity with Apple's local-only Watchlist SwiftData container).
/// </summary>
public sealed class WatchlistRecord
{
    public int TmdbId { get; set; }
    public string MediaType { get; set; } = "movie";
    public string? Title { get; set; }
    public string? Overview { get; set; }
    public string? PosterPath { get; set; }
    public string? BackdropPath { get; set; }
    public string? PosterUrl { get; set; }
    public double? VoteAverage { get; set; }
    public string? ReleaseDate { get; set; }
    public long DateAdded { get; set; }
    public long UpdatedAt { get; set; }
    public bool InWatchlist { get; set; } = true;

    /// <summary>Serialized <see cref="WatchlistPendingAction"/>; read through <see cref="PendingAction"/>.</summary>
    public string PendingActionRaw { get; set; } = nameof(WatchlistPendingAction.Add);

    /// <summary>Composite identity keeps movie 123 distinct from TV show 123.</summary>
    [JsonIgnore]
    public string StorageKey => Key(TmdbId, MediaType);

    [JsonIgnore]
    public MediaRef Ref => new() { Id = TmdbId, MediaType = MediaType };

    [JsonIgnore]
    public WatchlistPendingAction PendingAction
    {
        get => Enum.TryParse<WatchlistPendingAction>(PendingActionRaw, out var value)
            ? value
            : WatchlistPendingAction.None;
        set => PendingActionRaw = value.ToString();
    }

    /// <summary>Non-null title for card binding; a saved record always has one.</summary>
    [JsonIgnore]
    public string DisplayTitle => Title ?? "";

    /// <summary>Release year parsed from the stored date, or null.</summary>
    [JsonIgnore]
    public int? Year =>
        ReleaseDate is { Length: >= 4 } date && int.TryParse(date[..4], out var year) ? year : null;

    /// <summary>Poster-card subtitle: the release year, always available offline.</summary>
    [JsonIgnore]
    public string? SubtitleText => Year?.ToString();

    public static string Key(int tmdbId, string mediaType) => $"{mediaType}:{tmdbId}";

    public static string Key(MediaRef reference) => Key(reference.Id, reference.MediaType);

    /// <summary>Refreshes the stored snapshot; blanks never overwrite good data.</summary>
    public void Apply(WatchlistMetadata metadata)
    {
        if (NonBlank(metadata.Title) is { } title) Title = title;
        if (NonBlank(metadata.Overview) is { } overview) Overview = overview;
        if (metadata.PosterPath is { } posterPath) PosterPath = posterPath;
        if (metadata.BackdropPath is { } backdropPath) BackdropPath = backdropPath;
        if (metadata.PosterUrl is { } posterUrl) PosterUrl = posterUrl;
        if (metadata.VoteAverage is { } voteAverage) VoteAverage = voteAverage;
        if (NonBlank(metadata.ReleaseDate) is { } releaseDate) ReleaseDate = releaseDate;
        UpdatedAt = NowMillis();
    }

    public static WatchlistRecord Create(
        MediaRef reference,
        WatchlistMetadata? metadata = null,
        bool inWatchlist = true,
        WatchlistPendingAction pending = WatchlistPendingAction.Add,
        long? dateAdded = null)
    {
        var record = new WatchlistRecord
        {
            TmdbId = reference.Id,
            MediaType = reference.MediaType,
            InWatchlist = inWatchlist,
            PendingAction = pending,
        };
        if (metadata is not null) record.Apply(metadata);
        var stamp = dateAdded ?? NowMillis();
        record.DateAdded = stamp;
        record.UpdatedAt = stamp;
        record.Title ??= FallbackTitle(reference.MediaType);
        return record;
    }

    private static string FallbackTitle(string mediaType) =>
        AppText.Get(mediaType == "tv" ? "Watchlist_FallbackShow" : "Watchlist_FallbackMovie");

    private static long NowMillis() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private static string? NonBlank(string? value) =>
        string.IsNullOrWhiteSpace(value) ? null : value;
}

/// <summary>
/// The TMDB account operations the watchlist store needs. The app binds this to
/// the connected session; tests supply a fake. Watchlist is the only cloud the
/// store talks to, so no OneDrive/session details leak into the domain layer.
/// </summary>
public interface IWatchlistRemote
{
    Task<IReadOnlyList<MediaItem>> WatchlistItemsAsync(string mediaType);
    Task SetWatchlistAsync(MediaRef reference, bool inWatchlist);
}
