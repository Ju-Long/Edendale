using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The device-local login stores behind Settings → Accounts (DIFF.md §3.12),
/// after Apple's ConnectorFactoryTests store cases. A pass-through protector
/// stands in for DPAPI, which only exists on Windows.
/// </summary>
[TestClass]
public sealed class SecretStoresTests
{
    private sealed class PassThrough : ISecretProtector
    {
        public int Protected;
        public byte[] Protect(byte[] data) { Protected++; return data.Reverse().ToArray(); }
        public byte[] Unprotect(byte[] data) => data.Reverse().ToArray();
    }

    private string _directory = "";

    [TestInitialize]
    public void Setup()
    {
        _directory = Path.Combine(Path.GetTempPath(), "edendale-secrets-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(_directory, recursive: true);

    private string FileNamed(string name) => Path.Combine(_directory, name);

    [TestMethod]
    public void StoresLoginsPerKindAndHost()
    {
        var protector = new PassThrough();
        var store = new ServerLoginStore(FileNamed("server-logins.bin"), protector);
        store.Save(new ServerLogin { Kind = "webdav", Host = "NAS.local", Username = "b", Password = "2" });
        store.Save(new ServerLogin { Kind = "sftp", Host = "nas.local", Username = "c", Password = "3", Port = 2222 });
        var s3 = new S3Configuration("https://s3.example.com", "auto", "films", UsesPathStyle: true);
        store.Save(new ServerLogin { Kind = "s3", Host = "acct", Username = "AKID", Password = "secret", S3 = s3 });

        Assert.AreEqual("b", store.Get(MediaSourceKind.WebDav, "nas.local")?.Username);
        Assert.AreEqual("c", store.Get(MediaSourceKind.Sftp, "NAS.LOCAL")?.Username);
        Assert.AreEqual(s3, store.Get(MediaSourceKind.S3, "acct")?.S3);
        Assert.IsNull(store.Get(MediaSourceKind.WebDav, "other"));
        Assert.IsTrue(protector.Protected > 0);

        // The listing never exposes a password or secret key.
        var logins = store.Logins;
        Assert.IsTrue(logins.All(login => login.Title != "2" && login.Detail != "2" && login.Detail != "secret"));
        Assert.AreEqual("films @ s3.example.com", logins.Single(login => login.Kind == MediaSourceKind.S3).Title);
        Assert.AreEqual("nas.local:2222", logins.Single(login => login.Kind == MediaSourceKind.Sftp).Title);

        // A fresh store reads back what was saved, through the protector.
        var reopened = new ServerLoginStore(FileNamed("server-logins.bin"), new PassThrough());
        Assert.AreEqual("3", reopened.Get(MediaSourceKind.Sftp, "nas.local")?.Password);
        Assert.AreEqual(2222, reopened.Get(MediaSourceKind.Sftp, "nas.local")?.Port);

        reopened.Remove(MediaSourceKind.WebDav, "nas.local");
        Assert.IsNull(reopened.Get(MediaSourceKind.WebDav, "nas.local"));
        Assert.IsNotNull(reopened.Get(MediaSourceKind.Sftp, "nas.local"));
    }

    [TestMethod]
    public void GuestLoginsSayGuest()
    {
        var store = new ServerLoginStore(FileNamed("logins.bin"), new PassThrough());
        store.Save(new ServerLogin { Kind = "webdav", Host = "cloud.example.com" });
        Assert.IsTrue(store.Get(MediaSourceKind.WebDav, "cloud.example.com")!.IsGuest);
        Assert.AreEqual("Guest", store.Logins.Single().Detail);
    }

    [TestMethod]
    public void AnUnreadableStoreStartsEmpty()
    {
        File.WriteAllText(FileNamed("broken.bin"), "not json at all");
        Assert.AreEqual(0, new ServerLoginStore(FileNamed("broken.bin"), new PassThrough()).Logins.Count);
        Assert.AreEqual(0, new CloudAccountVault(FileNamed("missing.bin"), new PassThrough()).All.Count);
    }

    [TestMethod]
    public void VaultsAccountsByKindAndKey()
    {
        var vault = new CloudAccountVault(FileNamed("cloud-accounts.bin"), new PassThrough());
        var oneDrive = new CloudAccount { Kind = "onedrive", Subject = "s1", Email = "b@example.com", RefreshToken = "r1" };
        var dropbox = new CloudAccount { Kind = "dropbox", Subject = "s1", Email = "a@example.com", RefreshToken = "r2" };
        vault.Save(oneDrive);
        vault.Save(dropbox);
        CollectionAssert.AreEqual(new[] { MediaSourceKind.Dropbox, MediaSourceKind.OneDrive }, vault.All.Select(a => a.SourceKind).ToArray());
        CollectionAssert.AreEqual(new[] { "r1" }, vault.AccountsOf(MediaSourceKind.OneDrive).Select(a => a.RefreshToken).ToArray());
        // The same subject on another provider is another account.
        Assert.IsNull(vault.Account(MediaSourceKind.Dropbox, oneDrive.Key));
        Assert.AreEqual(SourceUrl.AccountKey(MediaSourceKind.OneDrive, "s1"), oneDrive.Key);

        vault.UpdateRefreshToken(MediaSourceKind.OneDrive, oneDrive.Key, "r1b");
        Assert.AreEqual("r1b", new CloudAccountVault(FileNamed("cloud-accounts.bin"), new PassThrough())
            .Account(MediaSourceKind.OneDrive, oneDrive.Key)?.RefreshToken);

        // Rows show the email, never a token.
        Assert.IsTrue(vault.Logins.All(login => login.Title.Contains('@') && login.Detail is null));

        vault.Remove(MediaSourceKind.OneDrive, oneDrive.Key);
        CollectionAssert.AreEqual(new[] { MediaSourceKind.Dropbox }, vault.All.Select(a => a.SourceKind).ToArray());
    }

    [TestMethod]
    public void PinsHostKeysPerHostAndPort()
    {
        var keys = new HostKeyStore(FileNamed("ssh-host-keys.json"));
        keys.Pin("SHA256:abc", "NAS.local", 22);
        Assert.AreEqual("SHA256:abc", keys.PinnedFingerprint("nas.local", 22));
        Assert.IsNull(keys.PinnedFingerprint("nas.local", 2222));
        Assert.AreEqual("SHA256:abc", new HostKeyStore(FileNamed("ssh-host-keys.json")).PinnedFingerprint("nas.local", 22));
        keys.Remove("nas.local", 22);
        Assert.IsNull(keys.PinnedFingerprint("nas.local", 22));
    }

    [TestMethod]
    public void UncAddressesNormalize()
    {
        Assert.AreEqual(@"\\nas\Media\Films", SmbCredentialsStore.NormalizeUncPath(@"  \\nas\Media\Films\ "));
        Assert.AreEqual(@"\\nas\Media\Films", SmbCredentialsStore.NormalizeUncPath("smb://nas/Media/Films/"));
        Assert.AreEqual(@"\\nas\Media\Films & TV", SmbCredentialsStore.NormalizeUncPath("smb://nas/Media/Films%20&%20TV"));
        Assert.AreEqual(@"\\nas\Media", SmbCredentialsStore.NormalizeUncPath("//nas/Media"));
        Assert.IsNull(SmbCredentialsStore.NormalizeUncPath(@"\\nas"));
        Assert.IsNull(SmbCredentialsStore.NormalizeUncPath(@"C:\Movies"));
        Assert.IsNull(SmbCredentialsStore.NormalizeUncPath("smb://me:pw@nas/Media"));
    }
}
