// The client procedure of SYNC-PROTOCOL.md §8 for Windows: when to sync
// (launch, foreground, 10 s after the last local change, at once when
// playback stops or the app closes, every 5 minutes), one sync (list the
// space, read the other devices' changed files, merge, write this device's
// file when anything changed), failure backoff (1, 2, 4, 8 minutes, capped
// at an hour), and the one-time import of the OneDrive replica (§8.4). It
// never blocks playback or import, and a failed listing or read leaves the
// local stores untouched. WinUI-free, so the tests drive it with a folder
// location.

using Edendale.Windows.Core;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services.Sync;

/// <summary>What Settings shows: the location, the space, and when the device last synced.</summary>
public sealed record SyncStatus(
    bool IsOn,
    string? LocationDescription,
    string? Space,
    long LastSyncMillis,
    string? LastError,
    bool IsSyncing,
    IReadOnlyList<SyncPeer> Peers,
    IReadOnlyList<string> Warnings);

public sealed class SyncEngine : IDisposable
{
    /// <summary>§8.1: write this long after the last local change.</summary>
    public static readonly TimeSpan WriteDebounce = TimeSpan.FromSeconds(10);

    /// <summary>§8.1: read this often while the app is in the foreground.</summary>
    public static readonly TimeSpan ReadInterval = TimeSpan.FromMinutes(5);

    /// <summary>A foreground return sooner than this after the last sync does not sync again.</summary>
    public static readonly TimeSpan ForegroundThrottle = TimeSpan.FromSeconds(30);

    /// <summary>§8.2: 1, 2, 4, 8 minutes, capped at an hour, reset by a success.</summary>
    public static readonly TimeSpan[] Backoff =
    [
        TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(2), TimeSpan.FromMinutes(4), TimeSpan.FromMinutes(8),
        TimeSpan.FromMinutes(16), TimeSpan.FromMinutes(32), TimeSpan.FromMinutes(60),
    ];

    private readonly SyncState _state;
    private readonly SyncClock _clock;
    private readonly WatchProgressStore _progress;
    private readonly UserMediaStore _media;
    private readonly WatchlistStore? _watchlist;
    private readonly Func<SyncLocationSettings, ISyncLocation?> _locationFactory;
    private readonly Func<int, int, int, int?>? _episodeResolver;
    private readonly string _appVersion;
    private readonly SemaphoreSlim _syncGate = new(1, 1);
    private readonly object _gate = new();
    private readonly List<string> _warnings = [];

    private Timer? _writeDebounce;
    private Timer? _periodic;
    private bool _dirty;
    private bool _applying;
    private bool _started;
    private bool _syncing;
    private bool _foreground = true;
    private int _failures;
    private long _nextAllowedMillis;
    private string? _lastError;
    private ISyncLocation? _cachedLocation;
    private SyncLocationSettings? _cachedSettings;

    public SyncEngine(
        SyncState state,
        SyncClock clock,
        WatchProgressStore progress,
        UserMediaStore media,
        WatchlistStore? watchlist,
        Func<SyncLocationSettings, ISyncLocation?> locationFactory,
        Func<int, int, int, int?>? episodeResolver = null,
        string? appVersion = null)
    {
        _state = state;
        _clock = clock;
        _progress = progress;
        _media = media;
        _watchlist = watchlist;
        _locationFactory = locationFactory;
        _episodeResolver = episodeResolver;
        _appVersion = appVersion ?? "27.1";
        _clock.Restore(_state.HighWater);
        _clock.HighWaterChanged += (_, _) => _state.HighWater = _clock.HighWater;

        // Local writes mark the device file stale from the first moment, whether
        // or not the launch procedure has run yet.
        _progress.Changed += (_, _) => OnStoreChanged();
        _media.Changed += (_, _) => OnStoreChanged();
        if (_watchlist is not null)
        {
            _watchlist.Changed += (_, _) =>
            {
                if (_applying || !_started) return;
                MirrorWatchlist(initial: false);
            };
        }
        _state.Changed += (_, _) => RaiseStatusChanged();
    }

    /// <summary>Status changes: a sync started or finished, the location changed, a warning appeared.</summary>
    public event EventHandler? StatusChanged;

    /// <summary>Tests and the UI can wait for the sync in flight.</summary>
    public Task? Current { get; private set; }

