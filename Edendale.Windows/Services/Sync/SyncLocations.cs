// The sync locations of SYNC-PROTOCOL.md §4, each able to list a space
// folder (creating Edendale/ and the space on the way), read and write one
// device file as a whole, and delete one. Every write replaces the file in
// one step: a temporary file renamed over the target on a file system or
// SFTP server, one upload on the API providers, one PUT on WebDAV. Stamps
// are whatever the location reports as "changed": a modification time and
// size, an ETag, Drive's modifiedTime, Graph's eTag, Dropbox's rev.
// Nothing here logs a URL, a token, or a file's contents.

using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;
using Edendale.Windows.Core;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Services.Remote;

namespace Edendale.Windows.Services.Sync;

/// <summary>One file in a space folder and the stamp that says whether it changed.</summary>
public sealed record SyncFileEntry(string Name, string Stamp);

public interface ISyncLocation
{
    /// <summary>What Settings shows; never a credential.</summary>
    string Description { get; }

    /// <summary>§8.2 step 1: the files of the space folder, after creating Edendale/ and the space folder if missing.</summary>
    Task<IReadOnlyList<SyncFileEntry>> ListAsync(string space, CancellationToken cancellation);

    Task<byte[]> ReadAsync(string space, string name, CancellationToken cancellation);

    /// <summary>Replaces the file as a whole (§4 "Write").</summary>
    Task WriteAsync(string space, string name, ReadOnlyMemory<byte> bytes, CancellationToken cancellation);

    /// <summary>Deletes one device file; a missing file is not an error (§8.3).</summary>
    Task DeleteAsync(string space, string name, CancellationToken cancellation);
}

/// <summary>A location that learns provider ids (Drive folder ids) worth keeping in the settings.</summary>
public interface IHasProviderIds
{
    SyncLocationSettings Apply(SyncLocationSettings settings);
}

// ----------------------------------------------------------------------
// Folders: a picked folder, the OneDrive or iCloud for Windows folder, an SMB share
// ----------------------------------------------------------------------

public sealed class FolderSyncLocation : ISyncLocation
{
    private readonly string _root;
    private readonly Func<CancellationToken, Task>? _connect;

    /// <param name="root">The picked folder or UNC path.</param>
    /// <param name="connect">For an SMB share: re-establishes the session with the saved login before each listing.</param>
    public FolderSyncLocation(string root, string? description = null, Func<CancellationToken, Task>? connect = null)
    {
        _root = root;
        Description = description ?? root;
        _connect = connect;
    }

    public string Description { get; }

    private string SpaceDirectory(string space) => Path.Combine(_root, SyncFormat.RootFolderName, space);

    public async Task<IReadOnlyList<SyncFileEntry>> ListAsync(string space, CancellationToken cancellation)
    {
        if (_connect is not null) await _connect(cancellation).ConfigureAwait(false);
        return await Task.Run(() =>
        {
            var directory = SpaceDirectory(space);
            if (!Directory.Exists(_root)) throw new DirectoryNotFoundException(_root);
            Directory.CreateDirectory(directory);
            var entries = new List<SyncFileEntry>();
            foreach (var path in Directory.EnumerateFiles(directory, "*.json"))
            {
                var info = new FileInfo(path);
                entries.Add(new SyncFileEntry(info.Name, Stamp(info)));
            }
            return (IReadOnlyList<SyncFileEntry>)entries;
        }, cancellation).ConfigureAwait(false);
    }

    private static string Stamp(FileInfo info) =>
        $"{info.LastWriteTimeUtc.Ticks.ToString(CultureInfo.InvariantCulture)}:{info.Length.ToString(CultureInfo.InvariantCulture)}";

    public Task<byte[]> ReadAsync(string space, string name, CancellationToken cancellation) =>
        File.ReadAllBytesAsync(Path.Combine(SpaceDirectory(space), name), cancellation);

    public async Task WriteAsync(string space, string name, ReadOnlyMemory<byte> bytes, CancellationToken cancellation)
    {
        var directory = SpaceDirectory(space);
        Directory.CreateDirectory(directory);
        var target = Path.Combine(directory, name);
        var temporary = target + ".tmp";
        await File.WriteAllBytesAsync(temporary, bytes.ToArray(), cancellation).ConfigureAwait(false);
        File.Move(temporary, target, overwrite: true);
    }

