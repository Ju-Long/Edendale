// Google Drive (Drive v3 REST API, drive.readonly scope) connector and its
// streaming resolver (GoogleDriveConnector.swift).
//
// Item URLs are gdrive://<account>/<fileId>/<Name.ext>; folders use the
// folder's id the same way, plus ?drive=<driveId> inside a shared drive,
// whose listings must name it. The picker's root holds My Drive, Shared with
// me, and Shared drives.
//
// Shortcuts are followed to their targets, other Google formats (Docs,
// Sheets) are skipped, and videos are recognized by file extension rather
// than MIME type. Files stream from files/<id>?alt=media with a Bearer token
// and Range. acknowledgeAbuse is never sent: a file Google flags as abusive
// fails with its own message instead.

using System.Globalization;
using System.Net.Http.Headers;
using System.Text.Json;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

public sealed class GoogleDriveConnector : IMediaConnector
{
    public const string ApiBase = "https://www.googleapis.com/drive/v3/";
    public const string FolderMimeType = "application/vnd.google-apps.folder";
    public const string ShortcutMimeType = "application/vnd.google-apps.shortcut";
    private const string GoogleFormatPrefix = "application/vnd.google-apps.";

    private const string FileFields =
        "nextPageToken,files(id,name,mimeType,size,modifiedTime,driveId,videoMediaMetadata(durationMillis),shortcutDetails(targetId,targetMimeType))";

    /// <summary>Ids of the picker's virtual folders; real Drive ids never start with "~".</summary>
    public static class VirtualFolder
    {
        public const string Roots = "~roots";
        public const string SharedWithMe = "~shared";
        public const string SharedDrives = "~drives";
        public const string MyDrive = "root";
    }

    private readonly CloudAccount _account;
    private readonly ProviderHttp _http;

    public GoogleDriveConnector(CloudAccount account, CloudTokenProvider tokens, HttpMessageInvoker? client = null)
    {
        _account = account;
        _http = new ProviderHttp(MediaSourceKind.GoogleDrive, account.Key, tokens, client);
    }

    public MediaSourceKind Kind => MediaSourceKind.GoogleDrive;
    public string Root => FolderUrl(VirtualFolder.Roots, "Google Drive");
    public string? AccountLabel => _account.Label;

    public async Task ValidateAsync(CancellationToken cancellation)
    {
        var url = Url("about", [("fields", "user(emailAddress)")]);
        using var _ = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
    }

    public Task<IReadOnlyList<ConnectorEntry>> EnumerateVideosAsync(string folder, CancellationToken cancellation) =>
        ConnectorWalk.VideosAsync(folder, ListAsync, cancellation);

    /// <summary>
    /// The picker's root and the list of shared drives only gather other
    /// folders; linking either would scan all of Drive.
    /// </summary>
    public bool CanIndex(string directory) =>
        SourceUrl.ParseAccountItem(directory) is { Kind: MediaSourceKind.GoogleDrive } item
        && item.Ids[0] is not (VirtualFolder.Roots or VirtualFolder.SharedDrives);

