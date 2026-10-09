using System.Text;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Remote;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Recorded-shape Microsoft Graph, Dropbox, WebDAV, and S3 responses served
/// by a stub handler, ported from Apple's CloudListingTests: pagination,
/// shared folders, filtering, and how each provider's files are fetched. No
/// real credentials appear anywhere.
/// </summary>
[TestClass]
public sealed class CloudListingTests
{
    private sealed class PlainProtector : ISecretProtector
    {
        public byte[] Protect(byte[] data) => data;
        public byte[] Unprotect(byte[] data) => data;
    }

    private string _directory = "";

    [TestInitialize]
    public void Setup()
    {
        _directory = Path.Combine(Path.GetTempPath(), "edendale-cloud-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(_directory, recursive: true);

    private static CloudAccount Account(MediaSourceKind kind, string? driveId = null) => new()
    {
        Kind = kind.RawValue(),
        Subject = "subject-" + kind.RawValue(),
        Email = "me@example.com",
        RefreshToken = "refresh",
        DriveId = driveId,
    };

    /// <summary>A token provider that already holds a valid token for the account.</summary>
    private CloudTokenProvider Tokens(CloudAccount account)
    {
        var vault = new CloudAccountVault(Path.Combine(_directory, Guid.NewGuid().ToString("N")), new PlainProtector());
        vault.Save(account);
        var tokens = new CloudTokenProvider(vault, configuration: _ => null);
        tokens.Store(new OAuthTokenResponse { AccessToken = "token", ExpiresIn = 3600 }, account);
        return tokens;
    }

    // ------------------------------------------------------------------
    // Google Drive
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task ListsDriveFoldersFollowingShortcutsAndPages()
    {
        var pages = 0;
        var stub = new HttpStub(_ =>
        {
            if (Interlocked.Increment(ref pages) == 1)
            {
                return HttpStub.Json(new Dictionary<string, object>
                {
                    ["nextPageToken"] = "page-2",
                    ["files"] = new object[]
                    {
                        new { id = "f1", name = "Movies", mimeType = GoogleDriveConnector.FolderMimeType },
                        new { id = "v1", name = "The.Matrix.1999.mkv", mimeType = "video/x-matroska", size = "123456",
                              modifiedTime = "2024-05-01T12:34:56.789Z", videoMediaMetadata = new { durationMillis = "8160000" } },
                        new { id = "doc", name = "Notes", mimeType = "application/vnd.google-apps.document" },
                        new { id = "s1", name = "Shows", mimeType = GoogleDriveConnector.ShortcutMimeType,
                              shortcutDetails = new { targetId = "f2", targetMimeType = GoogleDriveConnector.FolderMimeType } },
                        new { id = "s2", name = "Alien.1979.mp4", mimeType = GoogleDriveConnector.ShortcutMimeType,
                              shortcutDetails = new { targetId = "v2", targetMimeType = "video/mp4" } },
                    },
                });
            }
            return HttpStub.Json(new { files = new object[] { new { id = "v3", name = "Heat.1995.mkv", mimeType = "video/x-matroska" } } });
        });
        var account = Account(MediaSourceKind.GoogleDrive);
        var connector = new GoogleDriveConnector(account, Tokens(account), stub.Client);

        var entries = await connector.ListAsync(connector.FolderUrl("f0", "Films"), CancellationToken.None);

        CollectionAssert.AreEqual(new[] { "Movies", "Shows", "Alien.1979.mp4", "Heat.1995.mkv", "The.Matrix.1999.mkv" },
            entries.Select(e => e.Name).ToArray());
        var shows = entries.Single(e => e.Name == "Shows");
        Assert.IsTrue(shows.IsDirectory);
        CollectionAssert.AreEqual(new[] { "f2" }, SourceUrl.ParseAccountItem(shows.Url)!.Ids.ToArray());
        CollectionAssert.AreEqual(new[] { "v2" }, SourceUrl.ParseAccountItem(entries.Single(e => e.Name == "Alien.1979.mp4").Url)!.Ids.ToArray());
        var matrix = entries.Single(e => e.Name == "The.Matrix.1999.mkv");
        Assert.AreEqual(123_456, matrix.Size);
        Assert.AreEqual(8160, matrix.Duration);
        Assert.AreEqual(new DateTimeOffset(2024, 5, 1, 12, 34, 56, 789, TimeSpan.Zero), matrix.Modified);
        Assert.AreEqual($"gdrive://{account.Key}/v1/The.Matrix.1999.mkv", matrix.Url);
        Assert.IsTrue(matrix.IsVideo);

        var requests = stub.Requests;
        Assert.AreEqual(2, requests.Count);
        Assert.AreEqual("www.googleapis.com", requests[0].Uri.Host);
        Assert.AreEqual("/drive/v3/files", requests[0].Uri.AbsolutePath);
        Assert.AreEqual("'f0' in parents and trashed = false", requests[0].Query("q"));
        Assert.AreEqual("true", requests[0].Query("supportsAllDrives"));
        Assert.AreEqual("true", requests[0].Query("includeItemsFromAllDrives"));
        StringAssert.Contains(requests[0].Query("fields"), "videoMediaMetadata(durationMillis)");
        Assert.IsNull(requests[0].Query("corpora"));
        Assert.AreEqual("page-2", requests[1].Query("pageToken"));
        Assert.IsTrue(requests.All(r => r.Header("Authorization") == "Bearer token"));
    }

    [TestMethod]
    public async Task DriveRootsAndSharedDrives()
    {
        var stub = new HttpStub(request => request.RequestUri!.AbsolutePath == "/drive/v3/drives"
            ? HttpStub.Json(new { drives = new object[] { new { id = "team1", name = "Team Films" } } })
            : HttpStub.Json(new { files = new object[] { new { id = "sub", name = "Season 1", mimeType = GoogleDriveConnector.FolderMimeType } } }));
        var account = Account(MediaSourceKind.GoogleDrive);
        var connector = new GoogleDriveConnector(account, Tokens(account), stub.Client);

        // The picker's root needs no request and can't itself be linked.
        var roots = await connector.ListAsync(connector.Root, CancellationToken.None);
        CollectionAssert.AreEqual(new[] { "My Drive", "Shared with me", "Shared drives" }, roots.Select(e => e.Name).ToArray());
        Assert.AreEqual(0, stub.Requests.Count);
        Assert.IsFalse(connector.CanIndex(connector.Root));
        Assert.IsFalse(connector.CanIndex(roots[2].Url));
        Assert.IsTrue(connector.CanIndex(roots[0].Url));
        Assert.IsTrue(connector.CanIndex(roots[1].Url));
        CollectionAssert.AreEqual(new[] { "root" }, SourceUrl.ParseAccountItem(roots[0].Url)!.Ids.ToArray());

        var drives = await connector.ListAsync(roots[2].Url, CancellationToken.None);
        var team = drives.Single();
        Assert.AreEqual("Team Films", team.Name);
        Assert.AreEqual("team1", SourceUrl.ParseAccountItem(team.Url)!.QueryValue("drive"));

        // Listing inside a shared drive names it, and its folders keep it.
        var inside = await connector.ListAsync(team.Url, CancellationToken.None);
        var request = stub.Requests[^1];
        Assert.AreEqual("drive", request.Query("corpora"));
        Assert.AreEqual("team1", request.Query("driveId"));
        Assert.AreEqual("team1", SourceUrl.ParseAccountItem(inside.Single().Url)!.QueryValue("drive"));

        _ = await connector.ListAsync(roots[1].Url, CancellationToken.None);
        Assert.AreEqual("sharedWithMe = true and trashed = false", stub.Requests[^1].Query("q"));
    }

    [TestMethod]
    public async Task DriveFoldersKeepTheSharedDriveTheyReportAndQueriesAreEscaped()
    {
        var stub = new HttpStub(_ => HttpStub.Json(new
        {
            files = new object[] { new { id = "t1", name = "Team", mimeType = GoogleDriveConnector.FolderMimeType, driveId = "team9" } },
        }));
        var account = Account(MediaSourceKind.GoogleDrive);
        var connector = new GoogleDriveConnector(account, Tokens(account), stub.Client);

        // "Shared with me" names no drive, but a folder in a shared drive says which.
        var entries = await connector.ListAsync(connector.FolderUrl(GoogleDriveConnector.VirtualFolder.SharedWithMe, "Shared with me"), CancellationToken.None);
        Assert.AreEqual("team9", SourceUrl.ParseAccountItem(entries.Single().Url)!.QueryValue("drive"));

        Assert.AreEqual(@"it\'s a \\ test", GoogleDriveConnector.QueryLiteral(@"it's a \ test"));
    }

    [TestMethod]
    public async Task DriveStreamsWithABearerTokenAndAltMedia()
    {
        var account = Account(MediaSourceKind.GoogleDrive);
        var resolver = new GoogleDriveContentResolver("v1", account.Key, Tokens(account));
        Assert.IsFalse(resolver.UsesPreauthorizedLinks);
        using var request = await resolver.ContentRequestAsync(false, CancellationToken.None);
        var url = request.RequestUri!;
        Assert.AreEqual("www.googleapis.com", url.Host);
        Assert.AreEqual("/drive/v3/files/v1", url.AbsolutePath);
        var query = OAuthClient.ParseQuery(url.Query);
        Assert.AreEqual("media", query["alt"]);
        Assert.AreEqual("true", query["supportsAllDrives"]);
        Assert.IsFalse(query.ContainsKey("acknowledgeAbuse"));
        Assert.AreEqual("Bearer token", request.Headers.Authorization?.ToString());
    }

    // ------------------------------------------------------------------
    // OneDrive
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task ListsOneDriveChildrenAcrossPagesAndSharedFolders()
    {
        var stub = new HttpStub(request =>
        {
            if (request.RequestUri!.Query.Contains("skiptoken"))
            {
                return HttpStub.Json(new { value = new object[] { new { id = "a1", name = "Alien.1979.mp4", size = 20, file = new { mimeType = "video/mp4" } } } });
            }
            return HttpStub.Json(new Dictionary<string, object>
            {
                ["value"] = new object[]
                {
                    new { id = "m1", name = "Movies", folder = new { childCount = 3 } },
                    new { id = "h1", name = "Heat.1995.mkv", size = 1000, file = new { mimeType = "video/x-matroska" },
                          video = new { duration = 6_000_000 }, lastModifiedDateTime = "2024-01-02T03:04:05Z" },
                    new { id = "n1", name = "Notebook", package = new { type = "oneNote" } },
                    new { id = "x1", name = "Shared", remoteItem = new { id = "r1", folder = new { childCount = 1 }, parentReference = new { driveId = "d2" } } },
                },
                ["@odata.nextLink"] = "https://graph.microsoft.com/v1.0/drives/d1/root/children?$skiptoken=abc",
            });
        });
        var account = Account(MediaSourceKind.OneDrive, driveId: "d1");
        var connector = OneDriveConnector.Create(account, Tokens(account), stub.Client)!;

        Assert.AreEqual($"onedrive://{account.Key}/d1/root/OneDrive", connector.Root);
        var entries = await connector.ListAsync(connector.Root, CancellationToken.None);
        CollectionAssert.AreEqual(new[] { "Movies", "Shared", "Alien.1979.mp4", "Heat.1995.mkv" }, entries.Select(e => e.Name).ToArray());
        var heat = entries.Single(e => e.Name == "Heat.1995.mkv");
        Assert.AreEqual(6000, heat.Duration);
        Assert.AreEqual(1000, heat.Size);
        CollectionAssert.AreEqual(new[] { "d1", "h1" }, SourceUrl.ParseAccountItem(heat.Url)!.Ids.ToArray());
        var shared = entries.Single(e => e.Name == "Shared");
        Assert.IsTrue(shared.IsDirectory);
        CollectionAssert.AreEqual(new[] { "d2", "r1" }, SourceUrl.ParseAccountItem(shared.Url)!.Ids.ToArray());

        var requests = stub.Requests;
        Assert.AreEqual(2, requests.Count);
        Assert.AreEqual("/v1.0/drives/d1/root/children", requests[0].Uri.AbsolutePath);
        StringAssert.Contains(requests[0].Query("$select"), "video");
        Assert.AreEqual("Bearer token", requests[0].Header("Authorization"));
        StringAssert.Contains(requests[1].Uri.ToString(), "skiptoken=abc");

        // An account from before the drive was known can't browse.
        Assert.IsNull(OneDriveConnector.Create(Account(MediaSourceKind.OneDrive), Tokens(account)));
    }

    [TestMethod]
    public async Task OneDriveStreamsFromItsPreauthenticatedDownloadUrl()
    {
        var lookups = 0;
        var stub = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object>
        {
            ["id"] = "h1",
            ["@microsoft.graph.downloadUrl"] = $"https://download.example/link-{Interlocked.Increment(ref lookups)}",
        }));
        var account = Account(MediaSourceKind.OneDrive, driveId: "d1");
        var resolver = new OneDriveContentResolver("d1", "h1", account.Key, Tokens(account), stub.Client);
        Assert.IsTrue(resolver.UsesPreauthorizedLinks);
        using var first = await resolver.ContentRequestAsync(false, CancellationToken.None);
        Assert.AreEqual("https://download.example/link-1", first.RequestUri!.ToString());
        Assert.IsNull(first.Headers.Authorization);
        // Reused until it fails, then resolved again.
        using var again = await resolver.ContentRequestAsync(false, CancellationToken.None);
        Assert.AreEqual(first.RequestUri, again.RequestUri);
        using var refreshed = await resolver.ContentRequestAsync(true, CancellationToken.None);
        Assert.AreEqual("https://download.example/link-2", refreshed.RequestUri!.ToString());
        Assert.AreEqual("/v1.0/drives/d1/items/h1", stub.Requests[0].Uri.AbsolutePath);
    }

