// OneDrive (Microsoft Graph, delegated Files.Read) and Dropbox (API v2,
// scoped files.metadata.read and files.content.read) connectors and their
// streaming resolvers (OneDriveConnector.swift, DropboxConnector.swift).
//
// OneDrive item URLs are onedrive://<account>/<driveId>/<itemId>/<Name.ext>;
// folders shared into the drive (remoteItem) are followed into the drive
// they live in. Files stream from @microsoft.graph.downloadUrl, a
// pre-authenticated link that needs no Authorization header, expires within
// minutes, and takes Range itself (or ignores it at offset 0).
//
// Dropbox item URLs are dropbox://<account>/<fileId>/<Name.ext> with the
// percent-encoded id:…; the root is the virtual id "root". Imports list a
// whole folder in one recursive list_folder (plus continue pages). Files
// stream from get_temporary_link, which lasts four hours and then answers
// 410 Gone, when the byte source resolves a new link.

using System.Globalization;
using System.Text.Json;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

public sealed class OneDriveConnector : IMediaConnector
{
    public const string ApiBase = "https://graph.microsoft.com/v1.0/";
    public const string RootItem = "root";

    private readonly CloudAccount _account;
    private readonly ProviderHttp _http;
    private readonly string _driveId;

    private OneDriveConnector(CloudAccount account, string driveId, CloudTokenProvider tokens, HttpMessageInvoker? client)
    {
        _account = account;
        _driveId = driveId;
        _http = new ProviderHttp(MediaSourceKind.OneDrive, account.Key, tokens, client);
    }

    /// <summary>Null when the account predates knowing its drive (sign in again).</summary>
    public static OneDriveConnector? Create(CloudAccount account, CloudTokenProvider tokens, HttpMessageInvoker? client = null) =>
        account.DriveId is { Length: > 0 } driveId ? new OneDriveConnector(account, driveId, tokens, client) : null;

    public MediaSourceKind Kind => MediaSourceKind.OneDrive;
    public string Root => SourceUrl.AccountItem(MediaSourceKind.OneDrive, _account.Key, [_driveId, RootItem], "OneDrive");
    public string? AccountLabel => _account.Label;

    public Task ValidateAsync(CancellationToken cancellation) => ListAsync(Root, cancellation);

    public Task<IReadOnlyList<ConnectorEntry>> EnumerateVideosAsync(string folder, CancellationToken cancellation) =>
        ConnectorWalk.VideosAsync(folder, ListAsync, cancellation);

    public bool CanIndex(string directory) => true;

    public async Task<IReadOnlyList<ConnectorEntry>> ListAsync(string directory, CancellationToken cancellation)
    {
        if (SourceUrl.ParseAccountItem(directory) is not { Kind: MediaSourceKind.OneDrive, Ids.Count: 2 } item)
        {
            throw new ConnectorException(ConnectorFailure.InvalidAddress);
        }
        var entries = new List<ConnectorEntry>();
        string? next = ChildrenUrl(item.Ids[0], item.Ids[1]);
        while (next is not null)
        {
            var url = next;
            using var page = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
            var root = page.RootElement;
            if (root.Property("value") is { ValueKind: JsonValueKind.Array } values)
            {
                foreach (var value in values.EnumerateArray())
                {
                    if (Entry(value) is { } entry) entries.Add(entry);
                }
            }
            next = root.String("@odata.nextLink");
        }
        return ConnectorWalk.Sorted(entries);
    }

    /// <summary>Folders and files become entries; OneNote packages are skipped.</summary>
    private ConnectorEntry? Entry(JsonElement item)
    {
        if (item.Property("package") is not null) return null;
        if (item.String("id") is not { } id || item.String("name") is not { } name) return null;
        var driveId = item.Property("parentReference") is { } parent ? parent.String("driveId") ?? _driveId : _driveId;
        var isFolder = item.Property("folder") is not null;
        var isFile = item.Property("file") is not null;
        var size = item.Property("size") is { ValueKind: JsonValueKind.Number } sizeValue ? sizeValue.GetInt64() : (long?)null;
        var video = item.Property("video");
        if (item.Property("remoteItem") is { ValueKind: JsonValueKind.Object } remote)
        {
            // A folder shared into this drive lives in another one.
            driveId = remote.Property("parentReference") is { } remoteParent ? remoteParent.String("driveId") ?? driveId : driveId;
            id = remote.String("id") ?? id;
            isFolder = remote.Property("folder") is not null;
            isFile = remote.Property("file") is not null;
            if (remote.Property("size") is { ValueKind: JsonValueKind.Number } remoteSize) size = remoteSize.GetInt64();
            video = remote.Property("video") ?? video;
        }
        if (!isFolder && !isFile) return null;
        DateTimeOffset? modified = DateTimeOffset.TryParse(item.String("lastModifiedDateTime"), CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal, out var date) ? date : null;
        return new ConnectorEntry(name, SourceUrl.AccountItem(MediaSourceKind.OneDrive, _account.Key, [driveId, id], name), isFolder)
        {
            Size = isFolder ? null : size,
            Modified = modified,
            // Milliseconds in Graph, so items show a runtime before enrichment.
            Duration = video?.Property("duration") is { ValueKind: JsonValueKind.Number } duration ? duration.GetDouble() / 1000 : null,
        };
    }