    public async Task<IReadOnlyList<ConnectorEntry>> ListAsync(string directory, CancellationToken cancellation)
    {
        if (SourceUrl.ParseAccountItem(directory) is not { Kind: MediaSourceKind.GoogleDrive } item)
        {
            throw new ConnectorException(ConnectorFailure.InvalidAddress);
        }
        var id = item.Ids[0];
        return id switch
        {
            VirtualFolder.Roots =>
            [
                new ConnectorEntry(AppText.Get("GoogleDrive_MyDrive"), FolderUrl(VirtualFolder.MyDrive, AppText.Get("GoogleDrive_MyDrive")), true),
                new ConnectorEntry(AppText.Get("GoogleDrive_SharedWithMe"), FolderUrl(VirtualFolder.SharedWithMe, AppText.Get("GoogleDrive_SharedWithMe")), true),
                new ConnectorEntry(AppText.Get("GoogleDrive_SharedDrives"), FolderUrl(VirtualFolder.SharedDrives, AppText.Get("GoogleDrive_SharedDrives")), true),
            ],
            VirtualFolder.SharedDrives => await ListSharedDrivesAsync(cancellation).ConfigureAwait(false),
            VirtualFolder.SharedWithMe => await ListFilesAsync("sharedWithMe = true and trashed = false", null, cancellation).ConfigureAwait(false),
            _ => await ListFilesAsync($"'{QueryLiteral(id)}' in parents and trashed = false", item.QueryValue("drive"), cancellation).ConfigureAwait(false),
        };
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    private async Task<IReadOnlyList<ConnectorEntry>> ListFilesAsync(string query, string? driveId, CancellationToken cancellation)
    {
        var entries = new List<ConnectorEntry>();
        string? pageToken = null;
        do
        {
            var parameters = new List<(string, string)>
            {
                ("q", query),
                ("fields", FileFields),
                ("pageSize", "1000"),
                ("supportsAllDrives", "true"),
                ("includeItemsFromAllDrives", "true"),
            };
            if (driveId is not null)
            {
                parameters.Add(("corpora", "drive"));
                parameters.Add(("driveId", driveId));
            }
            if (pageToken is not null) parameters.Add(("pageToken", pageToken));
            var url = Url("files", parameters);
            using var page = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
            if (page.RootElement.Property("files") is { ValueKind: JsonValueKind.Array } files)
            {
                foreach (var file in files.EnumerateArray())
                {
                    if (Entry(file, driveId) is { } entry) entries.Add(entry);
                }
            }
            pageToken = page.RootElement.String("nextPageToken");
        }
        while (pageToken is not null);
        return ConnectorWalk.Sorted(entries);
    }

    private async Task<IReadOnlyList<ConnectorEntry>> ListSharedDrivesAsync(CancellationToken cancellation)
    {
        var entries = new List<ConnectorEntry>();
        string? pageToken = null;
        do
        {
            var parameters = new List<(string, string)> { ("pageSize", "100"), ("fields", "nextPageToken,drives(id,name)") };
            if (pageToken is not null) parameters.Add(("pageToken", pageToken));
            var url = Url("drives", parameters);
            using var page = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
            if (page.RootElement.Property("drives") is { ValueKind: JsonValueKind.Array } drives)
            {
                foreach (var drive in drives.EnumerateArray())
                {
                    if (drive.String("id") is not { } id || drive.String("name") is not { } name) continue;
                    // A shared drive's root folder has the drive's id.
                    entries.Add(new ConnectorEntry(name, FolderUrl(id, name, driveId: id), true));
                }
            }
            pageToken = page.RootElement.String("nextPageToken");
        }
        while (pageToken is not null);
        return ConnectorWalk.Sorted(entries);
    }

    /// <summary>
    /// Folders and folder shortcuts become directories; other Google formats
    /// are skipped. A folder keeps the shared drive it lives in, which its
    /// listing must name.
    /// </summary>
    private ConnectorEntry? Entry(JsonElement file, string? driveId)
    {
        if (file.String("id") is not { } id || file.String("name") is not { } name) return null;
        var mimeType = file.String("mimeType") ?? "";
        if (mimeType == ShortcutMimeType)
        {
            // The shortcut doesn't say which drive its target is in, so it
            // keeps the listing's.
            if (file.Property("shortcutDetails") is not { } shortcut || shortcut.String("targetId") is not { } target) return null;
            id = target;
            mimeType = shortcut.String("targetMimeType") ?? "";
        }
        else
        {
            driveId = file.String("driveId") ?? driveId;
        }

        DateTimeOffset? modified = DateTimeOffset.TryParse(file.String("modifiedTime"), CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal, out var date) ? date : null;
        if (mimeType == FolderMimeType)
        {
            return new ConnectorEntry(name, FolderUrl(id, name, driveId), true) { Modified = modified };
        }
        if (mimeType.StartsWith(GoogleFormatPrefix, StringComparison.Ordinal)) return null;

        // Drive encodes 64-bit numbers as strings; the duration is missing
        // until Drive finishes processing a video.
        var size = long.TryParse(file.String("size"), NumberStyles.None, CultureInfo.InvariantCulture, out var bytes) ? bytes : (long?)null;
        var millis = file.Property("videoMediaMetadata")?.String("durationMillis");
        var duration = double.TryParse(millis, NumberStyles.Float, CultureInfo.InvariantCulture, out var ms) ? ms / 1000 : (double?)null;
        return new ConnectorEntry(name, SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, _account.Key, [id], name), false)
        {
            Size = size,
            Duration = duration,
            Modified = modified,
        };
    }

    // ------------------------------------------------------------------
    // URLs
    // ------------------------------------------------------------------

    public string FolderUrl(string id, string name, string? driveId = null) =>
        SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, _account.Key, [id], name,
            driveId is null ? null : [new KeyValuePair<string, string>("drive", driveId)]);

    public static string Url(string path, IEnumerable<(string Name, string Value)> query) =>
        $"{ApiBase}{path}?{OAuthClient.FormEncode(query)}";

    /// <summary>The file's bytes; supportsAllDrives reaches files in shared drives.</summary>
    public static string ContentUrl(string fileId) =>
        Url($"files/{Uri.EscapeDataString(fileId)}", [("alt", "media"), ("supportsAllDrives", "true")]);

    /// <summary>A string literal for a Drive query: backslashes and quotes escaped.</summary>
    public static string QueryLiteral(string value) => value.Replace(@"\", @"\\").Replace("'", @"\'");
}

/// <summary>Streams a Drive file with a Bearer token; a refresh gets a new token.</summary>
public sealed class GoogleDriveContentResolver : IRemoteContentResolver
{
    private readonly string _fileId;
    private readonly string _accountKey;
    private readonly CloudTokenProvider _tokens;
    private string? _lastToken;

    public GoogleDriveContentResolver(string fileId, string accountKey, CloudTokenProvider tokens, HttpMessageInvoker? client = null)
    {
        _fileId = fileId;
        _accountKey = accountKey;
        _tokens = tokens;
        Client = client ?? RemoteHttp.Shared;
    }

    public MediaSourceKind Kind => MediaSourceKind.GoogleDrive;
    public bool UsesPreauthorizedLinks => false;
    public HttpMessageInvoker Client { get; }

    public async Task<HttpRequestMessage> ContentRequestAsync(bool refresh, CancellationToken cancellation)
    {
        var token = await _tokens.AccessTokenAsync(Kind, _accountKey, refresh ? _lastToken : null).ConfigureAwait(false);
        _lastToken = token;
        var request = new HttpRequestMessage(HttpMethod.Get, GoogleDriveConnector.ContentUrl(_fileId));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return request;
    }
}