    public SyncStatus Status
    {
        get
        {
            lock (_gate)
            {
                var location = _state.Location;
                return new SyncStatus(
                    location is not null,
                    location is null ? null : Describe(location),
                    location?.Space,
                    _state.LastSyncMillis,
                    _lastError,
                    _syncing,
                    _state.Peers,
                    [.. _warnings]);
            }
        }
    }

    public string DeviceId => _state.DeviceId;

    public SyncState State => _state;

    /// <summary>The location's one-line description for Settings; never a credential.</summary>
    public string Describe(SyncLocationSettings location) =>
        _locationFactory(location)?.Description ?? location.Kind.ToString();

    // ------------------------------------------------------------------
    // Triggers (§8.1)
    // ------------------------------------------------------------------

    /// <summary>
    /// Launch: imports the replica once, mirrors the watchlist into the
    /// user-media records, subscribes to the stores, syncs, and starts the
    /// 5-minute timer. Safe to call once.
    /// </summary>
    public void Start()
    {
        if (_started) return;
        _started = true;

        ImportReplicaOnce();
        MirrorWatchlist(initial: true);

        // §8.1: every 5 minutes while the app is in the foreground.
        _periodic = new Timer(_ =>
        {
            if (_foreground) Trigger("periodic");
        }, null, ReadInterval, ReadInterval);
        Trigger("launch");
    }

    /// <summary>Return to the foreground: a read, then a write if anything changed locally.</summary>
    public void OnForeground()
    {
        _foreground = true;
        if (!_started || !_state.IsOn) return;
        var since = _clock.Now - _state.LastSyncMillis;
        if (since >= 0 && since < ForegroundThrottle.TotalMilliseconds) return;
        Trigger("foreground");
    }

    /// <summary>The app went to the background: anything changed is written at once (§8.1).</summary>
    public Task OnBackgroundAsync()
    {
        _foreground = false;
        return _started && _state.IsOn ? WriteNowAsync() : Task.CompletedTask;
    }

    /// <summary>Playback stopped or the app is closing: write at once when anything changed (§8.1).</summary>
    public Task WriteNowAsync()
    {
        lock (_gate)
        {
            _writeDebounce?.Dispose();
            _writeDebounce = null;
            if (!_dirty) return Task.CompletedTask;
        }
        return SyncAsync("write-now", userRequested: false);
    }

    /// <summary>Settings → Sync now: ignores the backoff.</summary>
    public Task SyncNowAsync() => SyncAsync("user", userRequested: true);

    private void OnStoreChanged()
    {
        if (_applying) return;
        lock (_gate)
        {
            _dirty = true;
            if (!_started || !_state.IsOn) return;
            _writeDebounce?.Dispose();
            _writeDebounce = new Timer(_ => Trigger("local-change"), null, WriteDebounce, Timeout.InfiniteTimeSpan);
        }
    }