    // ------------------------------------------------------------------
    // Dropbox
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task ListsADropboxFolderAcrossPages()
    {
        var stub = new HttpStub(request =>
        {
            if (request.RequestUri!.AbsolutePath.EndsWith("/continue"))
            {
                return HttpStub.Json(new Dictionary<string, object>
                {
                    ["entries"] = new object[] { new Dictionary<string, object> { [".tag"] = "file", ["name"] = "Alien.1979.mp4", ["id"] = "id:a", ["size"] = 5 } },
                    ["cursor"] = "c2",
                    ["has_more"] = false,
                });
            }
            return HttpStub.Json(new Dictionary<string, object>
            {
                ["entries"] = new object[]
                {
                    new Dictionary<string, object> { [".tag"] = "folder", ["name"] = "Movies", ["id"] = "id:m" },
                    new Dictionary<string, object> { [".tag"] = "file", ["name"] = "Heat.1995.mkv", ["id"] = "id:h", ["size"] = 10,
                        ["server_modified"] = "2015-05-12T15:50:38Z", ["is_downloadable"] = true },
                    new Dictionary<string, object> { [".tag"] = "file", ["name"] = ".hidden.mkv", ["id"] = "id:x" },
                    new Dictionary<string, object> { [".tag"] = "deleted", ["name"] = "Gone.mkv" },
                },
                ["cursor"] = "c1",
                ["has_more"] = true,
            });
        });
        var account = Account(MediaSourceKind.Dropbox);
        var connector = new DropboxConnector(account, Tokens(account), stub.Client);

        var entries = await connector.ListAsync(connector.Root, CancellationToken.None);
        CollectionAssert.AreEqual(new[] { "Movies", "Alien.1979.mp4", "Heat.1995.mkv" }, entries.Select(e => e.Name).ToArray());
        var heat = entries.Single(e => e.Name == "Heat.1995.mkv");
        Assert.AreEqual($"dropbox://{account.Key}/id%3Ah/Heat.1995.mkv", heat.Url);
        CollectionAssert.AreEqual(new[] { "id:h" }, SourceUrl.ParseAccountItem(heat.Url)!.Ids.ToArray());

        var requests = stub.Requests;
        Assert.AreEqual("/2/files/list_folder", requests[0].Uri.AbsolutePath);
        Assert.AreEqual("", requests[0].Json.GetProperty("path").GetString());
        Assert.IsFalse(requests[0].Json.GetProperty("recursive").GetBoolean());
        Assert.AreEqual("application/json", requests[0].Header("Content-Type"));
        Assert.AreEqual("c1", requests[1].Json.GetProperty("cursor").GetString());
    }

