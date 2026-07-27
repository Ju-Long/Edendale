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

    /// <summary>DPAPI-protected TMDB session; never leaves this device.</summary>
    public static string TmdbSessionFile => Path.Combine(DataDirectory, "tmdb-session.bin");

    /// <summary>DPAPI-protected SMB credentials; never leaves this device.</summary>
    public static string SmbCredentialsFile => Path.Combine(DataDirectory, "smb-credentials.bin");

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