    private void Trigger(string reason)
    {
        if (!_state.IsOn) return;
        _ = SyncAsync(reason, userRequested: false);
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    /// <summary>
    /// Chooses the location (§4): lists the space once to prove it works,
    /// creating Edendale/ and the space folder, then syncs. The old location
    /// is left alone; the caller offers to delete this device's file there.
    /// Throws a ConnectorException or IOException when the location fails.
    /// </summary>
    public async Task SetLocationAsync(SyncLocationSettings location, CancellationToken cancellation)
    {
        if (SyncSpaceName.Normalize(location.Space) is not string space) throw new ArgumentException("Invalid space name", nameof(location));
        location = location with { Space = space };
        var target = _locationFactory(location) ?? throw new ConnectorException(ConnectorFailure.SignInRequired, location.Kind.ToString());
        await target.ListAsync(space, cancellation).ConfigureAwait(false);
        if (target is IHasProviderIds ids) location = ids.Apply(location);

        DropCachedLocation();
        _state.SetLocation(location);
        lock (_gate)
        {
            _dirty = true;
            _failures = 0;
            _nextAllowedMillis = 0;
            _lastError = null;
            _warnings.Clear();
        }
        SyncLog.Info("sync", $"location set: {location.Kind}");
        RaiseStatusChanged();
        if (_started) await SyncAsync("location", userRequested: true).ConfigureAwait(false);
    }

    /// <summary>Turns sync off. The local store stays; the device's file in the location is deleted when asked.</summary>
    public async Task TurnOffAsync(bool deleteOwnFile, CancellationToken cancellation)
    {
        var location = _state.Location;
        if (location is not null && deleteOwnFile) await DeleteOwnFileAtAsync(location, cancellation).ConfigureAwait(false);
        DropCachedLocation();
        _state.SetLocation(null);
        lock (_gate)
        {
            _lastError = null;
            _warnings.Clear();
        }
        RaiseStatusChanged();
    }

    /// <summary>
    /// §4: after switching locations or spaces, the app offers to delete this
    /// device's file at the old one. Failures are logged, not thrown: the old
    /// location may be gone.
    /// </summary>
    public async Task<bool> DeleteOwnFileAtAsync(SyncLocationSettings location, CancellationToken cancellation)
    {
        try
        {
            var target = _locationFactory(location);
            if (target is null) return false;
            await target.DeleteAsync(location.Space, SyncFormat.FileName(_state.DeviceId), cancellation).ConfigureAwait(false);
            (target as IDisposable)?.Dispose();
            SyncLog.Info("sync", "deleted own file at a previous location");
            return true;
        }
        catch (Exception error)
        {
            SyncLog.Error("sync", "delete own file", error);
            return false;
        }
    }

    /// <summary>§8.3: deletes another device's file. Its records are already in every other file.</summary>
    public async Task RemoveDeviceAsync(string deviceId, CancellationToken cancellation)
    {
        var location = _state.Location ?? throw new InvalidOperationException("Sync is off");
        var target = LocationFor(location) ?? throw new ConnectorException(ConnectorFailure.SignInRequired, location.Kind.ToString());
        await target.DeleteAsync(location.Space, SyncFormat.FileName(deviceId), cancellation).ConfigureAwait(false);
        _state.ForgetPeer(deviceId);
        SyncLog.Info("sync", "removed a device file");
    }

    // ------------------------------------------------------------------
    // One sync (§8.2)
    // ------------------------------------------------------------------

    private Task SyncAsync(string reason, bool userRequested)
    {
        var task = RunAsync(reason, userRequested);
        Current = task;
        return task;
    }

    private async Task RunAsync(string reason, bool userRequested)
    {
        if (_state.Location is not { } location) return;
        if (!userRequested && _clock.Now < _nextAllowedMillis) return;
        if (!await _syncGate.WaitAsync(0).ConfigureAwait(false))
        {
            // A sync is running; a local change arriving now is picked up by its write step or the next trigger.
            return;
        }
        lock (_gate) _syncing = true;
        RaiseStatusChanged();
        var wrote = false;
        try
        {
            var target = LocationFor(location);
            if (target is null)
            {
                Fail(new ConnectorException(ConnectorFailure.SignInRequired, location.Kind.ToString()), "no login for the location");
                return;
            }
            using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(3));
            var cancellation = timeout.Token;

            // 1. List the space (creating the folders if missing).
            IReadOnlyList<SyncFileEntry> entries;
            try
            {
                entries = await target.ListAsync(location.Space, cancellation).ConfigureAwait(false);
            }
            catch (Exception error) when (error is not OperationCanceledException || cancellation.IsCancellationRequested)
            {
                Fail(error, "list");
                return;
            }
            if (target is IHasProviderIds ids)
            {
                var updated = ids.Apply(location);
                if (updated != location)
                {
                    _state.UpdateLocation(updated);
                    lock (_gate) _cachedSettings = updated;
                }
            }

            // 2. Read and merge the other devices' changed files.
            var ownFile = SyncFormat.FileName(_state.DeviceId);
            var merged = 0;
            foreach (var entry in entries)
            {
                cancellation.ThrowIfCancellationRequested();
                if (!SyncFormat.IsDeviceFileName(entry.Name) || entry.Name == ownFile) continue;
                if (_state.ReadStamp(entry.Name) == entry.Stamp) continue;
                byte[] bytes;
                try
                {
                    bytes = await target.ReadAsync(location.Space, entry.Name, cancellation).ConfigureAwait(false);
                }
                catch (Exception error) when (error is not OperationCanceledException || cancellation.IsCancellationRequested)
                {
                    SyncLog.Error("sync", $"read {entry.Name}", error);
                    continue; // The next trigger retries this file; the others still merge.
                }
                var result = SyncDeviceFileCodec.Decode(bytes, entry.Name, location.Space);
                var peerId = entry.Name[..^5];
                if (result.File is not { } file)
                {
                    var reason2 = result.Skipped ?? SyncSkipReason.Malformed;
                    SyncLog.Warn("sync", $"skipped {entry.Name}: {reason2}");
                    AddWarning(SkipWarning(reason2, result.DeviceName ?? _state.Peers.FirstOrDefault(p => p.Id == peerId)?.Name));
                    _state.RecordRead(entry.Name, entry.Stamp, new SyncPeer
                    {
                        Id = peerId,
                        Name = result.DeviceName ?? "",
                        LastReadMillis = _clock.Now,
                        Skipped = reason2,
                    });
                    continue;
                }
                var outcome = Apply(file);
                merged += outcome.Applied;
                if (outcome.FarFuture > 0) AddWarning(AppText.Format("Sync_WarningClockAhead", file.Device.Name));
                SyncLog.Info("sync", $"read {entry.Name}: applied {outcome.Applied}, ignored {outcome.Ignored}, far-future {outcome.FarFuture}, stamp {file.UpdateTime}");
                _state.RecordRead(entry.Name, entry.Stamp, new SyncPeer
                {
                    Id = file.Device.Id,
                    Name = file.Device.Name,
                    Platform = file.Device.Platform,
                    FileUpdateTime = file.UpdateTime,
                    LastReadMillis = _clock.Now,
                });
            }
            if (merged > 0)
            {
                lock (_gate) _dirty = true; // This device's merged view must go out (§7.1).
            }

            // 3. Write this device's file when anything changed since the last write:
            //    a local edit, a merge above, a first sync, or stamps applied since.
            bool dirty;
            lock (_gate) dirty = _dirty || _state.LastWriteMillis == 0 || _clock.HighWater > _state.WrittenHighWater;
            if (dirty)
            {
                var file = BuildOwnFile(location.Space);
                var bytes = SyncDeviceFileCodec.Encode(file);
                try
                {
                    await target.WriteAsync(location.Space, ownFile, bytes, cancellation).ConfigureAwait(false);
                    wrote = true;
                    lock (_gate) _dirty = false;
                    _state.WrittenHighWater = file.UpdateTime;
                    SyncLog.Info("sync", $"wrote own file: {file.WatchProgress.Count} progress, {file.UserMedia.Count} user-media records, {bytes.Length} bytes, stamp {file.UpdateTime}");
                }
                catch (Exception error) when (error is not OperationCanceledException || cancellation.IsCancellationRequested)
                {
                    Fail(error, "write");
                    return;
                }
            }

            // 4. Done.
            lock (_gate)
            {
                _failures = 0;
                _nextAllowedMillis = 0;
                _lastError = null;
            }
            _state.RecordSync(_clock.Now, wrote);
            SyncLog.Info("sync", $"ok ({reason}): {entries.Count} files listed, {merged} records applied, wrote={wrote}");
        }
        catch (OperationCanceledException)
        {
            Fail(new TimeoutException("sync timed out"), "timeout");
        }
        catch (Exception error)
        {
            Fail(error, "sync");
        }
        finally
        {
            lock (_gate) _syncing = false;
            _syncGate.Release();
            RaiseStatusChanged();
        }
    }