    public Task DeleteAsync(string space, string name, CancellationToken cancellation) => Task.Run(() =>
    {
        var path = Path.Combine(SpaceDirectory(space), name);
        if (File.Exists(path)) File.Delete(path);
    }, cancellation);
}

// ----------------------------------------------------------------------
// WebDAV: MKCOL, PROPFIND depth 1 (ETags), PUT, GET, DELETE through the connector
// ----------------------------------------------------------------------

public sealed class WebDavSyncLocation : ISyncLocation
{
    private readonly WebDavConnector _connector;
    private string? _knownSpace;

    /// <param name="connector">The connector for the folder that holds Edendale/, with the saved login.</param>
    public WebDavSyncLocation(WebDavConnector connector)
    {
        _connector = connector;
        Description = $"WebDAV · {connector.Host}";
    }

    public string Description { get; }

    private string RootFolder => WebDavConnector.ChildUrl(_connector.Root, SyncFormat.RootFolderName, isDirectory: true);
    private string SpaceFolder(string space) => WebDavConnector.ChildUrl(RootFolder, space, isDirectory: true);
    private string FileUrl(string space, string name) => WebDavConnector.ChildUrl(SpaceFolder(space), name, isDirectory: false);

    private async Task EnsureSpaceAsync(string space, CancellationToken cancellation)
    {
        if (_knownSpace == space) return;
        await _connector.CreateCollectionAsync(RootFolder, cancellation).ConfigureAwait(false);
        await _connector.CreateCollectionAsync(SpaceFolder(space), cancellation).ConfigureAwait(false);
        _knownSpace = space;
    }

    public async Task<IReadOnlyList<SyncFileEntry>> ListAsync(string space, CancellationToken cancellation)
    {
        await EnsureSpaceAsync(space, cancellation).ConfigureAwait(false);
        var entries = await _connector.ListAsync(SpaceFolder(space), cancellation).ConfigureAwait(false);
        return entries
            .Where(entry => !entry.IsDirectory)
            .Select(entry => new SyncFileEntry(entry.Name, entry.ETag ?? $"{entry.Modified?.UtcTicks}:{entry.Size}"))
            .ToList();
    }

    public Task<byte[]> ReadAsync(string space, string name, CancellationToken cancellation) =>
        _connector.ReadFileAsync(FileUrl(space, name), cancellation);

    public async Task WriteAsync(string space, string name, ReadOnlyMemory<byte> bytes, CancellationToken cancellation)
    {
        await EnsureSpaceAsync(space, cancellation).ConfigureAwait(false);
        await _connector.WriteFileAsync(FileUrl(space, name), bytes, "application/json", cancellation).ConfigureAwait(false);
    }

    public Task DeleteAsync(string space, string name, CancellationToken cancellation) =>
        _connector.DeleteFileAsync(FileUrl(space, name), cancellation);
}

// ----------------------------------------------------------------------
// SFTP: folders created as needed, <name>.tmp renamed over the target, through the connector
// ----------------------------------------------------------------------

public sealed class SftpSyncLocation : ISyncLocation, IDisposable
{
    private readonly SftpConnector _connector;
    private readonly string _basePath;
    private string? _knownSpace;

    /// <param name="connector">A connector with the saved login; its host key must already be pinned.</param>
    /// <param name="basePath">The folder on the server that holds Edendale/.</param>
    public SftpSyncLocation(SftpConnector connector, string basePath)
    {
        _connector = connector;
        _basePath = basePath.TrimEnd('/');
        Description = $"SFTP · {connector.Host}";
    }

    public string Description { get; }

    private string SpacePath(string space) => $"{_basePath}/{SyncFormat.RootFolderName}/{space}";

    public async Task<IReadOnlyList<SyncFileEntry>> ListAsync(string space, CancellationToken cancellation)
    {
        if (_knownSpace != space)
        {
            await _connector.CreateDirectoriesAsync(SpacePath(space), cancellation).ConfigureAwait(false);
            _knownSpace = space;
        }
        var entries = await _connector.ListAsync(_connector.UrlFor(SpacePath(space), isDirectory: true), cancellation).ConfigureAwait(false);
        return entries
            .Where(entry => !entry.IsDirectory)
            .Select(entry => new SyncFileEntry(entry.Name, $"{entry.Modified?.UtcTicks.ToString(CultureInfo.InvariantCulture)}:{entry.Size?.ToString(CultureInfo.InvariantCulture)}"))
            .ToList();
    }

