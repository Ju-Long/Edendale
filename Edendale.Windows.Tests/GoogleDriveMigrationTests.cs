using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Remote;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The move from drive.readonly to drive.file (ENHANCEMENT.md 3.20), ported
/// from Apple's GoogleDriveMigrationTests. 3.20 D1: at launch, Google accounts
/// without drive.file and every Google Drive folder source go, once, without
/// revoking anything; drive.file accounts, "Videos you chose" sources, and
/// other providers stay. 3.20 D2: a removed Drive video's id stays on its source,
/// on this PC only, so rescans skip it until it is picked again. Temporary
/// files only; no network.
/// </summary>
[TestClass]
public sealed class GoogleDriveMigrationTests
{
    private sealed class PlainProtector : ISecretProtector
    {
        public byte[] Protect(byte[] data) => data;
        public byte[] Unprotect(byte[] data) => data;
    }

    private static readonly string[] OldScopes = ["openid", "email", "https://www.googleapis.com/auth/drive.readonly"];

    private string _directory = "";

    [TestInitialize]
    public void Setup()
    {
        _directory = Path.Combine(Path.GetTempPath(), "edendale-drive-file-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(_directory, recursive: true);

    private static CloudAccount Account(MediaSourceKind kind, string subject, IReadOnlyList<string>? scopes) => new()
    {
        Kind = kind.RawValue(),
        Subject = subject,
        Email = $"{subject}@example.com",
        RefreshToken = "refresh-" + subject,
        Scopes = scopes,
        DriveId = kind == MediaSourceKind.OneDrive ? "drive-1" : null,
    };

    private static LibraryFolder Folder(string path, MediaSourceKind kind) => new()
    {
        Path = path,
        Name = path,
        Kind = kind.RawValue(),
        AccountKey = SourceUrl.CredentialHost(path),
    };

    // ------------------------------------------------------------------
    // 3.20 D1: accounts
    // ------------------------------------------------------------------

    [TestMethod]
    public void RemovesGoogleAccountsWithoutDriveFileOnce()
    {
        var path = Path.Combine(_directory, "cloud-accounts.bin");
        var vault = new CloudAccountVault(path, new PlainProtector());
        // Builds before drive.file stored no scopes at all.
        var unscoped = Account(MediaSourceKind.GoogleDrive, "110169484474386276334", null);
        var readOnly = Account(MediaSourceKind.GoogleDrive, "sub-readonly", OldScopes);
        var current = Account(MediaSourceKind.GoogleDrive, "08174361937620587386", CloudProviders.GoogleScopes);
        var oneDrive = Account(MediaSourceKind.OneDrive, "user-1", null);
        var dropbox = Account(MediaSourceKind.Dropbox, "dbid", CloudProviders.DropboxScopes);
        foreach (var account in new[] { unscoped, readOnly, current, oneDrive, dropbox }) vault.Save(account);

        Assert.IsTrue(unscoped.IsFromBeforeDriveFile);
        Assert.IsTrue(readOnly.IsFromBeforeDriveFile);
        Assert.IsFalse(current.IsFromBeforeDriveFile);
        // Other providers never count, scopes or not.
        Assert.IsFalse(oneDrive.IsFromBeforeDriveFile);
        Assert.IsFalse(dropbox.IsFromBeforeDriveFile);

        var changes = 0;
        vault.Changed += (_, _) => changes++;
        Assert.AreEqual(2, vault.RemoveGoogleAccountsWithoutDriveFile());
        Assert.AreEqual(1, changes);
        CollectionAssert.AreEquivalent(new[] { current.Key, oneDrive.Key, dropbox.Key }, vault.All.Select(a => a.Key).ToArray());

        // Saved: a relaunch has nothing left to remove, so the notice shows once.
        var reopened = new CloudAccountVault(path, new PlainProtector());
        CollectionAssert.AreEquivalent(new[] { current.Key, oneDrive.Key, dropbox.Key }, reopened.All.Select(a => a.Key).ToArray());
        Assert.AreEqual(0, reopened.RemoveGoogleAccountsWithoutDriveFile());
        Assert.AreEqual(0, vault.RemoveGoogleAccountsWithoutDriveFile());
        Assert.AreEqual(1, changes);
    }

    [TestMethod]
    public void GrantedScopesSurviveTheVault()
    {
        var path = Path.Combine(_directory, "cloud-accounts.bin");
        new CloudAccountVault(path, new PlainProtector()).Save(Account(MediaSourceKind.GoogleDrive, "08174361937620587386", CloudProviders.GoogleScopes));
        var stored = new CloudAccountVault(path, new PlainProtector()).AccountsOf(MediaSourceKind.GoogleDrive).Single();
        CollectionAssert.AreEqual(new[] { "https://www.googleapis.com/auth/drive.file" }, stored.Scopes!.ToArray());
        Assert.IsFalse(stored.IsFromBeforeDriveFile);
    }

    [TestMethod]
    public void ChooseMoreVideosReplacesTheStoredTokenWithoutAddingAnAccount()
    {
        var vault = new CloudAccountVault(Path.Combine(_directory, "cloud-accounts.bin"), new PlainProtector());
        var account = Account(MediaSourceKind.GoogleDrive, "08174361937620587386", CloudProviders.GoogleScopes);
        vault.Save(account);
        var updated = CloudProviders.ChosenVideosAccount(account,
            new OAuthTokenResponse { AccessToken = "a", RefreshToken = "refresh-newest", Scope = CloudProviders.GoogleDriveScope },
            new CloudProviders.Identity(account.Subject, account.Email, null));
        vault.Save(updated);
        var stored = vault.AccountsOf(MediaSourceKind.GoogleDrive).Single();
        Assert.AreEqual("refresh-newest", stored.RefreshToken);
        Assert.AreEqual(account.Key, stored.Key);
    }

    // ------------------------------------------------------------------
    // 3.20 D1: sources
    // ------------------------------------------------------------------

    [TestMethod]
    public void OnlyGoogleDriveFolderSourcesAreFromBeforeDriveFile()
    {
        var key = SourceUrl.AccountKey(MediaSourceKind.GoogleDrive, "110169484474386276334");
        // The old account's folders, including one in a shared drive.
        Assert.IsTrue(Folder(SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, key, ["folder-1"], "Films"), MediaSourceKind.GoogleDrive).IsFromBeforeDriveFile);
        Assert.IsTrue(Folder(SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, key, ["root"], "My Drive"), MediaSourceKind.GoogleDrive).IsFromBeforeDriveFile);
        Assert.IsTrue(Folder(SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, key, ["team1"], "Team Films",
            [new KeyValuePair<string, string>("drive", "team1")]), MediaSourceKind.GoogleDrive).IsFromBeforeDriveFile);
        // A library written before source kinds were stored reads the kind from the URL.
        Assert.IsTrue(new LibraryFolder { Path = SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, key, ["~shared"], "Shared with me") }.IsFromBeforeDriveFile);

        var current = SourceUrl.AccountKey(MediaSourceKind.GoogleDrive, "08174361937620587386");
        Assert.IsFalse(Folder(GoogleDriveConnector.ChosenFolderUrl(current), MediaSourceKind.GoogleDrive).IsFromBeforeDriveFile);
        Assert.IsFalse(Folder("dropbox://acct/root/Dropbox", MediaSourceKind.Dropbox).IsFromBeforeDriveFile);
        Assert.IsFalse(Folder("onedrive://acct/d1/root/OneDrive", MediaSourceKind.OneDrive).IsFromBeforeDriveFile);
        Assert.IsFalse(new LibraryFolder { Path = @"C:\Users\me\Videos" }.IsFromBeforeDriveFile);
        Assert.IsFalse(new LibraryFolder { Path = @"\\nas\share\Movies" }.IsFromBeforeDriveFile);
    }

    // ------------------------------------------------------------------
    // 3.20 D2: hidden items
    // ------------------------------------------------------------------

    private static string Item(string id, string name) =>
        SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, "abc", [id], name);

    [TestMethod]
    public void RemovedDriveVideosStayHiddenUntilPickedAgain()
    {
        var folder = Folder(GoogleDriveConnector.ChosenFolderUrl("abc"), MediaSourceKind.GoogleDrive);
        Assert.IsNull(folder.HiddenItemIds);
        Assert.IsFalse(folder.Hides(Item("v1", "Heat.1995.mkv")));

        folder.Hide([Item("v1", "Heat.1995.mkv"), Item("v2", "Alien.1979.mp4"), Item("v1", "Heat.1995.mkv")]);
        CollectionAssert.AreEqual(new[] { "v1", "v2" }, folder.HiddenItemIds!.ToArray());
        Assert.IsTrue(folder.Hides(Item("v1", "Heat.1995.mkv")));
        // The id decides, so a rename in Drive stays hidden.
        Assert.IsTrue(folder.Hides(Item("v2", "Alien (1979).mp4")));
        Assert.IsFalse(folder.Hides(Item("v3", "The.Matrix.1999.mkv")));

        // Picking a hidden video again brings it back.
        folder.Unhide(["v2", "v9"]);
        CollectionAssert.AreEqual(new[] { "v1" }, folder.HiddenItemIds!.ToArray());
        Assert.IsFalse(folder.Hides(Item("v2", "Alien.1979.mp4")));
        folder.Unhide(["v1"]);
        Assert.IsNull(folder.HiddenItemIds);
    }

    [TestMethod]
    public void OnlyGoogleDriveSourcesHideItems()
    {
        var dropbox = Folder("dropbox://acct/root/Dropbox", MediaSourceKind.Dropbox);
        dropbox.Hide([SourceUrl.AccountItem(MediaSourceKind.Dropbox, "acct", ["id:1"], "Heat.mkv")]);
        Assert.IsNull(dropbox.HiddenItemIds);

        var local = new LibraryFolder { Path = @"C:\Users\me\Videos" };
        local.Hide([@"C:\Users\me\Videos\Heat.mkv"]);
        Assert.IsNull(local.HiddenItemIds);
        Assert.IsFalse(local.Hides(@"C:\Users\me\Videos\Heat.mkv"));
    }

    [TestMethod]
    public void HiddenIdsAreKeptWithTheLocalLibrary()
    {
        // library.json is written with these options and never leaves this PC.
        var options = new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true };
        var folder = Folder(GoogleDriveConnector.ChosenFolderUrl("abc"), MediaSourceKind.GoogleDrive);
        folder.Hide([Item("v1", "Heat.1995.mkv")]);
        var json = JsonSerializer.Serialize(new LibraryData { Folders = [folder] }, options);
        StringAssert.Contains(json, "\"hiddenItemIds\"");
        var restored = JsonSerializer.Deserialize<LibraryData>(json, options)!.Folders.Single();
        CollectionAssert.AreEqual(new[] { "v1" }, restored.HiddenItemIds!.ToArray());
        Assert.IsTrue(restored.Hides(Item("v1", "Heat.1995.mkv")));

        // A library from before this build has no list.
        var old = JsonSerializer.Deserialize<LibraryData>("""{"folders":[{"path":"C:\\Movies","name":"Movies"}]}""", options)!;
        Assert.IsNull(old.Folders.Single().HiddenItemIds);
    }
}