    public static string ChildrenUrl(string driveId, string itemId)
    {
        var path = itemId == RootItem
            ? $"drives/{Uri.EscapeDataString(driveId)}/root/children"
            : $"drives/{Uri.EscapeDataString(driveId)}/items/{Uri.EscapeDataString(itemId)}/children";
        return $"{ApiBase}{path}?$select=id,name,size,folder,file,package,video,lastModifiedDateTime,parentReference,remoteItem&$top=200";
    }

    public static string ItemUrl(string driveId, string itemId) =>
        $"{ApiBase}drives/{Uri.EscapeDataString(driveId)}/items/{Uri.EscapeDataString(itemId)}";
}

/// <summary>Streams from the item's pre-authenticated download URL, fetching a new one when it expires.</summary>
public sealed class OneDriveContentResolver : IRemoteContentResolver
{
    private readonly string _driveId;
    private readonly string _itemId;
    private readonly ProviderHttp _http;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private string? _link;

    public OneDriveContentResolver(string driveId, string itemId, string accountKey, CloudTokenProvider tokens, HttpMessageInvoker? client = null)
    {
        _driveId = driveId;
        _itemId = itemId;
        _http = new ProviderHttp(MediaSourceKind.OneDrive, accountKey, tokens, client);
        Client = client ?? RemoteHttp.Shared;
    }

    public MediaSourceKind Kind => MediaSourceKind.OneDrive;
    public bool UsesPreauthorizedLinks => true;
    public HttpMessageInvoker Client { get; }

    public async Task<HttpRequestMessage> ContentRequestAsync(bool refresh, CancellationToken cancellation)
    {
        await _gate.WaitAsync(cancellation).ConfigureAwait(false);
        try
        {
            if (refresh || _link is null)
            {
                var url = OneDriveConnector.ItemUrl(_driveId, _itemId);
                using var item = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
                _link = item.RootElement.String("@microsoft.graph.downloadUrl")
                    ?? throw new ConnectorException(ConnectorFailure.AccessDenied, Kind.DisplayName());
            }
            return new HttpRequestMessage(HttpMethod.Get, _link);
        }
        finally
        {
            _gate.Release();
        }
    }
}

public sealed class DropboxConnector : IMediaConnector
{
    public const string ApiBase = "https://api.dropboxapi.com/2/";
    public const string RootFolder = "root";

    private readonly CloudAccount _account;
    private readonly ProviderHttp _http;

    public DropboxConnector(CloudAccount account, CloudTokenProvider tokens, HttpMessageInvoker? client = null)
    {
        _account = account;
        _http = new ProviderHttp(MediaSourceKind.Dropbox, account.Key, tokens, client);
    }

    public MediaSourceKind Kind => MediaSourceKind.Dropbox;
    public string Root => SourceUrl.AccountItem(MediaSourceKind.Dropbox, _account.Key, [RootFolder], "Dropbox");
    public string? AccountLabel => _account.Label;

    public Task ValidateAsync(CancellationToken cancellation) => ListAsync(Root, cancellation);

    public bool CanIndex(string directory) => true;

    public async Task<IReadOnlyList<ConnectorEntry>> ListAsync(string directory, CancellationToken cancellation) =>
        ConnectorWalk.Sorted((await ListFolderAsync(directory, recursive: false, cancellation).ConfigureAwait(false))
            .Where(entry => !entry.IsHidden));

    /// <summary>One recursive listing instead of a walk; anything inside a dot-folder is skipped.</summary>
    public async Task<IReadOnlyList<ConnectorEntry>> EnumerateVideosAsync(string folder, CancellationToken cancellation) =>
        (await ListFolderAsync(folder, recursive: true, cancellation).ConfigureAwait(false))
            .Where(entry => entry.IsVideo && !entry.IsHidden)
            .ToList();