    public Task<byte[]> ReadAsync(string space, string name, CancellationToken cancellation) =>
        _connector.ReadFileAsync($"{SpacePath(space)}/{name}", cancellation);

    public async Task WriteAsync(string space, string name, ReadOnlyMemory<byte> bytes, CancellationToken cancellation)
    {
        if (_knownSpace != space)
        {
            await _connector.CreateDirectoriesAsync(SpacePath(space), cancellation).ConfigureAwait(false);
            _knownSpace = space;
        }
        await _connector.WriteFileAsync($"{SpacePath(space)}/{name}", bytes, cancellation).ConfigureAwait(false);
    }

    public Task DeleteAsync(string space, string name, CancellationToken cancellation) =>
        _connector.DeleteFileAsync($"{SpacePath(space)}/{name}", cancellation);

    public void Dispose() => _connector.Dispose();
}

// ----------------------------------------------------------------------
// Google Drive: files.list / files.create / files.update with uploadType=media
// ----------------------------------------------------------------------

public sealed class GoogleDriveSyncLocation : ISyncLocation, IHasProviderIds
{
    private const string UploadBase = "https://www.googleapis.com/upload/drive/v3/";
    private const string FolderMime = GoogleDriveConnector.FolderMimeType;

    private readonly ProviderHttp _http;
    private readonly object _gate = new();
    private readonly Dictionary<string, string> _fileIds = new(StringComparer.Ordinal);
    private string? _rootId;
    private string? _spaceId;
    private string? _spaceName;

    public GoogleDriveSyncLocation(CloudAccount account, CloudTokenProvider tokens, string? rootFolderId, string? spaceFolderId, HttpMessageInvoker? client = null)
    {
        _http = new ProviderHttp(MediaSourceKind.GoogleDrive, account.Key, tokens, client);
        _rootId = rootFolderId;
        _spaceId = spaceFolderId;
        Description = $"Google Drive · {account.Label}";
    }

    public string Description { get; }

    public SyncLocationSettings Apply(SyncLocationSettings settings) =>
        settings with { GoogleRootFolderId = _rootId ?? settings.GoogleRootFolderId, GoogleFolderId = _spaceId ?? settings.GoogleFolderId };

    public async Task<IReadOnlyList<SyncFileEntry>> ListAsync(string space, CancellationToken cancellation)
    {
        var spaceId = await SpaceFolderIdAsync(space, cancellation).ConfigureAwait(false);
        var entries = new List<SyncFileEntry>();
        string? pageToken = null;
        var ids = new Dictionary<string, string>(StringComparer.Ordinal);
        do
        {
            var parameters = new List<(string, string)>
            {
                ("q", $"'{spaceId}' in parents and trashed = false"),
                ("fields", "nextPageToken,files(id,name,modifiedTime,size,mimeType)"),
                ("pageSize", "1000"),
            };
            if (pageToken is not null) parameters.Add(("pageToken", pageToken));
            var url = GoogleDriveConnector.Url("files", parameters);
            using var page = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
            foreach (var file in page.RootElement.Array("files"))
            {
                if (file.String("id") is not { } id || file.String("name") is not { } name) continue;
                if (file.String("mimeType") == FolderMime) continue;
                ids[name] = id;
                entries.Add(new SyncFileEntry(name, $"{file.String("modifiedTime")}:{file.String("size")}"));
            }
            pageToken = page.RootElement.String("nextPageToken");
        }
        while (pageToken is not null);
        lock (_gate)
        {
            _fileIds.Clear();
            foreach (var (name, id) in ids) _fileIds[name] = id;
        }
        return entries;
    }

