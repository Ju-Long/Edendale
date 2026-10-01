namespace Edendale.Windows.Services;

/// <summary>Composition root for the app's singleton services.</summary>
public static class AppServices
{
    public static SmbCredentialsStore SmbCredentials { get; } = new();

    /// <summary>SFTP, WebDAV, and S3 logins (DPAPI, device-local).</summary>
    public static ServerLoginStore ServerLogins { get; } = new(AppPaths.ServerLoginsFile, DpapiProtector.CurrentUser);

    /// <summary>OneDrive and Dropbox accounts (DPAPI refresh tokens, device-local).</summary>
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
    public static WatchProgressStore WatchProgress { get; } = new();
    public static UserMediaStore UserMedia { get; } = new();
    public static PlayerSession Player { get; } = new();
    public static CloudSyncService CloudSync { get; } = new(UserMedia, WatchProgress);
    public static TmdbAccountService Account { get; } = new(UserMedia);

    /// <summary>Local-first watchlist; TMDB (not OneDrive) is its only cloud.</summary>
    public static WatchlistStore Watchlist { get; } = new(new WatchlistRemoteAdapter(Account), () => Account.IsConnected);

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

    /// <summary>TheIntroDB, reached only while skip prompts are switched on.</summary>
    public static IntroDbClient IntroDb { get; } = new();

    /// <summary>The skip-prompt state for the item on screen.</summary>
    public static SegmentPrompts SegmentPrompts { get; } =
        new(PlayerSettings, (request, cancellation) => IntroDb.SegmentsAsync(request, cancellation));

    private static bool _accountConnected;

    /// <summary>Launch-time side effects: OneDrive merge + TMDB account sync.</summary>
    public static void StartBackgroundSync()
    {
        CloudSync.Initialize();
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
    }
}