    /// <summary>
    /// The location for the current settings, kept between syncs so folder
    /// ids and connections are reused; dropped on any failure, so a login
    /// linked again takes effect at the next try.
    /// </summary>
    private ISyncLocation? LocationFor(SyncLocationSettings settings)
    {
        lock (_gate)
        {
            if (_cachedLocation is not null && _cachedSettings == settings) return _cachedLocation;
        }
        var created = _locationFactory(settings);
        lock (_gate)
        {
            (_cachedLocation as IDisposable)?.Dispose();
            _cachedLocation = created;
            _cachedSettings = created is null ? null : settings;
        }
        return created;
    }

    private void DropCachedLocation()
    {
        lock (_gate)
        {
            (_cachedLocation as IDisposable)?.Dispose();
            _cachedLocation = null;
            _cachedSettings = null;
        }
    }

    private void Fail(Exception error, string context)
    {
        DropCachedLocation();
        lock (_gate)
        {
            var delay = Backoff[Math.Min(_failures, Backoff.Length - 1)];
            _failures++;
            _nextAllowedMillis = _clock.Now + (long)delay.TotalMilliseconds;
            _lastError = error is ConnectorException or IOException or TimeoutException ? error.Message : error.GetType().Name;
        }
        SyncLog.Error("sync", context, error);
    }

    // ------------------------------------------------------------------
    // Merge in, build out
    // ------------------------------------------------------------------

