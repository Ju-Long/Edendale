// Launch-at-startup for the unpackaged app: an HKCU Run entry pointing at
// the current executable — the Windows analogue of Apple's SMAppService
// Launch at Login. Per-user, no elevation. A future packaged (MSIX) build
// would switch to StartupTask instead.

using Microsoft.Win32;

namespace Edendale.Windows.Services;

public static class StartupService
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "Edendale";

    private static string? ExecutablePath => Environment.ProcessPath;

    public static bool IsAvailable => ExecutablePath is not null;

    public static bool IsEnabled
    {
        get
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
                return key?.GetValue(ValueName) is string stored
                    && ExecutablePath is { } exe
                    && string.Equals(stored.Trim('"'), exe, StringComparison.OrdinalIgnoreCase);
            }
            catch
            {
                return false;
            }
        }
    }

    /// <summary>Returns false when the registry write failed.</summary>
    public static bool SetEnabled(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath);
            if (key is null) return false;
            if (enabled)
            {
                if (ExecutablePath is not { } exe) return false;
                key.SetValue(ValueName, $"\"{exe}\"");
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
            return true;
        }
        catch
        {
            return false;
        }
    }
}
