using Microsoft.VisualStudio.TestTools.UnitTesting;
using Edendale.Windows.Core;
using Edendale.Windows.Services;

namespace Edendale.Windows.Tests;

/// <summary>
/// Canonical source URLs and account keys (DIFF.md §3.12), ported from the
/// SourceURLTests in Apple's ConnectorTests.swift so every branch stores the
/// same shapes.
/// </summary>
[TestClass]
public sealed class SourceUrlTests
{
    [TestMethod]
    public void AccountKeysHashTheKindAndSubject()
    {
        // First 32 hex digits of SHA-256("kind:subject"), the same vectors Apple uses.
        Assert.AreEqual("fc33299258dcfba4155a09b5ec6ea6c9", SourceUrl.AccountKey(MediaSourceKind.GoogleDrive, "110169484474386276334"));
        Assert.AreEqual("efaa7701612dd41d3d0a949ca736bce4", SourceUrl.AccountKey(MediaSourceKind.OneDrive, "48d31887-5fad-4d73-a9f5-3c356e68a038"));
        Assert.AreEqual("7e1576915dd882c6cd3cb73befcb3ddd", SourceUrl.AccountKey(MediaSourceKind.Dropbox, "dbid:AAH4f99T0taONIb-OurWxbNQ6ywGRopQngc"));
        Assert.AreEqual("a8e9cfc310a2f9a91163345e524e29b4",
            SourceUrl.S3AccountKey("https://S3.us-east-1.amazonaws.com/", "examplebucket", "AKIAIOSFODNN7EXAMPLE"));
        // The same subject on another provider is another account.
        Assert.AreNotEqual(SourceUrl.AccountKey(MediaSourceKind.GoogleDrive, "x"), SourceUrl.AccountKey(MediaSourceKind.Dropbox, "x"));
    }

    [DataTestMethod]
    [DataRow("The.Matrix.1999.mkv")]
    [DataRow("Heat (1995) #1: Director's Cut? 50%.mkv")]
    [DataRow("Amélie.2001.mkv")]
    [DataRow("Slash/In/Name.S01E02.mkv")]
    [DataRow("  spaced  .mp4")]
    public void AccountItemUrlsRoundTrip(string name)
    {
        var account = SourceUrl.AccountKey(MediaSourceKind.GoogleDrive, "subject");
        var drive = SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, account, ["1a2B-c_3"], name);
        Assert.IsTrue(drive.StartsWith($"gdrive://{account}/", StringComparison.Ordinal));
        var parsed = SourceUrl.ParseAccountItem(drive);
        Assert.IsNotNull(parsed);
        Assert.AreEqual(MediaSourceKind.GoogleDrive, parsed.Kind);
        Assert.AreEqual(account, parsed.Account);
        CollectionAssert.AreEqual(new[] { "1a2B-c_3" }, parsed.Ids.ToArray());
        Assert.AreEqual(name, parsed.Name);
        // The filename parser and the extension filter read the real file name.
        Assert.AreEqual(name, SourceUrl.FileName(drive));
        Assert.AreEqual(Path.GetExtension(name), Path.GetExtension(SourceUrl.FileName(drive)));

        var oneDrive = SourceUrl.AccountItem(MediaSourceKind.OneDrive, account, ["b!drive", "01ITEM"], name);
        CollectionAssert.AreEqual(new[] { "b!drive", "01ITEM" }, SourceUrl.ParseAccountItem(oneDrive)!.Ids.ToArray());
        Assert.AreEqual(name, SourceUrl.ParseAccountItem(oneDrive)!.Name);

