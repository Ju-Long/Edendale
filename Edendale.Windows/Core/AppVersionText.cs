namespace Edendale.Windows.Core;

/// <summary>
/// The version Settings → About shows. Apple's AboutSettingsSection shows the
/// marketing version (CFBundleShortVersionString, SettingsView.swift). Windows
/// reads the app assembly's informational version: Directory.Build.props makes
/// it VersionPrefix in a local build and VersionPrefix+commit in a release,
/// where release.yml passes the 7-character SourceRevisionId. AssemblyVersion
/// can't stand in, because it is pinned to the major version (27.0.0.0).
/// </summary>
public static class AppVersionText
{
    /// <summary>
    /// "27.1.0+5780275" reads "27.1.0 (5780275)", so a bug report carries the
    /// commit, and "27.1.0" reads as it is. Null when there is no version, for
    /// the caller's "development build".
    /// </summary>
    public static string? Display(string? informationalVersion)
    {
        if (string.IsNullOrWhiteSpace(informationalVersion)) return null;
        var parts = informationalVersion.Split('+', 2);
        var version = parts[0].Trim();
        if (version.Length == 0) return null;
        var build = parts.Length > 1 ? parts[1].Trim() : "";
        return build.Length == 0 ? version : $"{version} ({build})";
    }
}