    private async Task<string> SpaceFolderIdAsync(string space, CancellationToken cancellation)
    {
        lock (_gate)
        {
            // An id from the settings or a handoff belongs to the settings' space.
            if (_spaceId is not null && (_spaceName is null || _spaceName == space))
            {
                _spaceName = space;
                return _spaceId;
            }
        }
        _rootId ??= await FindOrCreateFolderAsync(SyncFormat.RootFolderName, "root", cancellation).ConfigureAwait(false);
        var spaceId = await FindOrCreateFolderAsync(space, _rootId, cancellation).ConfigureAwait(false);
        lock (_gate)
        {
            _spaceId = spaceId;
            _spaceName = space;
        }
        return spaceId;
    }

    private async Task<string> FindOrCreateFolderAsync(string name, string parentId, CancellationToken cancellation)
    {
        var escaped = name.Replace("\\", "\\\\").Replace("'", "\\'");
        var query = GoogleDriveConnector.Url("files",
        [
            ("q", $"name = '{escaped}' and mimeType = '{FolderMime}' and '{parentId}' in parents and trashed = false"),
            ("fields", "files(id)"),
            ("pageSize", "1"),
        ]);
        using (var found = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, query), cancellation).ConfigureAwait(false))
        {
            if (found.RootElement.Array("files").FirstOrDefault() is { ValueKind: JsonValueKind.Object } existing && existing.String("id") is { } id)
            {
                return id;
            }
        }
        var body = new Dictionary<string, object> { ["name"] = name, ["mimeType"] = FolderMime, ["parents"] = new[] { parentId } };
        using var created = await _http.JsonAsync(
            () => ProviderHttp.JsonRequest(GoogleDriveConnector.Url("files", [("fields", "id")]), body), cancellation).ConfigureAwait(false);
        return created.RootElement.String("id") ?? throw new ConnectorException(ConnectorFailure.ServerError, "Google Drive", 200);
    }

    public async Task<byte[]> ReadAsync(string space, string name, CancellationToken cancellation)
    {
        var id = FileId(name) ?? throw new ConnectorException(ConnectorFailure.NotFound, "Google Drive");
        var url = GoogleDriveConnector.Url($"files/{Uri.EscapeDataString(id)}", [("alt", "media")]);
        return await _http.SendAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
    }

    public async Task WriteAsync(string space, string name, ReadOnlyMemory<byte> bytes, CancellationToken cancellation)
    {
        var spaceId = await SpaceFolderIdAsync(space, cancellation).ConfigureAwait(false);
        var existing = FileId(name);
        if (existing is not null)
        {
            var url = $"{UploadBase}files/{Uri.EscapeDataString(existing)}?uploadType=media&fields=id";
            await _http.SendAsync(() => new HttpRequestMessage(HttpMethod.Patch, url) { Content = JsonContent(bytes) }, cancellation).ConfigureAwait(false);
            return;
        }
        var metadata = JsonSerializer.Serialize(new { name, parents = new[] { spaceId } });
        using var created = await _http.JsonAsync(() =>
        {
            var multipart = new MultipartContent("related");
            multipart.Add(new StringContent(metadata, Encoding.UTF8, "application/json"));
            multipart.Add(JsonContent(bytes));
            return new HttpRequestMessage(HttpMethod.Post, $"{UploadBase}files?uploadType=multipart&fields=id") { Content = multipart };
        }, cancellation).ConfigureAwait(false);
        if (created.RootElement.String("id") is { } id)
        {
            lock (_gate) _fileIds[name] = id;
        }
    }

    public async Task DeleteAsync(string space, string name, CancellationToken cancellation)
    {
        if (FileId(name) is null) await ListAsync(space, cancellation).ConfigureAwait(false);
        var id = FileId(name);
        if (id is null) return;
        try
        {
            await _http.SendAsync(() => new HttpRequestMessage(HttpMethod.Delete, GoogleDriveConnector.Url($"files/{Uri.EscapeDataString(id)}", [])), cancellation).ConfigureAwait(false);
        }
        catch (ConnectorException error) when (error.Failure == ConnectorFailure.NotFound)
        {
            // Already gone.
        }
        lock (_gate) _fileIds.Remove(name);
    }

    private string? FileId(string name)
    {
        lock (_gate) return _fileIds.GetValueOrDefault(name);
    }

    private static ReadOnlyMemoryContent JsonContent(ReadOnlyMemory<byte> bytes)
    {
        var content = new ReadOnlyMemoryContent(bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        return content;
    }
}