    /// <summary>§7.1 against the stores, then the stores take the merged view.</summary>
    private SyncMergeOutcome Apply(SyncDeviceFile file)
    {
        var thisDevice = _state.DeviceId;
        var outcome = new SyncMergeOutcome();

        // Progress: by sync key, local-only episodes left alone.
        var progressLocal = _progress.AllIncludingCleared;
        var progressStore = new Dictionary<string, SyncProgressRecord>(StringComparer.Ordinal);
        var progressByKey = new Dictionary<string, WatchProgress>(StringComparer.Ordinal);
        foreach (var record in progressLocal)
        {
            if (SyncRecordMapping.ToSync(record, thisDevice) is { } sync)
            {
                progressStore[sync.Key] = sync;
                progressByKey[sync.Key] = record;
            }
        }
        var progressOutcome = SyncMerge.Apply(progressStore, file.WatchProgress, _clock);

        // User media: by key.
        var mediaLocal = _media.All;
        var mediaStore = new Dictionary<string, SyncUserMediaRecord>(StringComparer.Ordinal);
        var mediaByKey = new Dictionary<string, UserMediaRecord>(StringComparer.Ordinal);
        foreach (var record in mediaLocal)
        {
            var sync = SyncRecordMapping.ToSync(record, thisDevice);
            mediaStore[sync.Key] = sync;
            mediaByKey[sync.Key] = record;
        }
        var mediaOutcome = SyncMerge.Apply(mediaStore, file.UserMedia, _clock);

        outcome.Applied = progressOutcome.Applied + mediaOutcome.Applied;
        outcome.Ignored = progressOutcome.Ignored + mediaOutcome.Ignored;
        outcome.FarFuture = progressOutcome.FarFuture + mediaOutcome.FarFuture;
        if (outcome.Applied == 0) return outcome;

        _applying = true;
        try
        {
            if (progressOutcome.Applied > 0)
            {
                var replaced = new List<WatchProgress>();
                foreach (var record in progressLocal)
                {
                    if (!record.IsSyncable) replaced.Add(record); // local-only episodes stay
                }
                foreach (var (key, sync) in progressStore)
                {
                    if (progressOutcome.ChangedKeys.Contains(key))
                    {
                        var existing = progressByKey.GetValueOrDefault(key);
                        int? episodeId = existing is { TmdbId: > 0 } ? existing.TmdbId : null;
                        if (episodeId is null && sync.Type == "tv" && _episodeResolver is not null)
                        {
                            episodeId = _episodeResolver(sync.TmdbId, sync.Season ?? 0, sync.Episode ?? 0);
                        }
                        replaced.Add(SyncRecordMapping.FromSync(sync, episodeId));
                    }
                    else
                    {
                        replaced.Add(progressByKey[key]);
                    }
                }
                _progress.ReplaceAll(replaced);
            }
            if (mediaOutcome.Applied > 0)
            {
                var replaced = new List<UserMediaRecord>();
                var watchlistChanges = new List<UserMediaRecord>();
                foreach (var (key, sync) in mediaStore)
                {
                    if (mediaOutcome.ChangedKeys.Contains(key))
                    {
                        var existing = mediaByKey.GetValueOrDefault(key);
                        var record = SyncRecordMapping.FromSync(sync, existing);
                        replaced.Add(record);
                        if (existing is null ? record.Watchlist : existing.Watchlist != record.Watchlist) watchlistChanges.Add(record);
                    }
                    else
                    {
                        replaced.Add(mediaByKey[key]);
                    }
                }
                _media.ApplyMerged(replaced);
                ApplyWatchlistChanges(watchlistChanges);
            }
        }
        finally
        {
            _applying = false;
        }
        return outcome;
    }

    /// <summary>The watchlist store follows the merged flag; its own TMDB push carries it on.</summary>
    private void ApplyWatchlistChanges(List<UserMediaRecord> changes)
    {
        if (_watchlist is null) return;
        foreach (var record in changes)
        {
            var reference = new MediaRef { Id = record.TmdbId, MediaType = record.MediaType };
            if (_watchlist.IsInWatchlist(reference) == record.Watchlist) continue;
            var metadata = record.Title is null && record.PosterPath is null
                ? null
                : new WatchlistMetadata { Title = record.Title, PosterPath = record.PosterPath, PosterUrl = record.PosterUrl };
            _watchlist.SetWatchlist(record.Watchlist, reference, metadata);
        }
    }