    [TestMethod]
    public async Task EnumeratesDropboxRecursivelyInOneListing()
    {
        var stub = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object>
        {
            ["entries"] = new object[]
            {
                new Dictionary<string, object> { [".tag"] = "folder", ["name"] = "Movies", ["id"] = "id:m", ["path_lower"] = "/movies" },
                new Dictionary<string, object> { [".tag"] = "file", ["name"] = "Heat.1995.mkv", ["id"] = "id:h", ["path_lower"] = "/movies/heat.1995.mkv" },
                new Dictionary<string, object> { [".tag"] = "file", ["name"] = "Old.2001.mkv", ["id"] = "id:o", ["path_lower"] = "/.trash/old.2001.mkv" },
                new Dictionary<string, object> { [".tag"] = "file", ["name"] = "readme.txt", ["id"] = "id:r", ["path_lower"] = "/movies/readme.txt" },
            },
            ["cursor"] = "c",
            ["has_more"] = false,
        }));
        var account = Account(MediaSourceKind.Dropbox);
        var connector = new DropboxConnector(account, Tokens(account), stub.Client);

        var folder = SourceUrl.AccountItem(MediaSourceKind.Dropbox, account.Key, ["id:films"], "Films");
        var videos = await connector.EnumerateVideosAsync(folder, CancellationToken.None);
        CollectionAssert.AreEqual(new[] { "Heat.1995.mkv" }, videos.Select(v => v.Name).ToArray());
        Assert.AreEqual("id:films", stub.Requests[0].Json.GetProperty("path").GetString());
        Assert.IsTrue(stub.Requests[0].Json.GetProperty("recursive").GetBoolean());
    }

    [TestMethod]
    public async Task DropboxStreamsFromATemporaryLink()
    {
        var links = 0;
        var stub = new HttpStub(_ => HttpStub.Json(new { link = $"https://dl.dropboxusercontent.com/link-{Interlocked.Increment(ref links)}", metadata = new { } }));
        var account = Account(MediaSourceKind.Dropbox);
        var resolver = new DropboxContentResolver("id:h", account.Key, Tokens(account), stub.Client);
        using var first = await resolver.ContentRequestAsync(false, CancellationToken.None);
        Assert.AreEqual("https://dl.dropboxusercontent.com/link-1", first.RequestUri!.ToString());
        using var second = await resolver.ContentRequestAsync(true, CancellationToken.None);
        Assert.AreEqual("https://dl.dropboxusercontent.com/link-2", second.RequestUri!.ToString());
        Assert.AreEqual("/2/files/get_temporary_link", stub.Requests[0].Uri.AbsolutePath);
        Assert.AreEqual("id:h", stub.Requests[0].Json.GetProperty("path").GetString());
    }

    // ------------------------------------------------------------------
    // WebDAV
    // ------------------------------------------------------------------

    private const string Multistatus = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
          <d:response>
            <d:href>/remote.php/dav/files/me/Movies/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/Movies/Heat%20(1995).mkv</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength>
              <d:getlastmodified>Tue, 15 Nov 1994 12:45:26 GMT</d:getlastmodified></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>https://HOST/remote.php/dav/files/me/Movies/Season%201/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/Movies/.DS_Store</d:href>
            <d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
        </d:multistatus>
        """;

    [TestMethod]
    public void ParsesWebDavMultistatusFromDifferentServers()
    {
        var body = Encoding.UTF8.GetBytes(Multistatus.Replace("HOST", "cloud.example.com"));
        var entries = WebDavConnector.ParseMultistatus(body,
            "https://cloud.example.com/remote.php/dav/files/me/Movies/",
            "davs://cloud.example.com/remote.php/dav/files/me/Movies/")!;
        CollectionAssert.AreEqual(new[] { "Heat (1995).mkv", "Season 1", ".DS_Store" }, entries.Select(e => e.Name).ToArray());
        var heat = entries[0];
        Assert.AreEqual("davs://cloud.example.com/remote.php/dav/files/me/Movies/Heat%20(1995).mkv", heat.Url);
        Assert.AreEqual(1234, heat.Size);
        Assert.AreEqual(DateTimeOffset.FromUnixTimeSeconds(784_903_526), heat.Modified);
        Assert.IsFalse(heat.IsDirectory);
        Assert.IsTrue(entries[1].IsDirectory);
        Assert.IsTrue(entries[1].Url.EndsWith("/Season%201/", StringComparison.Ordinal));

        // Apache mod_dav: uppercase prefix and properties in another prefix.
        const string apache = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:"><D:response xmlns:lp1="DAV:">
            <D:href>/dav/Alien.1979.mp4</D:href>
            <D:propstat><D:prop><lp1:resourcetype/><lp1:getcontentlength>5</lp1:getcontentlength></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>
            """;
        var apacheEntries = WebDavConnector.ParseMultistatus(Encoding.UTF8.GetBytes(apache), "http://nas.local/dav/", "dav://nas.local/dav/")!;
        CollectionAssert.AreEqual(new[] { "Alien.1979.mp4" }, apacheEntries.Select(e => e.Name).ToArray());
        Assert.AreEqual(5, apacheEntries[0].Size);
        Assert.AreEqual("dav://nas.local/dav/Alien.1979.mp4", apacheEntries[0].Url);
    }

    [TestMethod]
    public async Task ListsAWebDavFolderWithPropfind()
    {
        const string host = "dav.example.com";
        var body = Encoding.UTF8.GetBytes(Multistatus.Replace("HOST", host));
        var stub = new HttpStub(_ => HttpStub.Bytes((System.Net.HttpStatusCode)207, body));
        var connector = WebDavConnector.FromAddress($"https://{host}/remote.php/dav/files/me/Movies",
            new ServerLogin { Kind = "webdav", Host = host, Username = "me", Password = "secret" }, stub.Client)!;
        Assert.AreEqual($"davs://{host}/remote.php/dav/files/me/Movies/", connector.Root);

        var entries = await connector.ListAsync(connector.Root, CancellationToken.None);
        // Hidden files are left out; folders come first.
        CollectionAssert.AreEqual(new[] { "Season 1", "Heat (1995).mkv" }, entries.Select(e => e.Name).ToArray());
        var request = stub.Requests[0];
        Assert.AreEqual("PROPFIND", request.Method);
        Assert.AreEqual("1", request.Header("Depth"));
        Assert.AreEqual($"https://{host}/remote.php/dav/files/me/Movies/", request.Uri.ToString());
        StringAssert.Contains(Encoding.UTF8.GetString(request.Body), "getcontentlength");
    }

    [TestMethod]
    public async Task ARefusedWebDavLoginSaysSo()
    {
        var stub = new HttpStub(_ => HttpStub.Text("", 401, ("WWW-Authenticate", "Basic realm=\"x\"")));
        var connector = WebDavConnector.FromAddress("https://dav.example.com/dav/", null, stub.Client)!;
        var error = await Assert.ThrowsExceptionAsync<ConnectorException>(() => connector.ValidateAsync(CancellationToken.None));
        Assert.AreEqual(ConnectorFailure.AuthenticationFailed, error.Failure);
        Assert.IsTrue(error.NeedsUserAction);
    }

    [TestMethod]
    public async Task PlainHttpReachesOnlyTheLocalNetwork()
    {
        var stub = new HttpStub(_ => HttpStub.Text("", 207));
        var remote = WebDavConnector.FromAddress("http://files.example.com/dav/", null, stub.Client)!;
        var error = await Assert.ThrowsExceptionAsync<ConnectorException>(() => remote.ValidateAsync(CancellationToken.None));
        Assert.AreEqual(ConnectorFailure.InsecureConnection, error.Failure);
        Assert.AreEqual(0, stub.Requests.Count);

        Assert.IsTrue(LocalNetwork.IsLocalHost("nas.local"));
        Assert.IsTrue(LocalNetwork.IsLocalHost("nas"));
        Assert.IsTrue(LocalNetwork.IsLocalHost("192.168.1.10"));
        Assert.IsTrue(LocalNetwork.IsLocalHost("10.0.0.2"));
        Assert.IsTrue(LocalNetwork.IsLocalHost("100.101.102.103"));
        Assert.IsTrue(LocalNetwork.IsLocalHost("[fd00::1]"));
        Assert.IsFalse(LocalNetwork.IsLocalHost("8.8.8.8"));
        Assert.IsFalse(LocalNetwork.IsLocalHost("files.example.com"));
    }

    [TestMethod]
    public void WebDavLoginsGetTheirOwnClients()
    {
        ServerLogin Login(string user, string password) => new() { Kind = "webdav", Host = "nas.local", Username = user, Password = password };
        var first = WebDavConnector.FromAddress("https://nas.local/dav/", Login("me", "right"))!;
        var second = WebDavConnector.FromAddress("https://nas.local/dav/Movies/", Login("me", "right"))!;
        var mistyped = WebDavConnector.FromAddress("https://nas.local/dav/", Login("me", "wrong"))!;
        var guest = WebDavConnector.FromAddress("https://nas.local/dav/", null)!;
        var emptyLogin = WebDavConnector.FromAddress("https://nas.local/dav/", Login("", ""))!;

        Assert.AreSame(first.Client, second.Client);
        Assert.AreNotSame(first.Client, mistyped.Client);
        Assert.AreNotSame(first.Client, guest.Client);
        Assert.AreSame(guest.Client, emptyLogin.Client);
        Assert.AreNotSame(first.Client, RemoteHttp.Shared);
    }

    [TestMethod]
    public void NormalizesWebDavAddresses()
    {
        Assert.AreEqual("davs://cloud.example.com/remote.php/dav/files/me/", WebDavConnector.CanonicalRoot("https://cloud.example.com/remote.php/dav/files/me"));
        Assert.AreEqual("dav://nas.local:5005/", WebDavConnector.CanonicalRoot("http://nas.local:5005"));
        Assert.AreEqual("davs://nas.local/dav/", WebDavConnector.CanonicalRoot("nas.local/dav"));
        Assert.AreEqual("davs://nas.local/", WebDavConnector.CanonicalRoot("https://me:pw@nas.local/"));
        Assert.IsNull(WebDavConnector.CanonicalRoot("ftp://nas.local/"));
        Assert.IsNull(WebDavConnector.CanonicalRoot("  "));
        Assert.AreEqual("https://nas.local:5006/a%20b/", WebDavConnector.HttpUrl("davs://nas.local:5006/a%20b/"));
        Assert.AreEqual("http://nas.local/x.mkv", WebDavConnector.HttpUrl("dav://nas.local/x.mkv"));
    }

    // ------------------------------------------------------------------
    // S3
    // ------------------------------------------------------------------

    private static readonly S3Signer Signer = new("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
    private static readonly DateTimeOffset ExampleDate = DateTimeOffset.FromUnixTimeSeconds(1_369_353_600); // 2013-05-24T00:00:00Z

    [TestMethod]
    public void SignsListRequestsLikeAwsExample()
    {
        // "GET Bucket (List Objects)" from the AWS Signature Version 4 docs.
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://examplebucket.s3.amazonaws.com/?max-keys=2&prefix=J");
        Signer.Sign(request, ExampleDate);
        Assert.AreEqual("20130524T000000Z", request.Headers.GetValues("x-amz-date").Single());
        Assert.AreEqual(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            request.Headers.GetValues("Authorization").Single());
    }

    [TestMethod]
    public void PresignsUrlsLikeAwsExample()
    {
        // The query-string authentication example from the AWS docs.
        var url = Signer.Presign("https://examplebucket.s3.amazonaws.com/test.txt", ExampleDate, expires: 86400)!;
        var query = OAuthClient.ParseQuery(new Uri(url).Query);
        Assert.AreEqual("aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404", query["X-Amz-Signature"]);
        Assert.AreEqual("AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request", query["X-Amz-Credential"]);
    }

    [TestMethod]
    public void AddressesBucketsByPathOrHost()
    {
        var pathStyle = new S3Configuration("http://minio.local:9000", "us-east-1", "films", UsesPathStyle: true);
        Assert.AreEqual("http://minio.local:9000/films/Heat%20%281995%29/Heat.mkv", S3Connector.BucketUrl(pathStyle, "Heat (1995)/Heat.mkv"));
        var aws = new S3Configuration("https://s3.us-east-1.amazonaws.com", "us-east-1", "films", UsesPathStyle: false);
        Assert.AreEqual("https://films.s3.us-east-1.amazonaws.com/a%20b.mkv?list-type=2",
            S3Connector.BucketUrl(aws, "a b.mkv", [new("list-type", "2")]));
        Assert.IsFalse(S3Connector.DefaultUsesPathStyle(aws.Endpoint, "films"));
        Assert.IsTrue(S3Connector.DefaultUsesPathStyle(aws.Endpoint, "my.films"));
        Assert.IsTrue(S3Connector.DefaultUsesPathStyle(pathStyle.Endpoint, "films"));
    }

    [TestMethod]
    public async Task ListsAnS3PrefixAcrossPages()
    {
        var pages = 0;
        var stub = new HttpStub(_ => Interlocked.Increment(ref pages) == 1
            ? HttpStub.Text("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Name>films</Name><Prefix>Movies/</Prefix><IsTruncated>true</IsTruncated>
                  <NextContinuationToken>next-1</NextContinuationToken>
                  <Contents><Key>Movies/</Key><Size>0</Size></Contents>
                  <Contents><Key>Movies/Heat.1995.mkv</Key><Size>1000</Size><LastModified>2024-01-02T03:04:05.000Z</LastModified></Contents>
                  <CommonPrefixes><Prefix>Movies/Classics/</Prefix></CommonPrefixes>
                </ListBucketResult>
                """, 200)
            : HttpStub.Text("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <IsTruncated>false</IsTruncated>
                  <Contents><Key>Movies/Alien.1979.mp4</Key><Size>5</Size></Contents>
                </ListBucketResult>
                """, 200));
        var configuration = new S3Configuration("https://s3.example.com", "us-east-1", "films", UsesPathStyle: true);
        var connector = new S3Connector(configuration, "AKID", "secret", stub.Client);

        var folder = SourceUrl.S3(connector.AccountKey, "films", "Movies/");
        var entries = await connector.ListAsync(folder, CancellationToken.None);
        CollectionAssert.AreEqual(new[] { "Classics", "Alien.1979.mp4", "Heat.1995.mkv" }, entries.Select(e => e.Name).ToArray());
        Assert.IsTrue(entries[0].IsDirectory);
        Assert.AreEqual("Movies/Classics/", SourceUrl.ParseS3(entries[0].Url)!.Key);
        Assert.AreEqual(1000, entries[^1].Size);

        var requests = stub.Requests;
        Assert.AreEqual(2, requests.Count);
        Assert.AreEqual("/films/", requests[0].Uri.AbsolutePath);
        Assert.AreEqual("2", requests[0].Query("list-type"));
        Assert.AreEqual("/", requests[0].Query("delimiter"));
        Assert.AreEqual("Movies/", requests[0].Query("prefix"));
        Assert.AreEqual("next-1", requests[1].Query("continuation-token"));
        Assert.IsTrue(requests.All(r => r.Header("Authorization")?.StartsWith("AWS4-HMAC-SHA256 Credential=AKID/", StringComparison.Ordinal) == true));
        // The account key hashes the endpoint, bucket, and access key ID.
        Assert.AreEqual(SourceUrl.S3AccountKey("https://s3.example.com", "films", "AKID"), connector.AccountKey);
    }

    [TestMethod]
    public void ExplainsS3LoginAndRegionErrors()
    {
        var signature = Encoding.UTF8.GetBytes("<Error><Code>SignatureDoesNotMatch</Code></Error>");
        Assert.AreEqual(ConnectorFailure.AuthenticationFailed, S3Connector.Error(403, signature, "s3.example.com").Failure);
        var region = Encoding.UTF8.GetBytes("<Error><Code>AuthorizationHeaderMalformed</Code><Region>eu-west-1</Region></Error>");
        var error = S3Connector.Error(400, region, "s3.example.com");
        Assert.AreEqual(ConnectorFailure.BucketInAnotherRegion, error.Failure);
        Assert.AreEqual("eu-west-1", error.Subject);
    }

    [TestMethod]
    public async Task S3StreamsThroughFreshlySignedUrls()
    {
        var configuration = new S3Configuration("https://s3.example.com", "auto", "films", UsesPathStyle: true);
        var resolver = new S3ContentResolver(configuration, "AKID", "secret", "Movies/Heat 1995.mkv");
        Assert.IsTrue(resolver.UsesPreauthorizedLinks);
        using var request = await resolver.ContentRequestAsync(false, CancellationToken.None);
        var url = request.RequestUri!;
        Assert.AreEqual("/films/Movies/Heat%201995.mkv", url.AbsolutePath);
        var query = OAuthClient.ParseQuery(url.Query);
        Assert.IsTrue(query.ContainsKey("X-Amz-Signature"));
        Assert.IsTrue(query["X-Amz-Credential"].EndsWith("/auto/s3/aws4_request", StringComparison.Ordinal));
    }

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    [TestMethod]
    public void RebuildsConnectorsAndByteSourcesFromWhatTheLibraryStores()
    {
        var logins = new ServerLoginStore(Path.Combine(_directory, "logins.bin"), new PlainProtector());
        var vault = new CloudAccountVault(Path.Combine(_directory, "accounts.bin"), new PlainProtector());
        var environment = new ConnectorEnvironment(logins, vault, new CloudTokenProvider(vault), new HostKeyStore(Path.Combine(_directory, "keys.json")));

        // WebDAV works as a guest; S3 and SFTP need their login.
        Assert.IsNotNull(ConnectorFactory.ForSource("davs://cloud.example.com/dav/", MediaSourceKind.WebDav, environment));
        Assert.IsNull(ConnectorFactory.ForSource("s3://acct/films/", MediaSourceKind.S3, environment));
        Assert.IsNull(ConnectorFactory.ForSource("sftp://nas.local:2222/home/me/Films/", MediaSourceKind.Sftp, environment));
        Assert.IsNull(ConnectorFactory.ForSource(@"C:\Movies", MediaSourceKind.Local, environment));

        logins.Save(new ServerLogin { Kind = "sftp", Host = "nas.local", Username = "me", Password = "pw" });
        var sftp = (SftpConnector)ConnectorFactory.ForSource("sftp://nas.local:2222/home/me/Films/", MediaSourceKind.Sftp, environment)!;
        Assert.AreEqual(2222, sftp.Port);
        Assert.AreEqual("/home/me/Films", sftp.StartPath);
        Assert.AreEqual("sftp://nas.local:2222/home/me/Films/", sftp.Root);

        var s3 = new S3Configuration("https://s3.example.com", "auto", "films", true);
        logins.Save(new ServerLogin { Kind = "s3", Host = "acct", Username = "AKID", Password = "secret", S3 = s3 });
        Assert.IsInstanceOfType<RemoteByteSource>(ConnectorFactory.ByteSourceFor("s3://acct/films/Heat.mkv", environment));
        Assert.IsInstanceOfType<RemoteByteSource>(ConnectorFactory.ByteSourceFor("davs://nas/dav/Heat.mkv", environment));
        Assert.IsInstanceOfType<BufferedByteSource>(ConnectorFactory.ByteSourceFor("sftp://nas.local:2222/home/me/Heat.mkv", environment));

        // A cloud item without its account asks to sign in again.
        var onedrive = SourceUrl.AccountItem(MediaSourceKind.OneDrive, "acct", ["d", "i"], "Heat.mkv");
        var missing = Assert.ThrowsException<ConnectorException>(() => ConnectorFactory.ByteSourceFor(onedrive, environment));
        Assert.AreEqual(ConnectorFailure.SignInRequired, missing.Failure);
        Assert.AreEqual(ConnectorFailure.InvalidAddress,
            Assert.ThrowsException<ConnectorException>(() => ConnectorFactory.ByteSourceFor(@"\\nas\share\Heat.mkv", environment)).Failure);

        // Google Drive: nothing without the account, a connector and a byte source with it.
        var drive = Account(MediaSourceKind.GoogleDrive);
        var driveFolder = SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, drive.Key, ["f"], "Films");
        var driveItem = SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, drive.Key, ["v1"], "Heat.mkv");
        Assert.IsNull(ConnectorFactory.ForSource(driveFolder, MediaSourceKind.GoogleDrive, environment));
        Assert.AreEqual(ConnectorFailure.SignInRequired,
            Assert.ThrowsException<ConnectorException>(() => ConnectorFactory.ByteSourceFor(driveItem, environment)).Failure);
        vault.Save(drive);
        Assert.IsInstanceOfType<GoogleDriveConnector>(ConnectorFactory.ForSource(driveFolder, MediaSourceKind.GoogleDrive, environment));
        Assert.IsInstanceOfType<RemoteByteSource>(ConnectorFactory.ByteSourceFor(driveItem, environment));
        Assert.IsTrue(ConnectorFactory.NeedsCustomInput(driveItem));

        // Only HTTP and SFTP items need the custom input; LibVLC opens the rest.
        Assert.IsTrue(ConnectorFactory.NeedsCustomInput(onedrive));
        Assert.IsTrue(ConnectorFactory.NeedsCustomInput("sftp://nas/Heat.mkv"));
        Assert.IsFalse(ConnectorFactory.NeedsCustomInput("nfs://nas/export/Heat.mkv"));
        Assert.IsFalse(ConnectorFactory.NeedsCustomInput(@"\\nas\share\Heat.mkv"));
        Assert.IsFalse(ConnectorFactory.NeedsCustomInput(@"C:\Movies\Heat.mkv"));
    }
}
