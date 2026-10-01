using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

/// <summary>
/// Every saved login and account across the per-device stores (DIFF.md
/// §3.12, D11: logins stay on this device and never enter the OneDrive
/// replica). Passwords and tokens never leave their stores; this lists only
/// what a row shows.
/// </summary>
public static class SavedLogins
{
    public static IReadOnlyList<SavedLogin> All()
    {
        var logins = new List<SavedLogin>();
        foreach (var (host, username) in AppServices.SmbCredentials.Logins)
        {
            logins.Add(new SavedLogin(MediaSourceKind.Smb, host, host, username));
        }
        logins.AddRange(AppServices.ServerLogins.Logins);
        logins.AddRange(AppServices.CloudAccounts.Logins);
        return logins
            .OrderBy(login => login.Kind.DisplayName(), StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(login => login.Title, StringComparer.CurrentCultureIgnoreCase)
            .ToList();
    }

    /// <summary>Forgets a login. Sources that used it ask to sign in again at their next scan.</summary>
    public static void Forget(SavedLogin login)
    {
        switch (login.Kind)
        {
            case MediaSourceKind.Smb:
                AppServices.SmbCredentials.Remove(login.Key);
                break;
            case MediaSourceKind.GoogleDrive or MediaSourceKind.OneDrive or MediaSourceKind.Dropbox:
                AppServices.CloudAccounts.Remove(login.Kind, login.Key);
                break;
            default:
                AppServices.ServerLogins.Remove(login.Kind, login.Key);
                break;
        }
    }
}