// ----------------------------------------------------------------------
// OneDrive: the app folder, Apps/Edendale/ (Files.ReadWrite.AppFolder)
// ----------------------------------------------------------------------

public sealed class OneDriveSyncLocation : ISyncLocation
{
    private const string Api = OneDriveConnector.ApiBase;
    private const long SimpleUploadLimit = 4 * 1024 * 1024;

    private readonly ProviderHttp _http;
    private bool _spaceKnown;

    public OneDriveSyncLocation(CloudAccount account, CloudTokenProvider tokens, HttpMessageInvoker? client = null)
    {
        _http = new ProviderHttp(MediaSourceKind.OneDrive, account.Key, tokens, client);
        Description = $"OneDrive · {account.Label}";
    }

    public string Description { get; }

    /// <summary>`/me/drive/special/approot:/&lt;space&gt;` and `…/&lt;space&gt;/&lt;name&gt;`.</summary>
    public static string ItemPath(string space, string? name = null) =>
        $"{Api}me/drive/special/approot:/{Uri.EscapeDataString(space)}" + (name is null ? "" : "/" + Uri.EscapeDataString(name));

    public async Task<IReadOnlyList<SyncFileEntry>> ListAsync(string space, CancellationToken cancellation)
    {
        if (!_spaceKnown) await EnsureSpaceAsync(space, cancellation).ConfigureAwait(false);
        var entries = new List<SyncFileEntry>();
        string? next = ItemPath(space) + ":/children?$select=id,name,eTag,lastModifiedDateTime,size,file,folder&$top=200";
        while (next is not null)
        {
            var url = next;
            using var page = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
            foreach (var item in page.RootElement.Array("value"))
            {
                if (item.Property("folder") is not null || item.String("name") is not { } name) continue;
                entries.Add(new SyncFileEntry(name, item.String("eTag") ?? $"{item.String("lastModifiedDateTime")}:{item.Property("size")?.GetRawText()}"));
            }
            next = page.RootElement.String("@odata.nextLink");
        }
        return entries;
    }

    private async Task EnsureSpaceAsync(string space, CancellationToken cancellation)
    {
        try
        {
            using var _ = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, ItemPath(space) + "?$select=id"), cancellation).ConfigureAwait(false);
        }
        catch (ConnectorException error) when (error.Failure == ConnectorFailure.NotFound)
        {
            var body = new Dictionary<string, object> { ["name"] = space, ["folder"] = new Dictionary<string, object>(), ["@microsoft.graph.conflictBehavior"] = "fail" };
            try
            {
                using var _ = await _http.JsonAsync(() => ProviderHttp.JsonRequest($"{Api}me/drive/special/approot/children", body), cancellation).ConfigureAwait(false);
            }
            catch (ConnectorException conflict) when (conflict.Status == 409)
            {
                // Created by another device in the meantime.
            }
        }
        _spaceKnown = true;
    }

    public Task<byte[]> ReadAsync(string space, string name, CancellationToken cancellation) =>
        _http.SendAsync(() => new HttpRequestMessage(HttpMethod.Get, ItemPath(space, name) + ":/content"), cancellation);

    public async Task WriteAsync(string space, string name, ReadOnlyMemory<byte> bytes, CancellationToken cancellation)
    {
        if (!_spaceKnown) await EnsureSpaceAsync(space, cancellation).ConfigureAwait(false);
        if (bytes.Length < SimpleUploadLimit)
        {
            await _http.SendAsync(() => new HttpRequestMessage(HttpMethod.Put, ItemPath(space, name) + ":/content")
            {
                Content = Content(bytes),
            }, cancellation).ConfigureAwait(false);
            return;
        }
        // Above 4 MiB: an upload session, sent in one range.
        var session = new Dictionary<string, object> { ["item"] = new Dictionary<string, object> { ["@microsoft.graph.conflictBehavior"] = "replace" } };
        string uploadUrl;
        using (var created = await _http.JsonAsync(() => ProviderHttp.JsonRequest(ItemPath(space, name) + ":/createUploadSession", session), cancellation).ConfigureAwait(false))
        {
            uploadUrl = created.RootElement.String("uploadUrl") ?? throw new ConnectorException(ConnectorFailure.ServerError, "OneDrive", 200);
        }
        using var request = new HttpRequestMessage(HttpMethod.Put, uploadUrl) { Content = Content(bytes) };
        request.Content!.Headers.ContentRange = new ContentRangeHeaderValue(0, bytes.Length - 1, bytes.Length);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromMinutes(2));
        using var response = await RemoteHttp.Shared.SendAsync(request, timeout.Token).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) throw new ConnectorException(ConnectorFailure.ServerError, "OneDrive", (int)response.StatusCode);
    }

    public async Task DeleteAsync(string space, string name, CancellationToken cancellation)
    {
        try
        {
            await _http.SendAsync(() => new HttpRequestMessage(HttpMethod.Delete, ItemPath(space, name)), cancellation).ConfigureAwait(false);
        }
        catch (ConnectorException error) when (error.Failure == ConnectorFailure.NotFound)
        {
            // Already gone.
        }
    }

    private static ReadOnlyMemoryContent Content(ReadOnlyMemory<byte> bytes)
    {
        var content = new ReadOnlyMemoryContent(bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        return content;
    }
}

