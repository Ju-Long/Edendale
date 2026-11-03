using System.Net;
using System.Text;
using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Remote;
using Edendale.Windows.Services.Sync;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The sync locations of SYNC-PROTOCOL.md §4 against recorded responses
/// (FEATURES-27.1.md §11): Google Drive (files.list, files.create,
/// files.update with uploadType=media), the OneDrive app folder, Dropbox
/// (upload with mode overwrite), and WebDAV writes (MKCOL, PUT, PROPFIND with
/// ETags). Plus the handoff URL forms and the 27.1 scope rules.
/// </summary>
[TestClass]
public sealed class SyncLocationsTests
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
        _directory = Path.Combine(Path.GetTempPath(), "edendale-locations-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(_directory, recursive: true);

    private static CloudAccount Account(MediaSourceKind kind, IReadOnlyList<string>? scopes = null) => new()
    {
        Kind = kind.RawValue(),
        Subject = "subject-" + kind.RawValue(),
        Email = "me@example.com",
        RefreshToken = "refresh",
        DriveId = kind == MediaSourceKind.OneDrive ? "drive-1" : null,
        Scopes = scopes,
    };

    private CloudTokenProvider Tokens(CloudAccount account)
    {
        var vault = new CloudAccountVault(Path.Combine(_directory, Guid.NewGuid().ToString("N")), new PlainProtector());
        vault.Save(account);
        var tokens = new CloudTokenProvider(vault, configuration: _ => null);
        tokens.Store(new OAuthTokenResponse { AccessToken = "token", ExpiresIn = 3600 }, account);
        return tokens;
    }

    private static string Query(HttpStub.Recorded request, string name) => request.Query(name) ?? "";

    // ------------------------------------------------------------------
    // Google Drive
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task GoogleDrive_CreatesTheFolders_ListsTheSpace_AndReplacesFiles()
    {
        var account = Account(MediaSourceKind.GoogleDrive, CloudProviders.GoogleScopes);
        var stub = new HttpStub(request =>
        {
            var url = request.RequestUri!.ToString();
            var q = OAuthClient.ParseQuery(request.RequestUri.Query).GetValueOrDefault("q") ?? "";
            if (request.Method == HttpMethod.Get && q.Contains("name = 'Edendale'")) return HttpStub.Json(new { files = Array.Empty<object>() });
            if (request.Method == HttpMethod.Post && url.StartsWith("https://www.googleapis.com/drive/v3/files?", StringComparison.Ordinal))
            {
                var body = JsonDocument.Parse(request.Content!.ReadAsStringAsync().Result).RootElement;
                return HttpStub.Json(new { id = body.GetProperty("name").GetString() == "Edendale" ? "root-1" : "space-1" });
            }
            if (request.Method == HttpMethod.Get && q.Contains("name = 'Family'")) return HttpStub.Json(new { files = Array.Empty<object>() });
            if (request.Method == HttpMethod.Get && q.Contains("'space-1' in parents"))
            {
                return HttpStub.Json(new
                {
                    files = new object[]
                    {
                        new { id = "file-a", name = SyncMergeTests.A + ".json", modifiedTime = "2026-11-02T10:00:00.000Z", size = "120", mimeType = "application/json" },
                        new { id = "sub", name = "nested", mimeType = GoogleDriveConnector.FolderMimeType },
                    },
                });
            }
            if (url.StartsWith("https://www.googleapis.com/upload/drive/v3/files", StringComparison.Ordinal)) return HttpStub.Json(new { id = "file-new" });
            if (request.Method == HttpMethod.Get && url.Contains("alt=media")) return HttpStub.Bytes(HttpStatusCode.OK, "{}"u8.ToArray());
            return HttpStub.Text("unexpected " + request.Method + " " + url, 500);
        });
        var location = new GoogleDriveSyncLocation(account, Tokens(account), null, null, stub.Client);

        var entries = await location.ListAsync("Family", CancellationToken.None);
        Assert.AreEqual(1, entries.Count, "folders are not device files");
        Assert.AreEqual(SyncMergeTests.A + ".json", entries[0].Name);
        Assert.AreEqual("2026-11-02T10:00:00.000Z:120", entries[0].Stamp);

        var created = stub.Requests.Where(r => r.Method == "POST" && r.Uri.AbsolutePath == "/drive/v3/files").ToList();
        Assert.AreEqual(2, created.Count, "Edendale and the space were created");
        Assert.AreEqual("root", created[0].Json.GetProperty("parents")[0].GetString());
        Assert.AreEqual("root-1", created[1].Json.GetProperty("parents")[0].GetString());
        Assert.AreEqual(GoogleDriveConnector.FolderMimeType, created[1].Json.GetProperty("mimeType").GetString());
        Assert.IsTrue(stub.Requests.All(r => r.Header("Authorization") == "Bearer token"));

        var settings = location.Apply(new SyncLocationSettings { Kind = SyncLocationKind.GoogleDrive, Space = "Family" });
        Assert.AreEqual("root-1", settings.GoogleRootFolderId);
        Assert.AreEqual("space-1", settings.GoogleFolderId);

        // An existing file is replaced in place; a new one is created in the space folder.
        await location.WriteAsync("Family", SyncMergeTests.A + ".json", "{\"a\":1}"u8.ToArray(), CancellationToken.None);
        var update = stub.Requests.Last();
        Assert.AreEqual("PATCH", update.Method);
        Assert.AreEqual("/upload/drive/v3/files/file-a", update.Uri.AbsolutePath);
        Assert.AreEqual("media", Query(update, "uploadType"));
        Assert.AreEqual("{\"a\":1}", Encoding.UTF8.GetString(update.Body));

        await location.WriteAsync("Family", SyncMergeTests.B + ".json", "{}"u8.ToArray(), CancellationToken.None);
        var create = stub.Requests.Last();
        Assert.AreEqual("POST", create.Method);
        Assert.AreEqual("multipart", Query(create, "uploadType"));
        var multipart = Encoding.UTF8.GetString(create.Body);
        StringAssert.Contains(multipart, "\"parents\":[\"space-1\"]");
        StringAssert.Contains(multipart, SyncMergeTests.B + ".json");

        var bytes = await location.ReadAsync("Family", SyncMergeTests.A + ".json", CancellationToken.None);
        Assert.AreEqual("{}", Encoding.UTF8.GetString(bytes));
        Assert.AreEqual("/drive/v3/files/file-a", stub.Requests.Last().Uri.AbsolutePath);
    }

    [TestMethod]
    public async Task GoogleDrive_UsesTheFolderIdAHandoffBrought()
    {
        var account = Account(MediaSourceKind.GoogleDrive, CloudProviders.GoogleScopes);
        var stub = new HttpStub(_ => HttpStub.Json(new { files = Array.Empty<object>() }));
        var location = new GoogleDriveSyncLocation(account, Tokens(account), null, "space-from-iphone", stub.Client);
        await location.ListAsync("Family", CancellationToken.None);
        Assert.AreEqual(1, stub.Requests.Count, "no folder lookup: the id is known");
        StringAssert.Contains(Query(stub.Requests[0], "q"), "'space-from-iphone' in parents");
    }

    // ------------------------------------------------------------------
    // OneDrive app folder
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task OneDrive_UsesTheAppFolder()
    {
        var account = Account(MediaSourceKind.OneDrive, CloudProviders.OneDriveScopes);
        var stub = new HttpStub(request =>
        {
            var path = request.RequestUri!.AbsolutePath;
            if (request.Method == HttpMethod.Get && path.EndsWith("approot:/Family", StringComparison.Ordinal)) return HttpStub.Text("{\"error\":{\"code\":\"itemNotFound\"}}", 404);
            if (request.Method == HttpMethod.Post && path.EndsWith("approot/children", StringComparison.Ordinal)) return HttpStub.Json(new { id = "folder-1" }, 201);
            if (request.Method == HttpMethod.Get && path.EndsWith("approot:/Family:/children", StringComparison.Ordinal))
            {
                return HttpStub.Json(new Dictionary<string, object>
                {
                    ["value"] = new object[]
                    {
                        new Dictionary<string, object> { ["id"] = "1", ["name"] = SyncMergeTests.B + ".json", ["eTag"] = "\"{ABC},3\"", ["file"] = new { } },
                        new Dictionary<string, object> { ["id"] = "2", ["name"] = "Old", ["folder"] = new { childCount = 0 } },
                    },
                });
            }
            if (request.Method == HttpMethod.Put) return HttpStub.Json(new { id = "1" });
            return HttpStub.Text("unexpected " + request.Method + " " + path, 500);
        });
        var location = new OneDriveSyncLocation(account, Tokens(account), stub.Client);

        var entries = await location.ListAsync("Family", CancellationToken.None);
        Assert.AreEqual(1, entries.Count);
        Assert.AreEqual("\"{ABC},3\"", entries[0].Stamp, "Graph's eTag is the stamp");
        var create = stub.Requests.Single(r => r.Method == "POST");
        Assert.AreEqual("/v1.0/me/drive/special/approot/children", create.Uri.AbsolutePath);
        Assert.AreEqual("Family", create.Json.GetProperty("name").GetString());

        await location.WriteAsync("Family", SyncMergeTests.A + ".json", "{}"u8.ToArray(), CancellationToken.None);
        var put = stub.Requests.Last();
        Assert.AreEqual("PUT", put.Method);
        Assert.AreEqual($"/v1.0/me/drive/special/approot:/Family/{SyncMergeTests.A}.json:/content", put.Uri.AbsolutePath);
    }

    // ------------------------------------------------------------------
    // Dropbox
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task Dropbox_UploadsWithModeOverwrite_UnderEdendale()
    {
        var account = Account(MediaSourceKind.Dropbox, CloudProviders.DropboxScopes);
        var stub = new HttpStub(request =>
        {
            var path = request.RequestUri!.AbsolutePath;
            if (path == "/2/files/create_folder_v2") return HttpStub.Text("{\"error_summary\":\"path/conflict/folder/..\"}", 409);
            if (path == "/2/files/list_folder")
            {
                return HttpStub.Json(new Dictionary<string, object>
                {
                    ["entries"] = new object[]
                    {
                        new Dictionary<string, object> { [".tag"] = "file", ["name"] = SyncMergeTests.A + ".json", ["rev"] = "015f3a", ["size"] = 10 },
                        new Dictionary<string, object> { [".tag"] = "folder", ["name"] = "x" },
                    },
                    ["cursor"] = "c1",
                    ["has_more"] = false,
                });
            }
            if (path == "/2/files/upload") return HttpStub.Json(new { name = "x" });
            if (path == "/2/files/download") return HttpStub.Bytes(HttpStatusCode.OK, "{}"u8.ToArray());
            return HttpStub.Text("unexpected " + path, 500);
        });
        var location = new DropboxSyncLocation(account, Tokens(account), stub.Client);
        var entries = await location.ListAsync("Family", CancellationToken.None);
        Assert.AreEqual("015f3a", entries.Single().Stamp, "the rev is the stamp");
        Assert.AreEqual("/Edendale/Family", stub.Requests.Single(r => r.Uri.AbsolutePath == "/2/files/list_folder").Json.GetProperty("path").GetString());

        await location.WriteAsync("Family", SyncMergeTests.B + ".json", "{}"u8.ToArray(), CancellationToken.None);
        var upload = stub.Requests.Last();
        Assert.AreEqual("content.dropboxapi.com", upload.Uri.Host);
        using var argument = JsonDocument.Parse(upload.Header("Dropbox-API-Arg")!);
        Assert.AreEqual($"/Edendale/Family/{SyncMergeTests.B}.json", argument.RootElement.GetProperty("path").GetString());
        Assert.AreEqual("overwrite", argument.RootElement.GetProperty("mode").GetString());
    }

    // ------------------------------------------------------------------
    // WebDAV
    // ------------------------------------------------------------------

    private const string SpaceListing = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/dav/Edendale/Family/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/dav/Edendale/Family/0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength><d:getetag>"etag-1"</d:getetag>
              <d:getlastmodified>Mon, 02 Nov 2026 10:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>
        """;

    [TestMethod]
    public async Task WebDav_CreatesFolders_ListsByETag_AndPutsWholeFiles()
    {
        var stub = new HttpStub(request => request.Method.Method switch
        {
            "MKCOL" => HttpStub.Text("", request.RequestUri!.AbsolutePath.EndsWith("/Edendale/", StringComparison.Ordinal) ? 405 : 201),
            "PROPFIND" => HttpStub.Bytes((HttpStatusCode)207, Encoding.UTF8.GetBytes(SpaceListing)),
            "PUT" => HttpStub.Text("", 201),
            "GET" => HttpStub.Bytes(HttpStatusCode.OK, "{}"u8.ToArray()),
            "DELETE" => HttpStub.Text("", 404),
            _ => HttpStub.Text("", 500),
        });
        var connector = WebDavConnector.FromAddress("https://nas.example.com/dav/", new ServerLogin { Kind = "webdav", Host = "nas.example.com", Username = "me", Password = "pw" }, stub.Client)!;
        var location = new WebDavSyncLocation(connector);

        var entries = await location.ListAsync("Family", CancellationToken.None);
        Assert.AreEqual("\"etag-1\"", entries.Single().Stamp);
        Assert.AreEqual("MKCOL", stub.Requests[0].Method);
        Assert.AreEqual("https://nas.example.com/dav/Edendale/", stub.Requests[0].Uri.ToString());
        Assert.AreEqual("https://nas.example.com/dav/Edendale/Family/", stub.Requests[1].Uri.ToString());
        StringAssert.Contains(Encoding.UTF8.GetString(stub.Requests[2].Body), "getetag");

        await location.WriteAsync("Family", SyncMergeTests.A + ".json", "{\"x\":1}"u8.ToArray(), CancellationToken.None);
        var put = stub.Requests.Last();
        Assert.AreEqual("PUT", put.Method);
        Assert.AreEqual($"https://nas.example.com/dav/Edendale/Family/{SyncMergeTests.A}.json", put.Uri.ToString());
        Assert.AreEqual("{\"x\":1}", Encoding.UTF8.GetString(put.Body));
        Assert.AreEqual(1, stub.Requests.Count(r => r.Method == "MKCOL" && r.Uri.AbsolutePath.EndsWith("/Family/", StringComparison.Ordinal)), "the folders are created once");

        await location.DeleteAsync("Family", SyncMergeTests.A + ".json", CancellationToken.None); // 404 is fine
    }

    [TestMethod]
    public async Task WebDav_RefusesPlainHttpOutsideTheLocalNetwork()
    {
        var stub = new HttpStub(_ => HttpStub.Text("", 201));
        var connector = WebDavConnector.FromAddress("http://cloud.example.com/dav/", null, stub.Client)!;
        var error = await Assert.ThrowsExceptionAsync<ConnectorException>(() => new WebDavSyncLocation(connector).ListAsync("Family", CancellationToken.None));
        Assert.AreEqual(ConnectorFailure.InsecureConnection, error.Failure);
        Assert.AreEqual(0, stub.Requests.Count);
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task Folder_WritesThroughATemporaryFile_AndRefusesAMissingRoot()
    {
        var root = Path.Combine(_directory, "picked");
        Directory.CreateDirectory(root);
        var location = new FolderSyncLocation(root);
        Assert.AreEqual(0, (await location.ListAsync("Family", CancellationToken.None)).Count);
        Assert.IsTrue(Directory.Exists(Path.Combine(root, "Edendale", "Family")));
        await location.WriteAsync("Family", SyncMergeTests.A + ".json", "{}"u8.ToArray(), CancellationToken.None);
        var listed = await location.ListAsync("Family", CancellationToken.None);
        Assert.AreEqual(SyncMergeTests.A + ".json", listed.Single().Name);
        await location.DeleteAsync("Family", SyncMergeTests.A + ".json", CancellationToken.None);
        Assert.AreEqual(0, (await location.ListAsync("Family", CancellationToken.None)).Count);

        // A folder that is gone (an unplugged drive) is a failure, not an empty space.
        await Assert.ThrowsExceptionAsync<DirectoryNotFoundException>(() => new FolderSyncLocation(Path.Combine(_directory, "gone")).ListAsync("Family", CancellationToken.None));
    }

    // ------------------------------------------------------------------
    // Handoff URLs and provider names
    // ------------------------------------------------------------------

    [TestMethod]
    public void HandoffUrls_RoundTrip()
    {
        Assert.AreEqual("smb://nas/media/Shared%20Space/", SyncLocationUrls.SmbUrlFromUnc(@"\\nas\media\Shared Space"));
        Assert.AreEqual(@"\\nas\media\Shared Space", SyncLocationUrls.UncFromSmbUrl("smb://nas/media/Shared%20Space/"));
        Assert.AreEqual(@"\\nas\media", SyncLocationUrls.UncFromSmbUrl("smb://nas:445/media/"));
        Assert.IsNull(SyncLocationUrls.SmbUrlFromUnc(@"C:\Users"));
        Assert.AreEqual("davs://nas.example.com/dav/", SyncLocationUrls.FromHandoff(SyncLocationKind.WebDav, "davs://nas.example.com/dav/"));
        Assert.AreEqual("dav://nas.local/dav/", SyncLocationUrls.FromHandoff(SyncLocationKind.WebDav, "dav://nas.local/dav"));
        Assert.AreEqual("sftp://nas.local:2222/home/me/", SyncLocationUrls.FromHandoff(SyncLocationKind.Sftp, "sftp://nas.local:2222/home/me"));
        Assert.IsNull(SyncLocationUrls.FromHandoff(SyncLocationKind.Sftp, "https://nas.local/"));

        foreach (var kind in Enum.GetValues<SyncLocationKind>())
        {
            Assert.AreEqual(kind, SyncLocationSettings.KindFromProvider(SyncLocationSettings.ProviderNameOf(kind)));
        }
        Assert.AreEqual("googleDrive", SyncLocationSettings.ProviderNameOf(SyncLocationKind.GoogleDrive));
        Assert.AreEqual("oneDrive", SyncLocationSettings.ProviderNameOf(SyncLocationKind.OneDrive));
        Assert.AreEqual(SyncLocationKind.Folder, SyncLocationSettings.KindFromProvider("icloudDrive"), "Apple's iCloud Drive is a folder on Windows");
        Assert.IsNull(SyncLocationSettings.KindFromProvider("gdrive"));
    }

    // ------------------------------------------------------------------
    // 27.1 scopes (FEATURES-27.1.md §10.5)
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task Scopes_OldOneDriveTokensRefreshWithWhatTheyWereGranted()
    {
        var legacy = Account(MediaSourceKind.OneDrive, ["Files.Read", "User.Read"]);
        var current = Account(MediaSourceKind.OneDrive, ["https://graph.microsoft.com/Files.Read", "Files.ReadWrite.AppFolder"]);
        CollectionAssert.AreEqual(CloudProviders.OneDriveLegacyScopes.ToArray(), CloudProviders.RefreshScopes(legacy)!.ToArray());
        Assert.IsNull(CloudProviders.RefreshScopes(current), "a 27.1 account refreshes with the full list");
        Assert.IsFalse(CloudProviders.CanWriteSyncFiles(legacy));
        Assert.IsTrue(CloudProviders.CanWriteSyncFiles(current));
        Assert.IsTrue(CloudProviders.HasScope(["https://graph.microsoft.com/Files.ReadWrite.AppFolder"], "files.readwrite.appfolder"));

        Assert.IsFalse(CloudProviders.CanWriteSyncFiles(Account(MediaSourceKind.Dropbox, ["files.metadata.read", "files.content.read"])));
        Assert.IsTrue(CloudProviders.CanWriteSyncFiles(Account(MediaSourceKind.Dropbox, CloudProviders.DropboxScopes)));
        Assert.IsTrue(CloudProviders.CanWriteSyncFiles(Account(MediaSourceKind.GoogleDrive, CloudProviders.GoogleScopes)), "drive.file covers the app's own folder");

        // The token provider sends the legacy set for the legacy account.
        var vault = new CloudAccountVault(Path.Combine(_directory, "vault.bin"), new PlainProtector());
        vault.Save(legacy);
        var stub = new HttpStub(_ => HttpStub.Json(new Dictionary<string, object> { ["access_token"] = "a", ["expires_in"] = 3600 }));
        var configuration = CloudProviders.Configuration(MediaSourceKind.OneDrive, "client")!;
        var tokens = new CloudTokenProvider(vault, stub.Client, _ => configuration);
        await tokens.AccessTokenAsync(MediaSourceKind.OneDrive, legacy.Key);
        Assert.AreEqual("Files.Read User.Read offline_access", stub.Requests.Single().Form["scope"]);
    }

    [TestMethod]
    public void Scopes_GoogleSyncSignInSkipsThePicker_AndCarriesTheHint()
    {
        var hinted = CloudProviders.GoogleSyncParameters("person@example.com");
        Assert.AreEqual("person@example.com", hinted["login_hint"]);
        Assert.AreEqual("consent", hinted["prompt"]);
        Assert.AreEqual("offline", hinted["access_type"]);
        Assert.IsFalse(hinted.ContainsKey("trigger_onepick"));
        Assert.AreEqual("consent select_account", CloudProviders.GoogleSyncParameters(null)["prompt"]);

        var configuration = CloudProviders.Configuration(MediaSourceKind.GoogleDrive, "id.apps.googleusercontent.com", "secret")! with
        {
            AdditionalAuthorizationParameters = CloudProviders.GoogleSyncParameters("person@example.com"),
        };
        var url = new OAuthClient(configuration).AuthorizationUrl("http://127.0.0.1:1/", "s", "c");
        var query = OAuthClient.ParseQuery(new Uri(url).Query);
        Assert.AreEqual(CloudProviders.GoogleDriveScope, query["scope"]);
        Assert.IsFalse(query.ContainsKey("trigger_onepick"));
        Assert.AreEqual("person@example.com", query["login_hint"]);
    }
}
