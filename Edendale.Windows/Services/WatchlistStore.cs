// The watchlist's source of truth. Local mutations are saved first and mirrored
// to TMDB when an account is connected; a durable pending action survives a
// failed or interrupted request and is retried on the next sync. A full sync
// pulls the account's complete lists and lets the cloud win over confirmed
// local rows (pending taps still win until TMDB reflects them). Legacy watchlist
// flags written by older builds into user-media.json are migrated in on first
// run. This is a JSON-backed, WinUI-free service (like UserMediaStore), so the
// domain tests link it and inject a fake remote. Ports WatchlistStore.swift.

using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

public sealed class WatchlistStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
    };

    private readonly Dictionary<string, WatchlistRecord> _records = [];
    private readonly object _gate = new();
    private readonly IWatchlistRemote _remote;
    private readonly Func<bool> _isAuthenticated;
    private readonly string _storePath;
    private readonly string _legacyUserMediaPath;

    // Serialized remote work (coalesces overlapping push/pull requests).
    private readonly object _pumpGate = new();
    private bool _pumpRunning;
    private bool _pushRequested;
    private bool _pullRequested;

    /// <summary>Any change to the watchlist (local edit, migration, or sync).</summary>
    public event EventHandler? Changed;

    /// <param name="storePath">Watchlist JSON path; defaults to the app data file.</param>
    /// <param name="legacyUserMediaPath">Legacy user-media JSON to migrate from; defaults to the app data file.</param>
    public WatchlistStore(
        IWatchlistRemote remote,
        Func<bool> isAuthenticated,
        string? storePath = null,
        string? legacyUserMediaPath = null)
    {
        _remote = remote;
        _isAuthenticated = isAuthenticated;
        _storePath = storePath ?? AppPaths.WatchlistFile;
        _legacyUserMediaPath = legacyUserMediaPath ?? AppPaths.UserMediaFile;
        Load();
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /// <summary>Saved titles, newest first. Removed-but-unconfirmed rows are hidden.</summary>
    public IReadOnlyList<WatchlistRecord> Items
    {
        get
        {
            lock (_gate)
            {
                return [.. _records.Values
                    .Where(record => record.InWatchlist)
                    .OrderByDescending(record => record.DateAdded)];
            }
        }
    }

    public bool IsInWatchlist(MediaRef reference)
    {
        lock (_gate)
        {
            return _records.TryGetValue(WatchlistRecord.Key(reference), out var record)
                && record.InWatchlist;
        }
    }

    // ------------------------------------------------------------------
    // Local-first writes
    // ------------------------------------------------------------------

    public void Toggle(MediaRef reference, WatchlistMetadata? metadata = null) =>
        SetWatchlist(!IsInWatchlist(reference), reference, metadata);

    public void SetWatchlist(bool inWatchlist, MediaRef reference, WatchlistMetadata? metadata = null)
    {
        var changed = false;
        lock (_gate)
        {
            var key = WatchlistRecord.Key(reference);
            _records.TryGetValue(key, out var existing);
            // Nothing to do when removing a title that was never saved.
            if (existing is null && !inWatchlist) return;

            var record = existing ?? WatchlistRecord.Create(
                reference, metadata, inWatchlist: inWatchlist, pending: WatchlistPendingAction.None);
            if (existing is null) _records[key] = record;
            else if (metadata is not null) record.Apply(metadata);

            var stateChanged = record.InWatchlist != inWatchlist;
            record.InWatchlist = inWatchlist;
            record.UpdatedAt = NowMillis();
            if (stateChanged || existing is null)
            {
                record.PendingAction = inWatchlist ? WatchlistPendingAction.Add : WatchlistPendingAction.Remove;
                if (inWatchlist) record.DateAdded = NowMillis();
                changed = true;
            }
        }

        Save();
        RaiseChanged();
        if (changed) RequestPush();
    }

    public void Remove(WatchlistRecord record) => SetWatchlist(false, record.Ref);

    /// <summary>Keeps a saved card fresh when its full detail page loads.</summary>
    public void UpdateMetadata(MediaDetail detail)
    {
        if (!IsInWatchlist(detail.Ref)) return;
        lock (_gate)
        {
            if (!_records.TryGetValue(WatchlistRecord.Key(detail.Ref), out var record)) return;
            record.Apply(WatchlistMetadata.From(detail));
        }
        Save();
        RaiseChanged();
    }

    // ------------------------------------------------------------------
    // TMDB sync (cloud takes priority)
    // ------------------------------------------------------------------

    /// <summary>Flushes pending local mutations, then makes confirmed local rows
    /// match the connected account's complete movie and TV watchlists.</summary>
    public Task SyncFromTMDBAsync()
    {
        if (!_isAuthenticated()) return Task.CompletedTask;
        lock (_pumpGate) { _pullRequested = true; }
        return PumpAsync();
    }

    private void RequestPush()
    {
        if (!_isAuthenticated()) return;
        lock (_pumpGate) { _pushRequested = true; }
        _ = PumpAsync();
    }

    /// <summary>
    /// Serializes change pushes and full pulls. A request that arrives while a
    /// pump is running is observed by that pump before it goes idle, so no
    /// wake-up is lost. Push and pull never throw, so the loop only exits under
    /// the flag lock.
    /// </summary>
    private async Task PumpAsync()
    {
        lock (_pumpGate)
        {
            if (_pumpRunning) return;
            _pumpRunning = true;
        }

        try
        {
            while (_isAuthenticated())
            {
                bool pull, push;
                lock (_pumpGate)
                {
                    pull = _pullRequested;
                    push = _pushRequested || pull;
                    if (!push)
                    {
                        _pumpRunning = false;
                        return;
                    }
                    _pullRequested = false;
                    _pushRequested = false;
                }

                await PushPendingChangesAsync();
                if (pull) await PullFromTmdbAsync();
            }
        }
        finally
        {
            lock (_pumpGate) { _pumpRunning = false; }
        }
    }

    /// <summary>Successful writes stay pending until a subsequent pull confirms
    /// them; that stops a briefly stale TMDB list from undoing a local tap.</summary>
    private async Task PushPendingChangesAsync()
    {
        List<WatchlistRecord> pending;
        lock (_gate) pending = WatchlistReconciler.PendingPushOrder(_records.Values);

        foreach (var record in pending)
        {
            if (!_isAuthenticated()) return;
            var action = record.PendingAction;
            if (action == WatchlistPendingAction.None) continue;
            try
            {
                await _remote.SetWatchlistAsync(record.Ref, action == WatchlistPendingAction.Add);
            }
            catch
            {
                // Leave the pending action in place; a later pump retries it.
            }
        }
    }

    private async Task PullFromTmdbAsync()
    {
        try
        {
            var moviesTask = _remote.WatchlistItemsAsync("movie");
            var showsTask = _remote.WatchlistItemsAsync("tv");
            await Task.WhenAll(moviesTask, showsTask);

            var remote = new List<MediaItem>();
            remote.AddRange(await moviesTask);
            remote.AddRange(await showsTask);

            lock (_gate)
            {
                var reconciled = WatchlistReconciler.Reconcile([.. _records.Values], remote);
                _records.Clear();
                foreach (var record in reconciled) _records[record.StorageKey] = record;
            }
            Save();
            RaiseChanged();
        }
        catch
        {
            // Transient network/auth failure; the next sync retries.
        }
    }

    // ------------------------------------------------------------------
    // Persistence + migration
    // ------------------------------------------------------------------

    private void Load()
    {
        try
        {
            if (File.Exists(_storePath))
            {
                var stored = JsonSerializer.Deserialize<List<WatchlistRecord>>(
                    File.ReadAllText(_storePath), JsonOptions) ?? [];
                foreach (var record in stored) _records[record.StorageKey] = record;
                return;
            }

            // First run on this device: adopt any watchlist flags older builds
            // wrote into user-media.json, then write watchlist.json so the
            // migration runs exactly once.
            MigrateLegacyWatchlist();
            Save();
        }
        catch
        {
            // A corrupt store starts empty rather than blocking launch.
        }
    }

    private void MigrateLegacyWatchlist()
    {
        if (!File.Exists(_legacyUserMediaPath)) return;
        using var document = JsonDocument.Parse(File.ReadAllText(_legacyUserMediaPath));
        if (document.RootElement.ValueKind != JsonValueKind.Array) return;

        foreach (var element in document.RootElement.EnumerateArray())
        {
            if (element.Bool("watchlist") != true || element.Int("tmdbId") is not int id) continue;
            var reference = new MediaRef { Id = id, MediaType = element.String("mediaType") ?? "movie" };
            var key = WatchlistRecord.Key(reference);
            if (_records.ContainsKey(key)) continue;

            var metadata = new WatchlistMetadata
            {
                Title = element.String("title"),
                PosterPath = element.String("posterPath"),
                PosterUrl = element.String("posterUrl"),
            };
            _records[key] = WatchlistRecord.Create(
                reference,
                metadata,
                inWatchlist: true,
                pending: WatchlistPendingAction.Add,
                dateAdded: LegacyMillis(element, "watchlistUpdatedAt"));
        }
    }

    private static long LegacyMillis(JsonElement element, string name) =>
        element.Property(name) is { ValueKind: JsonValueKind.Number } value && value.TryGetInt64(out var millis)
            ? millis
            : NowMillis();

    private void Save()
    {
        try
        {
            List<WatchlistRecord> snapshot;
            lock (_gate) snapshot = [.. _records.Values];
            var temporary = _storePath + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(snapshot, JsonOptions));
            File.Move(temporary, _storePath, overwrite: true);
        }
        catch
        {
            // The watchlist is convenience data; a failed write must never crash the UI.
        }
    }

    private void RaiseChanged() => Changed?.Invoke(this, EventArgs.Empty);

    private static long NowMillis() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
