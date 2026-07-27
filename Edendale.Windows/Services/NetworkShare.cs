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
            ?? throw new IOException("Windows could not start the network-share connector.");
        process.StandardInput.WriteLine(password);
        process.StandardInput.Close();
        if (!process.WaitForExit(15_000))
        {
            process.Kill(entireProcessTree: true);
            throw new IOException($"Connecting to {sharePath} timed out.");
        }
        if (process.ExitCode == 0 || Directory.Exists(sharePath)) return;

        throw new IOException(
            $"Could not connect to {sharePath}. Check the address and stored credentials.");
    }

    /// <summary>Best-effort reconnect using stored credentials; never throws.</summary>
    public static void TryConnect(string uncPath, SmbCredentialsStore credentials)
    {
        try
        {
            var host = SmbCredentialsStore.HostFromUncPath(uncPath);
            var share = SmbCredentialsStore.ShareFromUncPath(uncPath);
            if (host is null || share is null) return;
            if (credentials.Get(host) is not { } stored) return;
            Connect(share, stored.Username, stored.Password);
        }
        catch
        {
            // The scan itself reports unreachable folders.
        }
    }

}