// ----------------------------------------------------------------------
// Dropbox: /Edendale/<space>/ with files/upload mode overwrite
// ----------------------------------------------------------------------

public sealed class DropboxSyncLocation : ISyncLocation
{
    private const string Api = DropboxConnector.ApiBase;
    private const string ContentApi = "https://content.dropboxapi.com/2/";

    private readonly ProviderHttp _http;
    private bool _spaceKnown;

    public DropboxSyncLocation(CloudAccount account, CloudTokenProvider tokens, HttpMessageInvoker? client = null)
    {
        _http = new ProviderHttp(MediaSourceKind.Dropbox, account.Key, tokens, client);
        Description = $"Dropbox · {account.Label}";
    }

    public string Description { get; }

    public static string SpacePath(string space) => $"/{SyncFormat.RootFolderName}/{space}";

    public async Task<IReadOnlyList<SyncFileEntry>> ListAsync(string space, CancellationToken cancellation)
    {
        if (!_spaceKnown) await EnsureSpaceAsync(space, cancellation).ConfigureAwait(false);
        var entries = new List<SyncFileEntry>();
        var body = new Dictionary<string, object> { ["path"] = SpacePath(space), ["recursive"] = false, ["include_deleted"] = false, ["limit"] = 2000 };
        using var first = await _http.JsonAsync(() => ProviderHttp.JsonRequest(Api + "files/list_folder", body), cancellation).ConfigureAwait(false);
        var (cursor, hasMore) = Collect(first.RootElement, entries);
        while (hasMore)
        {
            var next = new Dictionary<string, object> { ["cursor"] = cursor ?? "" };
            using var page = await _http.JsonAsync(() => ProviderHttp.JsonRequest(Api + "files/list_folder/continue", next), cancellation).ConfigureAwait(false);
            (cursor, hasMore) = Collect(page.RootElement, entries);
        }
        return entries;
    }

    private static (string? Cursor, bool HasMore) Collect(JsonElement page, List<SyncFileEntry> entries)
    {
        foreach (var item in page.Array("entries"))
        {
            if (item.String(".tag") != "file" || item.String("name") is not { } name) continue;
            entries.Add(new SyncFileEntry(name, item.String("rev") ?? $"{item.String("server_modified")}:{item.Property("size")?.GetRawText()}"));
        }
        return (page.String("cursor"), page.Bool("has_more") == true);
    }

    private async Task EnsureSpaceAsync(string space, CancellationToken cancellation)
    {
        var body = new Dictionary<string, object> { ["path"] = SpacePath(space), ["autorename"] = false };
        try
        {
            using var _ = await _http.JsonAsync(() => ProviderHttp.JsonRequest(Api + "files/create_folder_v2", body), cancellation).ConfigureAwait(false);
        }
        catch (ConnectorException error) when (error.Status == 409)
        {
            // path/conflict/folder: it exists.
        }
        _spaceKnown = true;
    }

    public Task<byte[]> ReadAsync(string space, string name, CancellationToken cancellation) =>
        _http.SendAsync(() =>
        {
            var request = new HttpRequestMessage(HttpMethod.Post, ContentApi + "files/download");
            request.Headers.Add("Dropbox-API-Arg", JsonSerializer.Serialize(new { path = $"{SpacePath(space)}/{name}" }));
            return request;
        }, cancellation);

