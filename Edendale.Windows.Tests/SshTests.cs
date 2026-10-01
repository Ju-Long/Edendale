using Edendale.Windows.Core;
using Edendale.Windows.Services.Remote;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Renci.SshNet;

namespace Edendale.Windows.Tests;

/// <summary>
/// SFTP trust on first use and the algorithms offered (DIFF.md §3.12): host
/// key fingerprints match <c>ssh-keygen -l</c> (Apple's SFTPProtocolTests
/// vectors), and SHA-1, CBC, 3DES, and DSA are never offered.
/// </summary>
[TestClass]
public sealed class SshTests
{
    [TestMethod]
    public void FingerprintsMatchSshKeygen()
    {
        Assert.AreEqual("SHA256:zlzog2w4EauQF30MI712Zx+Y/3R5V2fDgoiWSU2h/t0", SshFingerprint.FromOpenSshPublicKey(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIK6QWabgywKnlWMa6NnGVZCaWfP0glCGC4V0W/8HlsTr edendale-test"));
        Assert.AreEqual("SHA256:TKLRif70yHWH3oK7nqHIfFl2geOpqjP96zzTUpSSJsA", SshFingerprint.FromOpenSshPublicKey(
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBCAaz8Es2+hJ8igM3+ORqT4R02ofHWyhbnQmCuku8wCTB5X67m8DLyyqkE1onLjChUZC9864xxekJWFAA3NiaP0="));
        Assert.IsNull(SshFingerprint.FromOpenSshPublicKey("ssh-ed25519"));
        Assert.AreEqual("ED25519", new SshHostKey("ssh-ed25519", "").TypeName);
        Assert.AreEqual("ECDSA", new SshHostKey("ecdsa-sha2-nistp384", "").TypeName);
        Assert.AreEqual("RSA", new SshHostKey("rsa-sha2-512", "").TypeName);
    }

    [TestMethod]
    public void OffersOnlyModernAlgorithms()
    {
        var info = new ConnectionInfo("nas.local", 22, "me", new PasswordAuthenticationMethod("me", "pw"));
        SftpConnector.Harden(info);

        var kex = info.KeyExchangeAlgorithms.Keys.ToList();
        CollectionAssert.Contains(kex, "curve25519-sha256");
        CollectionAssert.Contains(kex, "ecdh-sha2-nistp256");
        Assert.IsFalse(kex.Any(name => name.Contains("sha1")));
        Assert.IsFalse(kex.Contains("diffie-hellman-group1-sha1"));

        var hostKeys = info.HostKeyAlgorithms.Keys.ToList();
        CollectionAssert.Contains(hostKeys, "ssh-ed25519");
        CollectionAssert.Contains(hostKeys, "ecdsa-sha2-nistp256");
        Assert.IsFalse(hostKeys.Any(name => name.StartsWith("ssh-dss") || name.StartsWith("ssh-rsa")));

        var ciphers = info.Encryptions.Keys.ToList();
        CollectionAssert.Contains(ciphers, "aes256-gcm@openssh.com");
        Assert.IsFalse(ciphers.Any(name => name.EndsWith("-cbc")));

        Assert.IsFalse(info.HmacAlgorithms.Keys.Any(name => name.StartsWith("hmac-sha1")));
    }

    [TestMethod]
    public void SftpUrlsKeepTheirPortAndPath()
    {
        var keys = new Edendale.Windows.Services.HostKeyStore(Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N") + ".json"));
        var login = new Edendale.Windows.Services.ServerLogin { Kind = "sftp", Host = "nas.local", Username = "me", Password = "pw" };
        var standard = new SftpConnector("nas.local", 22, login, keys, "/home/me/Films & TV");
        Assert.AreEqual("sftp://nas.local/home/me/Films%20&%20TV/", standard.Root);
        Assert.AreEqual("sftp://nas.local/home/me/Heat%20%231.mkv", standard.UrlFor("/home/me/Heat #1.mkv", isDirectory: false));
        Assert.AreEqual("/home/me/Heat #1.mkv", SftpConnector.PathOf("sftp://nas.local/home/me/Heat%20%231.mkv"));
        var custom = new SftpConnector("nas.local", 2222, login, keys, "/");
        Assert.AreEqual("sftp://nas.local:2222/", custom.Root);
    }
}
