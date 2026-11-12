// The client procedure of SYNC-PROTOCOL.md §8 for Windows: when to sync
// (launch, foreground, 10 s after the last local change, at once when
// playback stops or the app closes, every 5 minutes), one sync (list the
// space, read the other devices' changed files, merge, write this device's
// file when anything changed or it went missing), failure backoff (1, 2, 4,
// 8 minutes, capped at an hour, with a retry when it ends), and the one-time
// import of the OneDrive replica (§8.4). A sync asked for while one runs
// runs once more after it, however many asked (apple-27.1 K.7). It never
// blocks playback or import, and a failed listing or read leaves the local
// stores untouched. WinUI-free, so the tests drive it with a folder location.

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
    private readonly Func<IReadOnlyDictionary<string, int>>? _libraryEpisodeIndex;
    private readonly string _appVersion;
    private readonly object _gate = new();
    private readonly List<string> _warnings = [];

    private Timer? _writeDebounce;
    private Timer? _periodic;
    private Timer? _retry;
    private bool _dirty;
    private volatile bool _applying;
    private bool _started;
    private bool _syncing;

    // One sync at a time (under _gate): a request while one runs sets _again,
    // and the run starts one more when it ends; _againDone completes then.
    private bool _running;
    private bool _again;
    private bool _againByUser;
    private TaskCompletionSource? _againDone;
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
        Func<IReadOnlyDictionary<string, int>>? libraryEpisodeIndex = null,
        string? appVersion = null)
    {
        _state = state;
        _clock = clock;
        _progress = progress;
        _media = media;
        _watchlist = watchlist;
        _locationFactory = locationFactory;
        _libraryEpisodeIndex = libraryEpisodeIndex;
        _appVersion = appVersion ?? "27.1";

        // K.7: a mark more than a day ahead, persisted while this PC's clock
        // ran ahead, would stamp every later write past what the other
        // devices accept. It comes down to what the stores really hold, and
        // this device's file is written again.
        var now = _clock.Now;
        if (SyncMerge.IsFarFuture(_state.HighWater, now))
        {
            _state.ResetHighWater(_clock.HighWater);
            SyncLog.Warn("sync", "high-water mark was more than a day ahead; lowered");
        }
        if (SyncMerge.IsFarFuture(_state.WrittenHighWater, now)) _state.WrittenHighWater = 0;
        _clock.Restore(_state.HighWater);
        _clock.HighWaterChanged += (_, _) => _state.HighWater = _clock.HighWater;

        // Local writes mark the device file stale from the first moment, whether
        // or not the launch procedure has run yet.
        _progress.Changed += (_, _) => OnStoreChanged();
        _media.Changed += (_, _) => OnStoreChanged();
        if (_watchlist is not null)
        {
            // Every change but one the merge itself set is mirrored, from
            // whichever thread it comes (a toggle during a merge included).
            _watchlist.Changed += (_, args) =>
            {
                if (!_started || args is WatchlistChangedEventArgs { FromSync: true }) return;
                MirrorWatchlist();
            };
        }
        _state.Changed += (_, _) => RaiseStatusChanged();
    }

    /// <summary>Status changes: a sync started or finished, the location changed, a warning appeared.</summary>
    public event EventHandler? StatusChanged;

    /// <summary>
    /// Tests and the UI can wait for the sync in flight, and for the one
    /// queued behind it when there is one.
    /// </summary>
    public Task? Current { get; private set; }

    /// <summary>
    /// Finds where a picked sync folder went when its path is gone: the app
    /// resolves the folder's future-access token (packaged builds only).
    /// Null when nothing can follow a moved folder.
    /// </summary>
    public Func<SyncLocationSettings, CancellationToken, Task<string?>>? FollowMovedFolder { get; init; }

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
    public string Describe(SyncLocationSettings location)
    {
        lock (_gate)
        {
            if (_cachedLocation is not null && _cachedSettings == location) return _cachedLocation.Description;
        }
        var target = _locationFactory(location);
        var description = target?.Description ?? location.Kind.ToString();
        (target as IDisposable)?.Dispose();
        return description;
    }

    // ------------------------------------------------------------------
    // Triggers (§8.1)
    // ------------------------------------------------------------------

    /// <summary>
    /// Launch: imports the replica once, reconciles the watchlist with the
    /// user-media records, syncs, and starts the 5-minute timer. Safe to
    /// call once.
    /// </summary>
    public void Start()
    {
        if (_started) return;
        _started = true;

        ImportReplicaOnce();
        ReconcileWatchlistAtLaunch();

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
        }
        if (!_state.IsOn || !NeedsWrite()) return Task.CompletedTask;
        return SyncAsync("write-now", userRequested: false);
    }

    /// <summary>Settings → Sync now: ignores the backoff.</summary>
    public Task SyncNowAsync() => SyncAsync("user", userRequested: true);

    /// <summary>
    /// A store changed: sync 10 seconds after the last change (§8.1). Whether
    /// the file is rewritten depends on the stamps: every local write raises
    /// the high-water mark, so a change that stamped nothing (an episode id
    /// matched to the library) costs a read and no write.
    /// </summary>
    private void OnStoreChanged()
    {
        if (_applying) return;
        lock (_gate)
        {
            if (!_started || !_state.IsOn) return;
            _writeDebounce?.Dispose();
            _writeDebounce = new Timer(_ => Trigger("local-change"), null, WriteDebounce, Timeout.InfiniteTimeSpan);
        }
    }

    /// <summary>
    /// Whether this device's file is behind its store: never written, a merge
    /// applied, a stamp since the last write, or a record held back as
    /// far-future whose time has come.
    /// </summary>
    private bool NeedsWrite()
    {
        lock (_gate)
        {
            var heldBack = _state.HeldBackUntil;
            return _dirty
                || _state.LastWriteMillis == 0
                || _clock.HighWater > _state.WrittenHighWater
                || (heldBack > 0 && _clock.Now >= heldBack);
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
    /// creating Edendale/ and the space folder, then starts the first sync
    /// without waiting for it (SyncCoordinator.setLocation → requestSync on
    /// apple-27.1). A handoff receiver answers its `result` right after this
    /// returns, and the sender waits only 2 minutes for it (§9.2), less than
    /// one sync may take; the sync's progress and any failure show in
    /// Settings → Sync through <see cref="StatusChanged"/>. The old location
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
            _retry?.Dispose();
            _retry = null;
        }
        SyncLog.Info("sync", $"location set: {location.Kind}");
        RaiseStatusChanged();
        // Not awaited: RunAsync records its own failure (Status.LastError), and Current tracks it.
        if (_started) _ = SyncAsync("location", userRequested: true);
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

    /// <summary>
    /// Starts a sync off the caller's thread, or, while one runs, queues one
    /// more after it (SyncCoordinator.requestSync on apple-27.1): however
    /// many ask meanwhile, one follow-up runs, as the user's when any of them
    /// was. During a backoff an automatic request is dropped instead, as it
    /// would be with nothing running. The task returned, and
    /// <see cref="Current"/>, complete when the sync that serves the request
    /// has.
    /// </summary>
    private Task SyncAsync(string reason, bool userRequested)
    {
        lock (_gate)
        {
            if (_running)
            {
                if (!userRequested && _clock.Now < _nextAllowedMillis) return Current ?? Task.CompletedTask;
                _again = true;
                _againByUser |= userRequested;
                _againDone ??= new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                return Current = _againDone.Task;
            }
            _running = true;
            return Current = Task.Run(() => RunThenFollowUpAsync(reason, userRequested));
        }
    }

    private async Task RunThenFollowUpAsync(string reason, bool userRequested)
    {
        try
        {
            await RunAsync(reason, userRequested).ConfigureAwait(false);
        }
        finally
        {
            TaskCompletionSource? done;
            bool again, byUser;
            lock (_gate)
            {
                (again, byUser, done) = (_again, _againByUser, _againDone);
                (_again, _againByUser, _againDone) = (false, false, null);
                _running = false;
            }
            // Only once this sync has let go: asking while it still ran would
            // just queue the request again and lose it.
            if (again)
            {
                var next = SyncAsync("queued", byUser);
                _ = next.ContinueWith(_ => done?.TrySetResult(), CancellationToken.None, TaskContinuationOptions.None, TaskScheduler.Default);
            }
            else
            {
                done?.TrySetResult();
            }
        }
    }

    private async Task RunAsync(string reason, bool userRequested)
    {
        if (_state.Location is not { } location) return;
        if (!userRequested && _clock.Now < _nextAllowedMillis) return;
        // A location chosen while this sync runs gets the queued sync; this
        // one records nothing against it (SetLocation raises the generation).
        var generation = _state.Generation;
        bool StillCurrent() => _state.Generation == generation;
        lock (_gate) _syncing = true;
        RaiseStatusChanged();
        var wrote = false;
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(3));
            var cancellation = timeout.Token;
            location = await FollowMovedFolderAsync(location, cancellation).ConfigureAwait(false);
            var target = LocationFor(location);
            if (target is null)
            {
                Fail(new ConnectorException(ConnectorFailure.SignInRequired, location.Kind.ToString()), "no login for the location");
                return;
            }

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
            if (target is IHasProviderIds ids && StillCurrent())
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
            var episodes = new SyncEpisodeIndex(_libraryEpisodeIndex);
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
                    // Windows refused to download a cloud placeholder (the sync
                    // app isn't running, downloads are blocked, no network):
                    // say so, since every sync will fail the same way.
                    if (error is IOException io && CloudFileErrors.IsCloudFileError(io.HResult))
                    {
                        AddWarning(AppText.Format("Sync_WarningCloudDownload", PeerName(entry.Name[..^5], null)));
                    }
                    continue; // The next trigger retries this file; the others still merge.
                }
                var result = SyncDeviceFileCodec.Decode(bytes, entry.Name, location.Space);
                var peerId = entry.Name[..^5];
                if (result.File is not { } file)
                {
                    var reason2 = result.Skipped ?? SyncSkipReason.Malformed;
                    SyncLog.Warn("sync", $"skipped {entry.Name}: {reason2}");
                    AddWarning(SkipWarning(reason2, result.DeviceName ?? PeerName(peerId, null)));
                    if (StillCurrent())
                    {
                        _state.RecordRead(entry.Name, entry.Stamp, new SyncPeer
                        {
                            Id = peerId,
                            Name = result.DeviceName ?? "",
                            LastReadMillis = _clock.Now,
                            Skipped = reason2,
                        });
                    }
                    continue;
                }
                var outcome = Apply(file, episodes);
                merged += outcome.Applied;
                if (outcome.FarFuture > 0) AddWarning(AppText.Format("Sync_WarningClockAhead", file.Device.Name));
                SyncLog.Info("sync", $"read {entry.Name}: applied {outcome.Applied}, ignored {outcome.Ignored}, far-future {outcome.FarFuture}, stamp {file.UpdateTime}");
                if (StillCurrent())
                {
                    _state.RecordRead(entry.Name, entry.Stamp, new SyncPeer
                    {
                        Id = file.Device.Id,
                        Name = file.Device.Name,
                        Platform = file.Device.Platform,
                        FileUpdateTime = file.UpdateTime,
                        LastReadMillis = _clock.Now,
                    });
                }
            }
            if (merged > 0)
            {
                lock (_gate) _dirty = true; // This device's merged view must go out (§7.1).
            }
            if (!StillCurrent())
            {
                SyncLog.Info("sync", $"location changed during the sync ({reason}); the next sync writes there");
                return;
            }

            // §8.2, §8.3: files gone from the listing take their read stamps
            // with them, and their devices leave Settings → Sync.
            _state.ForgetMissing([.. entries.Select(entry => entry.Name)]);

            // 3. Write this device's file when anything changed since the last
            //    write (a local edit, a merge above, a first sync, stamps
            //    applied since, a held-back record now due), or when it is gone
            //    from the listing: a device removed elsewhere that syncs again
            //    comes back (§8.3).
            var ownListed = entries.Any(entry => entry.Name == ownFile);
            var needsWrite = NeedsWrite();
            if (!ownListed && !needsWrite) SyncLog.Info("sync", "own file missing; rewriting");
            if (needsWrite || !ownListed)
            {
                var builtAt = _clock.HighWater;
                var file = BuildOwnFile(location.Space, out var heldBack, out var heldBackUntil);
                var bytes = SyncDeviceFileCodec.Encode(file);
                try
                {
                    await target.WriteAsync(location.Space, ownFile, bytes, cancellation).ConfigureAwait(false);
                    wrote = true;
                    lock (_gate) _dirty = false;
                    _state.WrittenHighWater = Math.Max(builtAt, file.UpdateTime);
                    _state.HeldBackUntil = heldBackUntil;
                    if (heldBack > 0) SyncLog.Info("sync", $"held back {heldBack} far-future records");
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
                _retry?.Dispose();
                _retry = null;
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
            RaiseStatusChanged();
        }
    }

    /// <summary>
    /// A picked folder whose path is gone may have been moved or renamed: the
    /// app's follower finds it through its future-access token, and the
    /// location follows it (Windows' analog of Apple's stale-bookmark refresh,
    /// K.7). The path stays out of the log.
    /// </summary>
    private async Task<SyncLocationSettings> FollowMovedFolderAsync(SyncLocationSettings location, CancellationToken cancellation)
    {
        if (FollowMovedFolder is null
            || location is not { Kind: SyncLocationKind.Folder, FutureAccessToken: { Length: > 0 }, Path: { Length: > 0 } path }
            || Directory.Exists(path))
        {
            return location;
        }
        try
        {
            var moved = await FollowMovedFolder(location, cancellation).ConfigureAwait(false);
            if (moved is null || string.Equals(moved, path, StringComparison.OrdinalIgnoreCase) || !Directory.Exists(moved)) return location;
            var followed = location with { Path = moved };
            _state.UpdateLocation(followed);
            DropCachedLocation();
            SyncLog.Info("sync", "sync folder moved; following it");
            return followed;
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            SyncLog.Error("sync", "follow moved folder", error);
            return location;
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
            // Try again when the backoff ends (SyncCoordinator.scheduleRetry),
            // rather than at the next 5-minute tick; a second past it, so the
            // retry isn't itself inside the backoff.
            _retry?.Dispose();
            _retry = new Timer(_ => Trigger("retry"), null, delay + TimeSpan.FromSeconds(1), Timeout.InfiniteTimeSpan);
        }
        SyncLog.Error("sync", context, error);
    }

    // ------------------------------------------------------------------
    // Merge in, build out
    // ------------------------------------------------------------------

    /// <summary>§7.1 against the stores, then the stores take the merged view.</summary>
    private SyncMergeOutcome Apply(SyncDeviceFile file, SyncEpisodeIndex episodes)
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
                        // The store's own id first, then the library's index,
                        // loaded once per sync and only when needed.
                        var existing = progressByKey.GetValueOrDefault(key);
                        int? episodeId = existing is { TmdbId: > 0 } ? existing.TmdbId : null;
                        if (episodeId is null && sync.Type == "tv") episodeId = episodes.Id(key);
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
            _watchlist.SetWatchlist(record.Watchlist, reference, metadata, fromSync: true);
        }
    }

    /// <summary>
    /// §5: this device's whole merged view, tombstones older than a year left
    /// out (§6.3), and so are records stamped more than a day ahead: every
    /// other device would refuse them. They stay in the stores and go out
    /// once the clock passes them (SyncLedger.records(now:), K.7).
    /// </summary>
    public SyncDeviceFile BuildOwnFile(string space) => BuildOwnFile(space, out _, out _);

    /// <param name="heldBack">How many far-future records were left out.</param>
    /// <param name="heldBackUntil">When the earliest of them may go out, or 0.</param>
    private SyncDeviceFile BuildOwnFile(string space, out int heldBack, out long heldBackUntil)
    {
        var thisDevice = _state.DeviceId;
        var now = _clock.Now;
        var held = 0;
        var earliestHeld = long.MaxValue;
        bool HoldBack(SyncRecord sync)
        {
            if (!SyncMerge.IsFarFuture(sync.UpdateTime, now)) return false;
            held++;
            earliestHeld = Math.Min(earliestHeld, sync.UpdateTime);
            return true;
        }

        var progress = new List<SyncProgressRecord>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var record in _progress.AllIncludingCleared)
        {
            if (SyncRecordMapping.ToSync(record, thisDevice) is not { } sync) continue;
            if (SyncMerge.IsExpiredTombstone(sync, now) || HoldBack(sync)) continue;
            if (seen.Add(sync.Key)) progress.Add(sync);
        }
        var media = new List<SyncUserMediaRecord>();
        seen.Clear();
        foreach (var record in _media.All)
        {
            var sync = SyncRecordMapping.ToSync(record, thisDevice);
            if (sync.UpdateTime == 0 && sync.IsTombstone) continue; // never had state, never stamped
            if (SyncMerge.IsExpiredTombstone(sync, now) || HoldBack(sync)) continue;
            if (seen.Add(sync.Key)) media.Add(sync);
        }
        heldBack = held;
        heldBackUntil = held > 0 ? earliestHeld - SyncFormat.FarFutureMillis : 0;
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
    /// watchlist store after a change there; a flag that changes takes a
    /// fresh stamp.
    /// </summary>
    private void MirrorWatchlist()
    {
        if (_watchlist is null) return;
        var inList = _watchlist.Items;
        var listed = new HashSet<string>(inList.Select(item => item.StorageKey), StringComparer.Ordinal);
        foreach (var item in inList)
        {
            _media.SetWatchlistMirror(item.TmdbId, item.MediaType, true, null, item.Title, item.PosterPath);
        }
        foreach (var record in _media.All)
        {
            if (record.Watchlist && !listed.Contains(record.StorageKey))
            {
                _media.SetWatchlistMirror(record.TmdbId, record.MediaType, false);
            }
        }
    }

    /// <summary>
    /// K.7 (UserMediaStore.reconcileWatchlist on apple-27.1), before the
    /// first sync: the records' watchlist flag and the watchlist store come
    /// into step without lowering a stamp and without stamping a flag this PC
    /// never changed, so the first sync can't take titles off other devices'
    /// watchlists. A flag another device stamped wins and the watchlist
    /// follows it (WatchlistLaunchReconcile has the rules). Marked done only
    /// once it has run, so a failure tries again at the next launch.
    /// </summary>
    private void ReconcileWatchlistAtLaunch()
    {
        if (_watchlist is null) return;
        try
        {
            var listed = _watchlist.Items
                .Select(item => new WatchlistListing(item.TmdbId, item.MediaType, Math.Max(item.UpdatedAt, item.DateAdded), item.Title, item.PosterPath))
                .ToList();
            var plan = WatchlistLaunchReconcile.Plan(
                _media.All, listed, _state.HasDeviceId ? _state.DeviceId : null, _clock.Now, firstRun: !_state.WatchlistSeeded);
            // The launch sync right after this writes whatever changed.
            _applying = true;
            try
            {
                if (plan.Seeds.Count > 0) _media.ApplyLaunchSeed(plan.Seeds);
                foreach (var fresh in plan.FreshStamps)
                {
                    _media.SetWatchlistMirror(fresh.TmdbId, fresh.MediaType, fresh.InWatchlist, null, fresh.Title, fresh.PosterPath);
                }
            }
            finally
            {
                _applying = false;
            }
            foreach (var follow in plan.WatchlistFollows)
            {
                var metadata = follow.Title is null && follow.PosterPath is null
                    ? null
                    : new WatchlistMetadata { Title = follow.Title, PosterPath = follow.PosterPath, PosterUrl = follow.PosterUrl };
                _watchlist.SetWatchlist(follow.InWatchlist, follow.Ref, metadata, fromSync: true);
            }
            _state.WatchlistSeeded = true;
            if (!plan.IsEmpty)
            {
                SyncLog.Info("sync", $"watchlist reconciled: {plan.Seeds.Count} seeded, {plan.FreshStamps.Count} restamped, {plan.WatchlistFollows.Count} followed");
            }
        }
        catch (Exception error)
        {
            SyncLog.Error("sync", "watchlist reconcile", error);
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

    /// <summary>A device's name for a warning: the file's own, the one last read, or "another device".</summary>
    private string PeerName(string deviceId, string? fromFile)
    {
        var name = string.IsNullOrEmpty(fromFile) ? _state.Peers.FirstOrDefault(peer => peer.Id == deviceId)?.Name : fromFile;
        return string.IsNullOrEmpty(name) ? AppText.Get("Sync_AnotherDevice") : name;
    }

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
        _retry?.Dispose();
        DropCachedLocation();
    }
}
