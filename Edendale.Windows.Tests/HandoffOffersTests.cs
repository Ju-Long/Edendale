using Edendale.Windows.Core;
using Edendale.Windows.Core.Handoff;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Handoff;
using Edendale.Windows.Services.Remote;
using Edendale.Windows.Services.Sync;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// What a PC sends and keeps in a handoff (SYNC-PROTOCOL.md §9.6–§9.8,
/// FEATURES-27.1.md §6.1, §6.4): the ticked items, the Google ecosystem rule,
/// validation before anything is stored, and a whole exchange between two
/// PCs over loopback.
/// </summary>
[TestClass]
public sealed class HandoffOffersTests
{
    private sealed class PlainProtector : ISecretProtector
    {
        public byte[] Protect(byte[] data) => data;
        public byte[] Unprotect(byte[] data) => data;
    }

    private string _root = "";

    [TestInitialize]
    public void Setup()
    {
        _root = Path.Combine(Path.GetTempPath(), "edendale-offers-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(_root, recursive: true);

    private sealed class Device
    {
        public Device(string root, string name)
        {
            var folder = Path.Combine(root, name);
            Directory.CreateDirectory(folder);
            Stores = new HandoffStores
            {
                Sync = new SyncState(Path.Combine(folder, "sync-state.json")),
                Accounts = new CloudAccountVault(Path.Combine(folder, "accounts.bin"), new PlainProtector()),
                ServerLogins = new ServerLoginStore(Path.Combine(folder, "logins.bin"), new PlainProtector()),
                HostKeys = new HostKeyStore(Path.Combine(folder, "keys.json")),
                SmbLogins = () => Smb.ToList(),
                SaveSmbLogin = login => Smb.Add(login),
            };
        }

        public HandoffStores Stores { get; }
        public List<SmbCredentials> Smb { get; } = [];
        public List<SyncLocationSettings> LocationsSet { get; } = [];
        public List<string> Validated { get; } = [];

        public HandoffValidators Validators(Func<HandoffAccount, bool>? refuse = null) => new()
        {
            AdoptAccount = (account, _) =>
            {
                if (refuse?.Invoke(account) == true) throw new OAuthException(OAuthFailure.InvalidGrant, account.Kind);
                var cloud = new CloudAccount
                {
                    Kind = account.Kind, Subject = account.Subject, Email = account.Email, DisplayName = account.DisplayName,
                    RefreshToken = account.RefreshToken, DriveId = account.DriveId, Scopes = account.Scopes,
                };
                Stores.Accounts.Save(cloud);
                return Task.FromResult(cloud);
            },
            ValidateLogin = (login, address, _) =>
            {
                Validated.Add($"{login.Kind}:{login.Host}:{address}");
                return Task.CompletedTask;
            },
            SetLocation = (settings, _) =>
            {
                LocationsSet.Add(settings);
                Stores.Sync.SetLocation(settings);
                return Task.CompletedTask;
            },
        };
    }

    private static CloudAccount Cloud(MediaSourceKind kind, string subject, IReadOnlyList<string> scopes) => new()
    {
        Kind = kind.RawValue(), Subject = subject, Email = subject + "@example.com", DisplayName = subject,
        RefreshToken = "TOKEN-" + subject, Scopes = scopes, DriveId = kind == MediaSourceKind.OneDrive ? "drive-" + subject : null,
    };

    [TestMethod]
    public void Choices_ListEverything_AndMarkGoogleForOtherEcosystems()
    {
        var pc = new Device(_root, "pc");
        pc.Stores.Sync.SetLocation(new SyncLocationSettings { Kind = SyncLocationKind.Folder, Path = "C:\\Sync", Space = "Family" });
        pc.Stores.Accounts.Save(Cloud(MediaSourceKind.GoogleDrive, "g", CloudProviders.GoogleScopes));
        pc.Stores.Accounts.Save(Cloud(MediaSourceKind.Dropbox, "d", CloudProviders.DropboxScopes));
        pc.Stores.ServerLogins.Save(new ServerLogin { Kind = "webdav", Host = "nas.local", Username = "me", Password = "pw" });
        pc.Smb.Add(new SmbCredentials("FILESERVER", "me", "pw"));

        var forAndroid = HandoffOffers.Choices(pc.Stores, "android");
        CollectionAssert.AreEqual(new[] { "location", "account:dropbox:d", "account:gdrive:g", "login:webdav:nas.local", "login:smb:fileserver" },
            forAndroid.Select(item => item.Id).ToArray());
        Assert.IsFalse(forAndroid.Single(item => item.Id == "account:gdrive:g").Transfers);
        Assert.IsTrue(HandoffOffers.Choices(pc.Stores, "windows").All(item => item.Transfers));
    }

    [TestMethod]
    public void Build_GoogleLocation_CarriesTheTokenOnlyWithinWindows()
    {
        var pc = new Device(_root, "pc");
        var google = Cloud(MediaSourceKind.GoogleDrive, "g", CloudProviders.GoogleScopes);
        pc.Stores.Accounts.Save(google);
        pc.Stores.Sync.SetLocation(new SyncLocationSettings { Kind = SyncLocationKind.GoogleDrive, AccountKey = google.Key, Space = "Family", GoogleFolderId = "folder-9" });
        var ticked = new HashSet<string> { "location", "account:gdrive:g" };

        var toPc = HandoffOffers.Build(pc.Stores, ticked, "windows");
        Assert.AreEqual("googleDrive", toPc.Location!.Provider);
        Assert.AreEqual("TOKEN-g", toPc.Location.Account!.RefreshToken);
        Assert.AreEqual("folder-9", toPc.Location.GoogleFolderId);
        Assert.AreEqual(1, toPc.AccountList.Count);

        var toPhone = HandoffOffers.Build(pc.Stores, ticked, "android");
        Assert.IsNull(toPhone.Location!.Account, "no Google token across ecosystems");
        Assert.AreEqual("g@example.com", toPhone.Location.GoogleAccountHint);
        Assert.AreEqual("folder-9", toPhone.Location.GoogleFolderId);
        Assert.AreEqual(0, toPhone.AccountList.Count, "other Google accounts stay too");
        Assert.IsFalse(HandoffMessages.EncodeOffer(toPhone).AsSpan().IndexOf("TOKEN-g"u8) >= 0);

        var toUnknown = HandoffOffers.Build(pc.Stores, ticked, null);
        Assert.IsNull(toUnknown.Location!.Account, "a peer that names no platform is another ecosystem");
    }

    [TestMethod]
    public void Build_ServerLocations_CarryTheUrlAndLogin()
    {
        var pc = new Device(_root, "pc");
        pc.Stores.ServerLogins.Save(new ServerLogin { Kind = "sftp", Host = "nas.local", Username = "me", Password = "pw", Port = 2222 });
        pc.Stores.HostKeys.Pin("SHA256:approved", "nas.local", 2222);
        pc.Stores.Sync.SetLocation(new SyncLocationSettings { Kind = SyncLocationKind.Sftp, Path = "sftp://nas.local:2222/home/me/", Space = "Family" });

        var offer = HandoffOffers.Build(pc.Stores, new HashSet<string> { "location" }, "ios");
        Assert.AreEqual("sftp", offer.Location!.Provider);
        Assert.AreEqual("sftp://nas.local:2222/home/me/", offer.Location.Url);
        Assert.AreEqual("SHA256:approved", offer.Location.Login!.HostKeyFingerprint);
        Assert.AreEqual(2222, offer.Location.Login.Port);
        Assert.AreEqual("pw", offer.Location.Login.Password);

        var smb = new Device(_root, "smb");
        smb.Smb.Add(new SmbCredentials("nas", "me", "pw"));
        smb.Stores.Sync.SetLocation(new SyncLocationSettings { Kind = SyncLocationKind.Smb, Path = @"\\nas\media\sync", Space = "Family" });
        var smbOffer = HandoffOffers.Build(smb.Stores, new HashSet<string> { "location" }, "macos");
        Assert.AreEqual("smb", smbOffer.Location!.Provider);
        Assert.AreEqual("smb://nas/media/sync/", smbOffer.Location.Url);
        Assert.AreEqual("me", smbOffer.Location.Login!.User);

        var folder = new Device(_root, "folder");
        folder.Stores.Sync.SetLocation(new SyncLocationSettings { Kind = SyncLocationKind.Folder, Path = @"C:\Users\me\OneDrive\Apps", Space = "Family" });
        var folderOffer = HandoffOffers.Build(folder.Stores, new HashSet<string> { "location" }, "ios");
        Assert.AreEqual(new HandoffLocation("folder", "Family"), folderOffer.Location, "no path or login for a folder");
    }

    [TestMethod]
    public async Task Adopt_StoresWhatValidates_AndSetsTheLocation()
    {
        var pc = new Device(_root, "pc");
        var oneDrive = new HandoffAccount("onedrive", "ms-1", "me@outlook.com", "Me", "TOKEN-ms", CloudProviders.OneDriveScopes, "drive-1");
        var refused = new HandoffAccount("dropbox", "db-1", null, null, "TOKEN-db", CloudProviders.DropboxScopes, null);
        var offer = new HandoffOffer(true,
            new HandoffLocation("oneDrive", " Family ", oneDrive),
            [refused],
            [
                new HandoffLogin("webdav", "dav.example.com", null, "me", "pw", Addresses: ["davs://dav.example.com/dav/"]),
                new HandoffLogin("sftp", "nas.local", 22, "me", "pw"),
                new HandoffLogin("smb", "FILESERVER", null, "me", "pw"),
            ]);

        var adoption = await HandoffOffers.AdoptAsync(offer, pc.Stores, pc.Validators(refuse: account => account.Kind == "dropbox"), CancellationToken.None);

        CollectionAssert.AreEquivalent(new[] { "location", "login:webdav:dav.example.com", "login:smb:FILESERVER" }, adoption.Result.Stored.ToArray());
        CollectionAssert.AreEquivalent(new[] { "account:dropbox:db-1", "login:sftp:nas.local" }, adoption.Result.Failed.Select(f => f.Item).ToArray());
        Assert.IsTrue(adoption.Result.Failed.Single(f => f.Item == "login:sftp:nas.local").Reason.Contains("nas.local"), "an SFTP login without an approved key is refused");
        Assert.IsNull(pc.Stores.ServerLogins.Get(MediaSourceKind.Sftp, "nas.local"), "nothing is stored that failed");

        var location = pc.LocationsSet.Single();
        Assert.AreEqual(SyncLocationKind.OneDrive, location.Kind);
        Assert.AreEqual("Family", location.Space, "the space name is normalized");
        Assert.AreEqual(SourceUrl.AccountKey(MediaSourceKind.OneDrive, "ms-1"), location.AccountKey);
        Assert.IsNotNull(pc.Stores.Accounts.Account(MediaSourceKind.OneDrive, location.AccountKey!));
        Assert.AreEqual("pw", pc.Stores.ServerLogins.Get(MediaSourceKind.WebDav, "dav.example.com")!.Password);
        Assert.AreEqual("me", pc.Smb.Single().Username);
        CollectionAssert.Contains(pc.Validated, "webdav:dav.example.com:davs://dav.example.com/dav/");
    }

    [TestMethod]
    public async Task Adopt_ServerLocation_PinsTheSendersHostKey_AndUsesTheUrl()
    {
        var pc = new Device(_root, "pc");
        var offer = new HandoffOffer(true, new HandoffLocation("sftp", "Family",
            Login: new HandoffLogin("sftp", "nas.local", 2222, "me", "pw", "SHA256:approved"), Url: "sftp://nas.local:2222/home/me/"));
        var adoption = await HandoffOffers.AdoptAsync(offer, pc.Stores, pc.Validators(), CancellationToken.None);
        CollectionAssert.AreEqual(new[] { "location" }, adoption.Result.Stored.ToArray());
        Assert.AreEqual("SHA256:approved", pc.Stores.HostKeys.PinnedFingerprint("nas.local", 2222));
        Assert.AreEqual("sftp://nas.local:2222/home/me/", pc.LocationsSet.Single().Path);
        Assert.AreEqual(2222, pc.Stores.ServerLogins.Get(MediaSourceKind.Sftp, "nas.local")!.Port);

        var smb = new Device(_root, "smb");
        var smbOffer = new HandoffOffer(true, new HandoffLocation("smb", "Family", Login: new HandoffLogin("smb", "nas", null, "me", "pw"), Url: "smb://nas/media/sync/"));
        await HandoffOffers.AdoptAsync(smbOffer, smb.Stores, smb.Validators(), CancellationToken.None);
        Assert.AreEqual(@"\\nas\media\sync", smb.LocationsSet.Single().Path);
        CollectionAssert.Contains(smb.Validated, @"smb:nas:\\nas\media\sync");
    }

    [TestMethod]
    public async Task Adopt_FolderAndCrossEcosystemGoogle_LeaveAFollowUp()
    {
        var pc = new Device(_root, "pc");
        var folder = await HandoffOffers.AdoptAsync(new HandoffOffer(true, new HandoffLocation("icloudDrive", "Family")), pc.Stores, pc.Validators(), CancellationToken.None);
        Assert.AreEqual(new HandoffFollowUp.PickFolder("Family", "icloudDrive"), folder.FollowUp);
        Assert.AreEqual(0, pc.LocationsSet.Count);

        var google = await HandoffOffers.AdoptAsync(new HandoffOffer(true, new HandoffLocation("googleDrive", "Family", GoogleAccountHint: "person@example.com", GoogleFolderId: "1AbC")),
            pc.Stores, pc.Validators(), CancellationToken.None);
        Assert.AreEqual(new HandoffFollowUp.GoogleSignIn("person@example.com", "1AbC", "Family"), google.FollowUp);

        var unknown = await HandoffOffers.AdoptAsync(new HandoffOffer(true, new HandoffLocation("carrierPigeon", "Family")), pc.Stores, pc.Validators(), CancellationToken.None);
        Assert.AreEqual("location", unknown.Result.Failed.Single().Item);
        var badSpace = await HandoffOffers.AdoptAsync(new HandoffOffer(true, new HandoffLocation("folder", "a/b")), pc.Stores, pc.Validators(), CancellationToken.None);
        Assert.AreEqual("location", badSpace.Result.Failed.Single().Item);
    }

    [TestMethod]
    public async Task RefreshAndStore_ProvesTheToken_AndKeepsARotatedOne()
    {
        var vault = new CloudAccountVault(Path.Combine(_root, "vault.bin"), new PlainProtector());
        var tokens = new CloudTokenProvider(vault, configuration: _ => null);
        var stub = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object> { ["access_token"] = "access", ["refresh_token"] = "ROTATED", ["expires_in"] = 3600 }));
        var configuration = CloudProviders.Configuration(MediaSourceKind.OneDrive, "client")!;
        var account = new HandoffAccount("onedrive", "ms-1", "me@outlook.com", "Me", "ORIGINAL", CloudProviders.OneDriveScopes, "drive-1");

        var stored = await HandoffOffers.RefreshAndStoreAsync(account, vault, tokens, _ => configuration, stub.Client, CancellationToken.None);
        Assert.AreEqual("ROTATED", stored.RefreshToken);
        Assert.AreEqual("ROTATED", vault.Account(MediaSourceKind.OneDrive, stored.Key)!.RefreshToken);
        var form = stub.Requests.Single().Form;
        Assert.AreEqual("ORIGINAL", form["refresh_token"]);
        StringAssert.Contains(form["scope"], "Files.ReadWrite.AppFolder");

        var refusing = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object> { ["error"] = "invalid_grant" }, 400));
        var otherVault = new CloudAccountVault(Path.Combine(_root, "other.bin"), new PlainProtector());
        await Assert.ThrowsExceptionAsync<OAuthException>(() =>
            HandoffOffers.RefreshAndStoreAsync(account, otherVault, new CloudTokenProvider(otherVault), _ => configuration, refusing.Client, CancellationToken.None));
        Assert.AreEqual(0, otherVault.All.Count, "a refused token is never stored");
    }

    [TestMethod]
    public async Task AWholeHandoff_BetweenTwoPCs_MovesTheLocationAndLogins()
    {
        var sender = new Device(_root, "sender");
        var receiver = new Device(_root, "receiver");
        var dropbox = Cloud(MediaSourceKind.Dropbox, "d", CloudProviders.DropboxScopes);
        sender.Stores.Accounts.Save(dropbox);
        sender.Stores.Sync.SetLocation(new SyncLocationSettings { Kind = SyncLocationKind.Dropbox, AccountKey = dropbox.Key, Space = "Family" });
        sender.Stores.ServerLogins.Save(new ServerLogin { Kind = "webdav", Host = "nas.local", Username = "me", Password = "pw" });

        var (hostStream, guestStream, cleanup) = await HandoffSessionTests.ConnectAsync();
        using var _ = cleanup;
        using var pairing = new HandoffHostPairing("Receiving PC");
        HandoffAdoption? adoption = null;

        var host = HandoffSession.RunHostAsync(hostStream, pairing, new HandoffParty
        {
            DeviceName = "Receiving PC",
            Role = HandoffRole.Receiver,
            StoreOffer = async (offer, peer, cancellation) =>
            {
                adoption = await HandoffOffers.AdoptAsync(offer, receiver.Stores, receiver.Validators(), cancellation);
                return adoption.Result;
            },
        }, CancellationToken.None);
        var guest = HandoffSession.RunGuestAsync(guestStream, new HandoffGuestPairing.Qr(pairing.QrCode(null, null)), new HandoffParty
        {
            DeviceName = "Sending PC",
            Role = HandoffRole.Sender,
            ProvideOffer = (peer, _) => Task.FromResult(HandoffOffers.Build(sender.Stores,
                HandoffOffers.Choices(sender.Stores, peer.Platform).Where(item => item.Transfers).Select(item => item.Id).ToHashSet(), peer.Platform)),
        }, CancellationToken.None);
        var sent = await guest;
        await host;

        CollectionAssert.AreEquivalent(new[] { "location", "account:dropbox:d", "login:webdav:nas.local" }, sent.Result.Stored.ToArray());
        Assert.AreEqual(SyncLocationKind.Dropbox, receiver.Stores.Sync.Location!.Kind);
        Assert.AreEqual(dropbox.Key, receiver.Stores.Sync.Location.AccountKey);
        Assert.AreEqual("TOKEN-d", receiver.Stores.Accounts.Account(MediaSourceKind.Dropbox, dropbox.Key)!.RefreshToken);
        Assert.AreEqual("pw", receiver.Stores.ServerLogins.Get(MediaSourceKind.WebDav, "nas.local")!.Password);
        Assert.AreNotEqual(sender.Stores.Sync.DeviceId, receiver.Stores.Sync.DeviceId, "the device ID never travels");
    }
}