    private async Task<List<ConnectorEntry>> ListFolderAsync(string folder, bool recursive, CancellationToken cancellation)
    {
        if (SourceUrl.ParseAccountItem(folder) is not { Kind: MediaSourceKind.Dropbox } item || item.Ids.Count == 0)
        {
            throw new ConnectorException(ConnectorFailure.InvalidAddress);
        }
        var id = item.Ids[0];
        var body = new Dictionary<string, object>
        {
            ["path"] = id == RootFolder ? "" : id,
            ["recursive"] = recursive,
            ["include_deleted"] = false,
            ["include_non_downloadable_files"] = false,
            ["limit"] = 2000,
        };
        var entries = new List<ConnectorEntry>();
        using (var first = await _http.JsonAsync(() => ProviderHttp.JsonRequest(ApiBase + "files/list_folder", body), cancellation).ConfigureAwait(false))
        {
            var (cursor, hasMore) = Collect(first.RootElement, entries, recursive);
            while (hasMore)
            {
                var next = new Dictionary<string, object> { ["cursor"] = cursor ?? "" };
                using var page = await _http.JsonAsync(() => ProviderHttp.JsonRequest(ApiBase + "files/list_folder/continue", next), cancellation).ConfigureAwait(false);
                (cursor, hasMore) = Collect(page.RootElement, entries, recursive);
            }
        }
        return entries;
    }

    private (string? Cursor, bool HasMore) Collect(JsonElement page, List<ConnectorEntry> entries, bool hiddenAncestors)
    {
        if (page.Property("entries") is { ValueKind: JsonValueKind.Array } items)
        {
            foreach (var metadata in items.EnumerateArray())
            {
                if (Entry(metadata, hiddenAncestors) is { } entry) entries.Add(entry);
            }
        }
        var hasMore = page.Property("has_more") is { ValueKind: JsonValueKind.True };
        return (page.String("cursor"), hasMore);
    }

    /// <summary>
    /// Files and folders become entries. With <paramref name="hiddenAncestors"/>,
    /// an item below a dot-folder is dropped, as the default walk would.
    /// </summary>
    private ConnectorEntry? Entry(JsonElement metadata, bool hiddenAncestors)
    {
        var tag = metadata.String(".tag");
        if (metadata.String("id") is not { } id || metadata.String("name") is not { } name || tag is not ("file" or "folder")) return null;
        if (tag == "file" && metadata.Property("is_downloadable") is { ValueKind: JsonValueKind.False }) return null;
        if (hiddenAncestors && metadata.String("path_lower") is { } path
            && path.Split('/', StringSplitOptions.RemoveEmptyEntries).SkipLast(1).Any(segment => segment.StartsWith('.')))
        {
            return null;
        }
        var isFolder = tag == "folder";
        DateTimeOffset? modified = DateTimeOffset.TryParse(metadata.String("server_modified"), CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal, out var date) ? date : null;
        return new ConnectorEntry(name, SourceUrl.AccountItem(MediaSourceKind.Dropbox, _account.Key, [id], name), isFolder)
        {
            Size = !isFolder && metadata.Property("size") is { ValueKind: JsonValueKind.Number } size ? size.GetInt64() : null,
            Modified = modified,
        };
    }
}

/// <summary>Streams from a temporary link, resolving a new one after it expires.</summary>
public sealed class DropboxContentResolver : IRemoteContentResolver
{
    private readonly string _fileId;
    private readonly ProviderHttp _http;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private string? _link;

    public DropboxContentResolver(string fileId, string accountKey, CloudTokenProvider tokens, HttpMessageInvoker? client = null)
    {
        _fileId = fileId;
        _http = new ProviderHttp(MediaSourceKind.Dropbox, accountKey, tokens, client);
        Client = client ?? RemoteHttp.Shared;
    }

    public MediaSourceKind Kind => MediaSourceKind.Dropbox;
    public bool UsesPreauthorizedLinks => true;
    public HttpMessageInvoker Client { get; }

    public async Task<HttpRequestMessage> ContentRequestAsync(bool refresh, CancellationToken cancellation)
    {
        await _gate.WaitAsync(cancellation).ConfigureAwait(false);
        try
        {
            if (refresh || _link is null)
            {
                var body = new Dictionary<string, object> { ["path"] = _fileId };
                using var result = await _http.JsonAsync(
                    () => ProviderHttp.JsonRequest(DropboxConnector.ApiBase + "files/get_temporary_link", body), cancellation).ConfigureAwait(false);
                _link = result.RootElement.String("link")
                    ?? throw new ConnectorException(ConnectorFailure.AccessDenied, Kind.DisplayName());
            }
            return new HttpRequestMessage(HttpMethod.Get, _link);
        }
        finally
        {
            _gate.Release();
        }
    }
}
