namespace Edendale.Windows.Services;

/// <summary>Local data locations for the unpackaged desktop app.</summary>
public static class AppPaths
{
    /// <summary>%LOCALAPPDATA%\Edendale — created on first use.</summary>
    public static string DataDirectory
    {
        get
        {
            var directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Edendale");
            Directory.CreateDirectory(directory);
            return directory;
        }
    }

    public static string LibraryFile => Path.Combine(DataDirectory, "library.json");
    public static string WatchProgressFile => Path.Combine(DataDirectory, "watch-progress.json");
    public static string UserMediaFile => Path.Combine(DataDirectory, "user-media.json");

    /// <summary>
    /// The local-first watchlist. Device-local like the library and,
    /// deliberately, outside the OneDrive replica — TMDB is the watchlist's
    /// only cloud (parity with Apple's local-only Watchlist container).
    /// </summary>
    public static string WatchlistFile => Path.Combine(DataDirectory, "watchlist.json");

    /// <summary>The Young Audience preference; device-local, never replicated.</summary>
    public static string AudiencePreferenceFile => Path.Combine(DataDirectory, "audience.json");

    /// <summary>
    /// Player, audio, picture, and subtitle preferences plus the per-title
    /// track memory. Device-local like the audience preference and outside
    /// the OneDrive replica (Apple keeps the same keys out of iCloud).
    /// </summary>
    public static string PlayerSettingsFile => Path.Combine(DataDirectory, "player-settings.json");

    /// <summary>DPAPI-protected TMDB session; never leaves this device.</summary>
    public static string TmdbSessionFile => Path.Combine(DataDirectory, "tmdb-session.bin");

    /// <summary>DPAPI-protected SMB credentials; never leaves this device.</summary>
    public static string SmbCredentialsFile => Path.Combine(DataDirectory, "smb-credentials.bin");

    /// <summary>DPAPI-protected SFTP, WebDAV, and S3 logins; never leaves this device (D11).</summary>
    public static string ServerLoginsFile => Path.Combine(DataDirectory, "server-logins.bin");

    /// <summary>
    /// DPAPI-protected OneDrive and Dropbox accounts (refresh tokens only;
    /// access tokens stay in memory). Never leaves this device (D11), and kept
    /// apart from the OneDrive watch-state replica (D12).
    /// </summary>
    public static string CloudAccountsFile => Path.Combine(DataDirectory, "cloud-accounts.bin");

    /// <summary>Pinned SSH host keys (trust on first use). Public keys, device-local.</summary>
    public static string HostKeysFile => Path.Combine(DataDirectory, "ssh-host-keys.json");

    /// <summary>
    /// Downloaded subtitle files, kept so re-selecting one costs nothing
    /// against the daily quota. Device-local, like the library itself, and
    /// deliberately outside the cloud replica.
    /// </summary>
    public static string SubtitleCacheDirectory
    {
        get
        {
            var directory = Path.Combine(DataDirectory, "Subtitles");
            Directory.CreateDirectory(directory);
            return directory;
        }
    }

    /// <summary>
    /// Cloud replica root inside the user's OneDrive (Windows' default cloud
    /// storage), or null when OneDrive is not set up on this machine. Not
    /// created here — CloudSyncService creates it when it starts replicating.
    /// </summary>
    public static string? CloudReplicaDirectory
    {
        get
        {
            var oneDrive = Environment.GetEnvironmentVariable("OneDrive")
                ?? Environment.GetEnvironmentVariable("OneDriveConsumer")
                ?? Environment.GetEnvironmentVariable("OneDriveCommercial");
            if (string.IsNullOrWhiteSpace(oneDrive) || !Directory.Exists(oneDrive)) return null;
            return Path.Combine(oneDrive, "Apps", "Edendale");
        }
    }
}