    public async Task WriteAsync(string space, string name, ReadOnlyMemory<byte> bytes, CancellationToken cancellation)
    {
        if (!_spaceKnown) await EnsureSpaceAsync(space, cancellation).ConfigureAwait(false);
        await _http.SendAsync(() =>
        {
            var request = new HttpRequestMessage(HttpMethod.Post, ContentApi + "files/upload")
            {
                Content = new ReadOnlyMemoryContent(bytes),
            };
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            request.Headers.Add("Dropbox-API-Arg", JsonSerializer.Serialize(new { path = $"{SpacePath(space)}/{name}", mode = "overwrite", mute = true }));
            return request;
        }, cancellation).ConfigureAwait(false);
    }

    public async Task DeleteAsync(string space, string name, CancellationToken cancellation)
    {
        var body = new Dictionary<string, object> { ["path"] = $"{SpacePath(space)}/{name}" };
        try
        {
            using var _ = await _http.JsonAsync(() => ProviderHttp.JsonRequest(Api + "files/delete_v2", body), cancellation).ConfigureAwait(false);
        }
        catch (ConnectorException error) when (error.Failure == ConnectorFailure.NotFound)
        {
            // Already gone.
        }
    }
}

// ----------------------------------------------------------------------
// Factory and URLs
// ----------------------------------------------------------------------

/// <summary>Builds the location for the saved settings from the login stores; null when its login is gone.</summary>
public static class SyncLocationFactory
{
    /// <summary>
    /// <paramref name="connectSmb"/> re-establishes an SMB session for a UNC
    /// path with the saved login (the app passes NetworkShare; tests pass
    /// nothing). <paramref name="client"/> is for tests.
    /// </summary>
    public static ISyncLocation? Create(
        SyncLocationSettings settings,
        ConnectorEnvironment environment,
        Func<string, CancellationToken, Task>? connectSmb = null,
        HttpMessageInvoker? client = null)
    {
        switch (settings.Kind)
        {
            case SyncLocationKind.Folder:
                return settings.Path is { Length: > 0 } folder ? new FolderSyncLocation(folder, FolderDescription(folder)) : null;
            case SyncLocationKind.Smb:
                return settings.Path is { Length: > 0 } unc
                    ? new FolderSyncLocation(unc, $"SMB · {SmbCredentialsStoreHost(unc) ?? unc}", connectSmb is null ? null : cancellation => connectSmb(unc, cancellation))
                    : null;
            case SyncLocationKind.WebDav:
            {
                if (settings.Path is not { Length: > 0 } root || SourceUrl.CredentialHost(root) is not { } host) return null;
                var login = environment.Logins.Get(MediaSourceKind.WebDav, host);
                var connector = client is null
                    ? WebDavConnector.FromSource(root, environment.Logins)
                    : WebDavConnector.FromAddress(WebDavConnector.HttpUrl(root) ?? root, login, client);
                return connector is null ? null : new WebDavSyncLocation(connector);
            }
            case SyncLocationKind.Sftp:
            {
                if (settings.Path is not { Length: > 0 } root) return null;
                var connector = SftpConnector.FromSource(root, environment.Logins, environment.HostKeys);
                return connector is null ? null : new SftpSyncLocation(connector, SftpConnector.PathOf(root));
            }
            case SyncLocationKind.GoogleDrive:
                return settings.AccountKey is { } driveKey && environment.Accounts.Account(MediaSourceKind.GoogleDrive, driveKey) is { } drive
                    ? new GoogleDriveSyncLocation(drive, environment.Tokens, settings.GoogleRootFolderId, settings.GoogleFolderId, client)
                    : null;
            case SyncLocationKind.OneDrive:
                return settings.AccountKey is { } oneDriveKey && environment.Accounts.Account(MediaSourceKind.OneDrive, oneDriveKey) is { } oneDrive
                    ? new OneDriveSyncLocation(oneDrive, environment.Tokens, client)
                    : null;
            case SyncLocationKind.Dropbox:
                return settings.AccountKey is { } dropboxKey && environment.Accounts.Account(MediaSourceKind.Dropbox, dropboxKey) is { } dropbox
                    ? new DropboxSyncLocation(dropbox, environment.Tokens, client)
                    : null;
            default:
                return null;
        }
    }