    /// <summary>§5: this device's whole merged view, tombstones older than a year left out (§6.3).</summary>
    public SyncDeviceFile BuildOwnFile(string space)
    {
        var thisDevice = _state.DeviceId;
        var now = _clock.Now;
        var progress = new List<SyncProgressRecord>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var record in _progress.AllIncludingCleared)
        {
            if (SyncRecordMapping.ToSync(record, thisDevice) is not { } sync) continue;
            if (SyncMerge.IsExpiredTombstone(sync, now)) continue;
            if (seen.Add(sync.Key)) progress.Add(sync);
        }
        var media = new List<SyncUserMediaRecord>();
        seen.Clear();
        foreach (var record in _media.All)
        {
            var sync = SyncRecordMapping.ToSync(record, thisDevice);
            if (sync.UpdateTime == 0 && sync.IsTombstone) continue; // never had state, never stamped
            if (SyncMerge.IsExpiredTombstone(sync, now)) continue;
            if (seen.Add(sync.Key)) media.Add(sync);
        }
        return new SyncDeviceFile
        {
            Space = space,
            Device = new SyncDevice(thisDevice, _state.DeviceName, SyncFormat.WindowsPlatform, _appVersion),
            UpdateTime = SyncDeviceFile.HighestStamp(progress.Cast<SyncRecord>().Concat(media)),
            WatchProgress = progress,
            UserMedia = media,
        };
    }

    // ------------------------------------------------------------------
    // Watchlist mirror and replica import
    // ------------------------------------------------------------------

    /// <summary>
    /// Keeps the `watchlist` flag of the user-media records equal to the
    /// watchlist store. At first run the store's own times stamp the mirror
    /// (they are the time of the add); afterwards a change takes a fresh stamp.
    /// </summary>
    private void MirrorWatchlist(bool initial)
    {
        if (_watchlist is null) return;
        var inList = _watchlist.Items;
        var listed = new HashSet<string>(inList.Select(item => item.StorageKey), StringComparer.Ordinal);
        foreach (var item in inList)
        {
            _media.SetWatchlistMirror(item.TmdbId, item.MediaType, true, initial ? Math.Max(item.UpdatedAt, item.DateAdded) : null, item.Title, item.PosterPath);
        }
        foreach (var record in _media.All)
        {
            if (record.Watchlist && !listed.Contains(record.StorageKey))
            {
                _media.SetWatchlistMirror(record.TmdbId, record.MediaType, false);
            }
        }
    }

    private void ImportReplicaOnce()
    {
        if (_state.ReplicaImported) return;
        var directory = ReplicaDirectory;
        if (directory is not null && Directory.Exists(directory))
        {
            try
            {
                _applying = true;
                LastImport = ReplicaMigration.Import(directory, _progress, _media, _state.DeviceId);
            }
            catch (Exception error)
            {
                SyncLog.Error("migration", "import replica", error);
            }
            finally
            {
                _applying = false;
            }
        }
        _state.ReplicaImported = true;
    }

    /// <summary>Where the pre-27.1 replica lived; the app points this at %OneDrive%\Apps\Edendale.</summary>
    public string? ReplicaDirectory { get; init; } = AppPaths.CloudReplicaDirectory;

    /// <summary>What the import found at this launch, for the one-time notice.</summary>
    public ReplicaMigration.Outcome? LastImport { get; private set; }

    // ------------------------------------------------------------------
    // Warnings and status
    // ------------------------------------------------------------------

    private static string SkipWarning(SyncSkipReason reason, string? deviceName)
    {
        var name = string.IsNullOrEmpty(deviceName) ? AppText.Get("Sync_AnotherDevice") : deviceName;
        return reason switch
        {
            SyncSkipReason.NewerFormat => AppText.Format("Sync_WarningNewerFormat", name),
            SyncSkipReason.TooLarge => AppText.Format("Sync_WarningTooLarge", name),
            _ => AppText.Format("Sync_WarningSkipped", name),
        };
    }

    private void AddWarning(string warning)
    {
        lock (_gate)
        {
            if (_warnings.Contains(warning)) return;
            _warnings.Add(warning);
            if (_warnings.Count > 5) _warnings.RemoveAt(0);
        }
    }

    public void ClearWarnings()
    {
        lock (_gate) _warnings.Clear();
        RaiseStatusChanged();
    }

    private void RaiseStatusChanged() => StatusChanged?.Invoke(this, EventArgs.Empty);

    public void Dispose()
    {
        _writeDebounce?.Dispose();
        _periodic?.Dispose();
        DropCachedLocation();
    }
}
