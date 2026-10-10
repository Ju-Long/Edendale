// Establishes authenticated SMB sessions with Windows' built-in `net use`
// command so the app stays managed and scans run under user-supplied
// credentials instead of an anonymous/guest session.

using System.Diagnostics;

namespace Edendale.Windows.Services;

public static class NetworkShare
{
    /// <summary>
    /// Connects \\server\share with the given credentials (no drive letter,
    /// not persisted across reboots). Throws with a readable message when
    /// Windows refuses the logon.
    /// </summary>
    public static void Connect(string sharePath, string username, string password)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = "net.exe",
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
        };
        startInfo.ArgumentList.Add("use");
        startInfo.ArgumentList.Add(sharePath);
        startInfo.ArgumentList.Add("*");
        startInfo.ArgumentList.Add($"/user:{username}");
        startInfo.ArgumentList.Add("/persistent:no");

        using var process = Process.Start(startInfo)
            ?? throw new IOException(Loc.Get("Smb_ConnectorFailed"));
        process.StandardInput.WriteLine(password);
        process.StandardInput.Close();
        var errorOutput = process.StandardError.ReadToEndAsync();
        if (!process.WaitForExit(15_000))
        {
            process.Kill(entireProcessTree: true);
            throw new IOException(Loc.Format("Smb_Timeout", sharePath));
        }
        if (process.ExitCode == 0 || Directory.Exists(sharePath)) return;

        throw new NetworkShareException(
            Loc.Format("Smb_CheckAddress", sharePath),
            SystemErrorCode(errorOutput.Wait(1_000) ? errorOutput.Result : ""));
    }

    /// <summary>
    /// Re-establishes the share's session with its saved login before a scan.
    /// Never throws: the scan reports an unreachable folder itself, and this
    /// says whether the server refused the saved login, so the source can ask
    /// to sign in again instead of reading as offline.
    /// </summary>
    public static SmbReconnect TryConnect(string uncPath, SmbCredentialsStore credentials)
    {
        try
        {
            var host = SmbCredentialsStore.HostFromUncPath(uncPath);
            var share = SmbCredentialsStore.ShareFromUncPath(uncPath);
            if (host is null || share is null) return SmbReconnect.NoLogin;
            if (credentials.Get(host) is not { } stored) return SmbReconnect.NoLogin;
            Connect(share, stored.Username, stored.Password);
            return SmbReconnect.Connected;
        }
        catch (NetworkShareException failure) when (IsLoginRefusal(failure.SystemError))
        {
            return SmbReconnect.LoginRefused;
        }
        catch
        {
            return SmbReconnect.Failed;
        }
    }

    /// <summary>
    /// Win32 errors that mean the login, not the network, is the problem:
    /// access denied, a bad password or user, an expired or locked account.
    /// </summary>
    internal static bool IsLoginRefusal(int? error) =>
        error is 5 or 86 or 1326 or 1327 or 1328 or 1329 or 1330 or 1331 or 1907 or 1909;

    /// <summary>The N in net.exe's "System error N has occurred."</summary>
    internal static int? SystemErrorCode(string output)
    {
        var match = System.Text.RegularExpressions.Regex.Match(output, @"\b(\d{1,5})\b");
        return match.Success && int.TryParse(match.Groups[1].Value, out var code) ? code : null;
    }
}

public enum SmbReconnect
{
    /// <summary>No saved login for the share: Windows uses the signed-in user or a guest session.</summary>
    NoLogin,
    Connected,
    /// <summary>The server refused the saved username or password.</summary>
    LoginRefused,
    Failed,
}

/// <summary>A refused <c>net use</c>, carrying the Win32 error it printed.</summary>
public sealed class NetworkShareException(string message, int? systemError) : IOException(message)
{
    public int? SystemError { get; } = systemError;
}
