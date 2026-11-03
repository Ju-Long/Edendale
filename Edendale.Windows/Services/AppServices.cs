using System.Reflection;

namespace Edendale.Windows.Services;

/// <summary>Composition root for the app's singleton services.</summary>
public static class AppServices
{
    public static SmbCredentialsStore SmbCredentials { get; } = new();

    /// <summary>SFTP, WebDAV, and S3 logins (DPAPI, device-local).</summary>
    public static ServerLoginStore ServerLogins { get; } = new(AppPaths.ServerLoginsFile, DpapiProtector.CurrentUser);

    /// <summary>Google Drive, OneDrive, and Dropbox accounts (DPAPI refresh tokens, device-local).</summary>
    public static CloudAccountVault CloudAccounts { get; } = new(AppPaths.CloudAccountsFile, DpapiProtector.CurrentUser);

    /// <summary>Pinned SSH host keys for SFTP sources.</summary>
    public static HostKeyStore HostKeys { get; } = new(AppPaths.HostKeysFile);

    /// <summary>In-memory access tokens for linked cloud accounts, refreshed single-flight.</summary>
    public static Remote.CloudTokenProvider CloudTokens { get; } = new(CloudAccounts);

    /// <summary>What connectors and remote playback rebuild themselves from.</summary>
    public static Remote.ConnectorEnvironment Connectors { get; } = new(ServerLogins, CloudAccounts, CloudTokens, HostKeys);
    public static LibraryService Library { get; } = CreateLibrary();

    private static LibraryService CreateLibrary()
    {
        Remote.ConnectorFactory.NfsConnectors = Remote.NfsConnector.FromSource;
        return new LibraryService(SmbCredentials)
        {
            ConnectorFor = folder => Remote.ConnectorFactory.ForSource(folder.Path, folder.SourceKind, Connectors),
        };
    }
    /// <summary>
    /// 27.1 sync state (FEATURES-27.1.md §5): this device's sync ID, the
    /// location and space, and the stamps read. Device-local, outside every
    /// synced folder.
    /// </summary>
    public static Sync.SyncState SyncState { get; } = new();

    /// <summary>The monotonic stamp every local watch-state write takes (SYNC-PROTOCOL.md §7.2).</summary>
    public static Core.Sync.SyncClock SyncClock { get; } = new(highWater: SyncState.HighWater);

    /// <summary>Records written before sync was first turned on carry no device; the device file signs them.</summary>
    private static string? ThisSyncDevice() => SyncState.HasDeviceId ? SyncState.DeviceId : null;

    public static WatchProgressStore WatchProgress { get; } = new(clock: SyncClock, deviceId: ThisSyncDevice);
    public static UserMediaStore UserMedia { get; } = new(clock: SyncClock, deviceId: ThisSyncDevice);
    public static PlayerSession Player { get; } = new();
    public static TmdbAccountService Account { get; } = new(UserMedia);

    /// <summary>Local-first watchlist mirrored to TMDB; its flag also travels in the sync files.</summary>
    public static WatchlistStore Watchlist { get; } = new(new WatchlistRemoteAdapter(Account), () => Account.IsConnected);

    /// <summary>
    /// 27.1 file sync on storage the user owns (FEATURES-27.1.md §5): this
    /// device's file in Edendale/&lt;space&gt;/, merged with the others'. It
    /// replaces the OneDrive replica, which it imports once.
    /// </summary>
    public static Sync.SyncEngine FileSync { get; } = new(
        SyncState,
        SyncClock,
        WatchProgress,
        UserMedia,
        Watchlist,
        settings => Sync.SyncLocationFactory.Create(settings, Connectors, ConnectSmbAsync),
        ResolveEpisode,
        AppVersion);

