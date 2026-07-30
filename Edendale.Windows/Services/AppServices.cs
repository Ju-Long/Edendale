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
    public static SubtitleService Subtitles { get; } = new();

    /// <summary>Launch-time side effects: OneDrive merge + TMDB account sync.</summary>
    public static void StartBackgroundSync()
    {
        CloudSync.Initialize();
        Account.SyncOnLaunch();
    }
}