    private static string? SmbCredentialsStoreHost(string unc) =>
        unc.StartsWith(@"\\", StringComparison.Ordinal) ? unc[2..].Split('\\', 2)[0] : null;

    /// <summary>
    /// The OneDrive preset's root: %OneDrive%\Apps, so the files land in
    /// Apps\Edendale\&lt;space&gt;\, the same folder the OneDrive API location
    /// uses (its app folder) and the one the pre-27.1 replica lived in.
    /// </summary>
    public static string? OneDrivePresetRoot => AppPaths.OneDriveFolder is { } oneDrive ? Path.Combine(oneDrive, "Apps") : null;

    /// <summary>The iCloud for Windows preset's root: %USERPROFILE%\iCloudDrive.</summary>
    public static string? ICloudPresetRoot => AppPaths.ICloudDriveFolder;

    /// <summary>"OneDrive folder on this PC", "iCloud Drive folder on this PC", or the path.</summary>
    public static string FolderDescription(string folder)
    {
        static bool Same(string a, string? b) =>
            b is not null && string.Equals(Path.TrimEndingDirectorySeparator(a), Path.TrimEndingDirectorySeparator(b), StringComparison.OrdinalIgnoreCase);
        if (Same(folder, OneDrivePresetRoot) || Same(folder, AppPaths.OneDriveFolder)) return AppText.Get("Sync_FolderOneDrive");
        if (Same(folder, ICloudPresetRoot)) return AppText.Get("Sync_FolderICloud");
        return folder;
    }
}

/// <summary>
/// The credential-free root URL a handoff carries for a server location
/// (`offer.location.url`, the spelling the Apple and Android branches chose,
/// since SYNC-PROTOCOL.md §9.6 leaves it out): `davs://host[:port]/path/`,
/// `smb://host[:port]/share/path/`, `sftp://host[:port]/path/`.
/// </summary>
public static class SyncLocationUrls
{
    /// <summary>\\host\share\path → smb://host/share/path/.</summary>
    public static string? SmbUrlFromUnc(string unc)
    {
        if (!unc.StartsWith(@"\\", StringComparison.Ordinal)) return null;
        var parts = unc[2..].Split('\\', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 2) return null;
        return SourceUrl.Server("smb", parts[0], null, parts[1..], isDirectory: true);
    }

    /// <summary>smb://host[:port]/share/path/ → \\host\share\path (Windows' SMB client always uses port 445).</summary>
    public static string? UncFromSmbUrl(string url)
    {
        if (!SourceUrl.TrySplit(url, out var parts) || !parts.Scheme.Equals("smb", StringComparison.OrdinalIgnoreCase)) return null;
        var segments = SourceUrl.PathSegments(url);
        if (parts.Host.Length == 0 || segments.Count == 0) return null;
        return @"\\" + parts.Host + @"\" + string.Join(@"\", segments);
    }

    /// <summary>The URL a handoff sends for a location, or null for folders and cloud accounts.</summary>
    public static string? ForHandoff(SyncLocationSettings settings) => settings.Kind switch
    {
        SyncLocationKind.Smb => settings.Path is { } unc ? SmbUrlFromUnc(unc) : null,
        SyncLocationKind.WebDav or SyncLocationKind.Sftp => settings.Path,
        _ => null,
    };

    /// <summary>What a received URL becomes in the settings: a UNC path for SMB, the canonical URL otherwise.</summary>
    public static string? FromHandoff(SyncLocationKind kind, string? url) => url is null ? null : kind switch
    {
        SyncLocationKind.Smb => UncFromSmbUrl(url),
        SyncLocationKind.WebDav => WebDavConnector.CanonicalRoot(WebDavConnector.HttpUrl(url) ?? url),
        SyncLocationKind.Sftp => SourceUrl.TrySplit(url, out var parts) && parts.Scheme.Equals("sftp", StringComparison.OrdinalIgnoreCase)
            ? SourceUrl.Server("sftp", parts.Host, parts.Port, SourceUrl.PathSegments(url), isDirectory: true)
            : null,
        _ => null,
    };
}