    /// <summary>The app version a device file names (informational, §5).</summary>
    public static string AppVersion =>
        typeof(AppServices).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0]
        ?? typeof(AppServices).Assembly.GetName().Version?.ToString(3)
        ?? "27.1";

    /// <summary>An SMB sync location re-establishes its session with the saved login first.</summary>
    private static Task ConnectSmbAsync(string uncPath, CancellationToken cancellation) =>
        Task.Run(() => NetworkShare.TryConnect(uncPath, SmbCredentials), cancellation);

    /// <summary>
    /// An episode from another device arrives as show, season, and episode;
    /// the library knows its own TMDB id once the show is matched.
    /// </summary>
    private static int? ResolveEpisode(int showTmdbId, int season, int episode) =>
        Library.Shows
            .Where(show => show.TmdbId == showTmdbId)
            .SelectMany(show => show.Episodes)
            .FirstOrDefault(item => item.Season == season && item.Episode == episode && item.TmdbId is not null)?.TmdbId;

    /// <summary>App-wide PG / PG-13 audience preference and certification cache.</summary>
    public static YoungAudienceFilter YoungAudience { get; } = new();

    public static SubtitleService Subtitles { get; } = new();

    /// <summary>Device-local player preferences (player-settings.json), never replicated.</summary>
    public static PlayerSettingsStore PlayerSettings { get; } = new();

    /// <summary>Settings → App Controls: skip lengths and hold speeds.</summary>
    public static Core.PlayerControlPreferences Controls { get; } = new(PlayerSettings);

    /// <summary>Loop, Fit/Fill, skip prompts, and the per-title track memory.</summary>
    public static Core.PlayerPreferences PlayerPreferences { get; } = new(PlayerSettings);

    /// <summary>Settings → Audio Enhancement and the booster.</summary>
    public static Core.AudioEnhancement AudioEnhancement { get; } = new(PlayerSettings);

    /// <summary>Player Adjustments → Picture.</summary>
    public static Core.VideoAdjustments VideoAdjustments { get; } = new(PlayerSettings);

    /// <summary>Settings → Subtitles.</summary>
    public static Core.SubtitleAppearance SubtitleAppearance { get; } = new(PlayerSettings);

    /// <summary>Player Adjustments → Enhancement (Option A).</summary>
    public static Core.VideoEnhancementSettings VideoEnhancement { get; } = new(PlayerSettings);

    /// <summary>Downloaded subtitles kept per title, removed after a month unused.</summary>
    public static SavedSubtitleStore SavedSubtitles { get; } = new(settings: PlayerSettings);

    /// <summary>TheIntroDB, reached only while skip prompts are switched on.</summary>
    public static IntroDbClient IntroDb { get; } = new();

    /// <summary>The skip-prompt state for the item on screen.</summary>
    public static SegmentPrompts SegmentPrompts { get; } =
        new(PlayerSettings, (request, cancellation) => IntroDb.SegmentsAsync(request, cancellation));

    private static bool _accountConnected;

    /// <summary>
    /// 3.20 D1 at launch: Google Drive accounts and sources
    /// from before drive.file go. Runs at every launch, and returns whether
    /// anything went, which happens once, so the notice shows once.
    /// </summary>
    public static bool MigrateGoogleDriveToFileAccess()
    {
        var accounts = CloudAccounts.RemoveGoogleAccountsWithoutDriveFile();
        var sources = Library.RemoveGoogleDriveSourcesFromBeforeDriveFile();
        return accounts + sources > 0;
    }

    /// <summary>Launch-time side effects: file sync (with the one-time replica import) and TMDB account sync.</summary>
    public static void StartBackgroundSync()
    {
        FileSync.Start();
        // Episodes received before their show was matched pick up their ids as the library fills in.
        Library.Changed += (_, _) => WatchProgress.ResolveEpisodeIds(ResolveEpisode);
        Account.SyncOnLaunch();

        // The watchlist has its own cloud-priority sync: once at launch and
        // again whenever a sign-in completes. Favourites/ratings ride Account.
        _accountConnected = Account.IsConnected;
        Account.StateChanged += (_, _) =>
        {
            var connected = Account.IsConnected;
            if (connected && !_accountConnected) _ = Watchlist.SyncFromTMDBAsync();
            _accountConnected = connected;
        };
        _ = Watchlist.SyncFromTMDBAsync();

        // Subtitles unused for a month go; nothing is playing yet.
        SavedSubtitles.PruneInBackground();
    }
}
