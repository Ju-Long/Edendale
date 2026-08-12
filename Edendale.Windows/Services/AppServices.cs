namespace Edendale.Windows.Services;

/// <summary>Composition root for the app's singleton services.</summary>
public static class AppServices
{
    public static SmbCredentialsStore SmbCredentials { get; } = new();
    public static LibraryService Library { get; } = new(SmbCredentials);
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
