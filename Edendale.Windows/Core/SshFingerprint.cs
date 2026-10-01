using System.Security.Cryptography;

namespace Edendale.Windows.Core;

/// <summary>
/// SSH host-key fingerprints as <c>ssh-keygen -l</c> prints them
/// ("SHA256:" and unpadded base64 of the key blob's SHA-256), and the key
/// type the trust-on-first-use prompt names (DIFF.md §3.12).
/// </summary>
public static class SshFingerprint
{
    public static string FromKeyBlob(byte[] blob) =>
        "SHA256:" + Convert.ToBase64String(SHA256.HashData(blob)).TrimEnd('=');

    /// <summary>The fingerprint of an OpenSSH public key line ("ssh-ed25519 AAAA… comment"), or null.</summary>
    public static string? FromOpenSshPublicKey(string line)
    {
        var parts = line.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 2) return null;
        try
        {
            return FromKeyBlob(Convert.FromBase64String(parts[1]));
        }
        catch (FormatException)
        {
            return null;
        }
    }

    /// <summary>"ED25519", "ECDSA", or "RSA", as ssh-keygen labels the key.</summary>
    public static string TypeName(string algorithm)
    {
        var name = algorithm.ToLowerInvariant();
        if (name.Contains("ed25519")) return "ED25519";
        if (name.Contains("ecdsa")) return "ECDSA";
        if (name.Contains("rsa")) return "RSA";
        if (name.Contains("dss")) return "DSA";
        return algorithm.ToUpperInvariant();
    }
}