        var dropbox = SourceUrl.AccountItem(MediaSourceKind.Dropbox, account, ["id:a4ayc_80_OEAAAAAAAAAXw"], name);
        StringAssert.Contains(dropbox, "/id%3Aa4ayc_80_OEAAAAAAAAAXw/");
        CollectionAssert.AreEqual(new[] { "id:a4ayc_80_OEAAAAAAAAAXw" }, SourceUrl.ParseAccountItem(dropbox)!.Ids.ToArray());
    }

    [TestMethod]
    public void ProviderUrlsClassifyByFileName()
    {
        var account = SourceUrl.AccountKey(MediaSourceKind.Dropbox, "s");
        var movie = WindowsCore.ParseMediaFile(SourceUrl.FileName(
            SourceUrl.AccountItem(MediaSourceKind.Dropbox, account, ["id:1"], "The.Matrix.1999.mkv")));
        Assert.IsFalse(movie.IsEpisode);
        Assert.AreEqual("The Matrix", movie.Title);
        Assert.AreEqual(1999, movie.Year);

        var episode = WindowsCore.ParseMediaFile(SourceUrl.FileName(
            SourceUrl.AccountItem(MediaSourceKind.OneDrive, account, ["d", "i"], "Show.Name.S01E02.mkv")));
        Assert.IsTrue(episode.IsEpisode);
        Assert.AreEqual("Show Name", episode.ShowName);
        Assert.AreEqual(1, episode.Season);
        Assert.AreEqual(2, episode.Episode);
    }

    [TestMethod]
    public void FolderUrlsCarryListingHints()
    {
        var url = SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, "abc", ["folder1"], "Team Films",
            [new KeyValuePair<string, string>("drive", "0AB")]);
        var parsed = SourceUrl.ParseAccountItem(url);
        Assert.IsNotNull(parsed);
        Assert.AreEqual("Team Films", parsed.Name);
        Assert.AreEqual("0AB", parsed.QueryValue("drive"));
        Assert.IsNull(parsed.QueryValue("missing"));
    }

    [TestMethod]
    public void RejectsMalformedAccountUrls()
    {
        Assert.IsNull(SourceUrl.ParseAccountItem("gdrive://abc/onlyname.mkv"));
        Assert.IsNull(SourceUrl.ParseAccountItem("onedrive://abc/d/Name.mkv"));
        Assert.IsNull(SourceUrl.ParseAccountItem("smb://nas/share/Name.mkv"));
        Assert.IsNull(SourceUrl.ParseAccountItem("dropbox:///id/Name.mkv"));
        Assert.IsNull(SourceUrl.ParseAccountItem(@"C:\Movies\Heat.mkv"));
    }

    [TestMethod]
    public void S3UrlsRoundTripKeysAndPrefixes()
    {
        var item = SourceUrl.S3("acct", "films", "Movies/Heat (1995)/Heat 1995.mkv");
        Assert.AreEqual("s3://acct/films/Movies/Heat%20(1995)/Heat%201995.mkv", item);
        var parsed = SourceUrl.ParseS3(item);
        Assert.IsNotNull(parsed);
        Assert.AreEqual("films", parsed.Bucket);
        Assert.AreEqual("Movies/Heat (1995)/Heat 1995.mkv", parsed.Key);
        Assert.IsFalse(parsed.IsPrefix);
        Assert.AreEqual("Heat 1995.mkv", SourceUrl.FileName(item));

        var prefix = SourceUrl.S3("acct", "films", "Movies/");
        Assert.AreEqual("Movies/", SourceUrl.ParseS3(prefix)!.Key);
        Assert.IsTrue(SourceUrl.ParseS3(prefix)!.IsPrefix);
        var root = SourceUrl.S3("acct", "films", "");
        Assert.AreEqual("s3://acct/films/", root);
        Assert.AreEqual("", SourceUrl.ParseS3(root)!.Key);
    }

    [TestMethod]
    public void ServerUrlsEncodeEachSegment()
    {
        var url = SourceUrl.Server("sftp", "nas.local", 2222, ["home", "me", "Films & TV", "Heat #1.mkv"]);
        Assert.AreEqual("sftp://nas.local:2222/home/me/Films%20&%20TV/Heat%20%231.mkv", url);
        CollectionAssert.AreEqual(new[] { "home", "me", "Films & TV", "Heat #1.mkv" }, SourceUrl.PathSegments(url!).ToArray());
        Assert.AreEqual("nfs://nas/export/video/", SourceUrl.Server("nfs", "nas", null, ["export", "video"], isDirectory: true));
        Assert.IsNull(SourceUrl.Server("sftp", "   ", null, ["x"]));
        Assert.AreEqual("sftp://[fe80::1]:22/x", SourceUrl.Server("sftp", "fe80::1", 22, ["x"]));
    }

    [TestMethod]
    public void SchemesMapToPersistedKinds()
    {
        Assert.AreEqual(MediaSourceKind.Smb, MediaSourceKinds.FromScheme("smb2"));
        Assert.AreEqual(MediaSourceKind.WebDav, MediaSourceKinds.FromScheme("DAV"));
        Assert.AreEqual(MediaSourceKind.WebDav, MediaSourceKinds.FromScheme("davs"));
        Assert.AreEqual(MediaSourceKind.GoogleDrive, MediaSourceKinds.FromScheme("gdrive"));
        Assert.IsNull(MediaSourceKinds.FromScheme("https"));
        // Raw values are persisted on LibraryFolder: never rename a case.
        CollectionAssert.AreEqual(
            new[] { "local", "smb", "nfs", "sftp", "webdav", "s3", "gdrive", "onedrive", "dropbox" },
            MediaSourceKinds.All.Select(kind => kind.RawValue()).ToArray());
        CollectionAssert.AreEqual(
            new[] { MediaSourceKind.GoogleDrive, MediaSourceKind.OneDrive, MediaSourceKind.Dropbox },
            MediaSourceKinds.All.Where(kind => kind.IsCloudAccount()).ToArray());
        CollectionAssert.AreEqual(
            new[] { MediaSourceKind.Smb, MediaSourceKind.Sftp, MediaSourceKind.WebDav, MediaSourceKind.S3 },
            MediaSourceKinds.All.Where(kind => kind.UsesServerLogin()).ToArray());
        foreach (var kind in MediaSourceKinds.All)
        {
            Assert.AreEqual(kind, MediaSourceKinds.FromRawValue(kind.RawValue()));
        }
        Assert.IsNull(MediaSourceKinds.FromRawValue("ftp"));
    }

    [TestMethod]
    public void StoredPathsKeepTheirKind()
    {
        Assert.AreEqual(MediaSourceKind.Local, MediaSourceKinds.FromPath(@"C:\Movies"));
        Assert.AreEqual(MediaSourceKind.Smb, MediaSourceKinds.FromPath(@"\\nas\media\Films"));
        Assert.AreEqual(MediaSourceKind.Sftp, MediaSourceKinds.FromPath("sftp://nas/home/Films/"));
        Assert.AreEqual(MediaSourceKind.S3, MediaSourceKinds.FromPath("s3://acct/films/"));

        // Libraries written before 27.0 have no Kind, so the path decides.
        Assert.AreEqual(MediaSourceKind.Smb, new LibraryFolder { Path = @"\\nas\media" }.SourceKind);
        Assert.AreEqual(MediaSourceKind.Local, new LibraryFolder { Path = @"D:\Films" }.SourceKind);
        Assert.AreEqual(MediaSourceKind.WebDav, new LibraryFolder { Path = "davs://cloud/dav/", Kind = "webdav" }.SourceKind);
        Assert.IsFalse(new LibraryFolder { Path = @"D:\Films" }.IsRemote);
    }

    [TestMethod]
    public void FileNamesAndParentsReadEveryPathShape()
    {
        Assert.AreEqual("Heat (1995).mkv", SourceUrl.FileName(@"C:\Users\me\Movies\Heat (1995).mkv"));
        Assert.AreEqual("Heat.mkv", SourceUrl.FileName(@"\\nas\share\Heat.mkv"));
        Assert.AreEqual("Heat 1995.mkv", SourceUrl.FileName("davs://nas.local:5006/dav/Heat%201995.mkv"));
        Assert.AreEqual("davs://nas.local:5006/dav/", SourceUrl.Parent("davs://nas.local:5006/dav/Heat%201995.mkv"));
        Assert.AreEqual(@"\\nas\share", SourceUrl.Parent(@"\\nas\share\Heat.mkv"));
        Assert.AreEqual("nas.local", SourceUrl.CredentialHost("sftp://NAS.local:22/home/x.mkv"));
        Assert.AreEqual("nas", SourceUrl.CredentialHost(@"\\NAS\share\x.mkv"));
        Assert.IsNull(SourceUrl.CredentialHost(@"C:\x.mkv"));
    }

    [TestMethod]
    public void CredentialsNeverSurviveSplitting()
    {
        Assert.IsTrue(SourceUrl.TrySplit("davs://me:secret@nas.local/dav/", out var parts));
        Assert.AreEqual("nas.local", parts.Host);
        Assert.AreEqual("nas.local", SourceUrl.CredentialHost("davs://me:secret@nas.local/dav/"));
    }

    [TestMethod]
    public void AutomaticRescansSkipRecentlyScannedRemoteSources()
    {
        var now = DateTimeOffset.UtcNow;
        var local = new LibraryFolder { Path = @"C:\Movies", LastScannedAt = now };
        Assert.IsTrue(local.NeedsAutomaticRescan(now));

        var remote = new LibraryFolder { Path = "sftp://nas/Films/", Kind = "sftp" };
        Assert.IsTrue(remote.NeedsAutomaticRescan(now));
        remote.LastScannedAt = now.AddSeconds(-60);
        Assert.IsFalse(remote.NeedsAutomaticRescan(now));
        remote.LastScannedAt = now - LibraryFolder.AutomaticRescanInterval;
        Assert.IsTrue(remote.NeedsAutomaticRescan(now));

        // SMB shares are remote too, so their walk is throttled the same way.
        var smb = new LibraryFolder { Path = @"\\nas\media", LastScannedAt = now.AddMinutes(-5) };
        Assert.IsFalse(smb.NeedsAutomaticRescan(now));
    }
}
