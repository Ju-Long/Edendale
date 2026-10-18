// Google Drive (Drive v3 REST API, drive.file scope) connector and its
// streaming resolver (GoogleDriveConnector.swift).
//
// Under drive.file Edendale sees only the files the user picked in Google's
// picker, which opens during sign-in and from Choose More Videos
// (ENHANCEMENT.md 3.20), so there is nothing to browse. Each account has one
// virtual folder, "Videos you chose", listing every file picked so far on
// any device or platform, because Google grants picks per account, not per
// token. Item URLs are gdrive://<account>/<fileId>/<Name.ext>.
//
// Folders are skipped (a picked folder never grants what's inside it),
// shortcuts are followed to their targets, other Google formats (Docs,
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
        "nextPageToken,files(id,name,mimeType,size,modifiedTime,videoMediaMetadata(durationMillis),shortcutDetails(targetId,targetMimeType))";

    /// <summary>The id of the "Videos you chose" folder; real Drive ids never start with "~".</summary>
    public const string ChosenFolderId = "~chosen";

    /// <summary>The name in its URL, fixed so a source's stored URL is the same in every language.</summary>
    public const string ChosenFolderUrlName = "Google Drive";

    private readonly CloudAccount _account;
    private readonly ProviderHttp _http;

    public GoogleDriveConnector(CloudAccount account, CloudTokenProvider tokens, HttpMessageInvoker? client = null)
    {
        _account = account;
        _http = new ProviderHttp(MediaSourceKind.GoogleDrive, account.Key, tokens, client);
    }

    public MediaSourceKind Kind => MediaSourceKind.GoogleDrive;
    public string Root => ChosenFolderUrl(_account.Key);
    public string? AccountLabel => _account.Label;

    /// <summary>The account's one Google Drive source.</summary>
    public static string ChosenFolderUrl(string accountKey) =>
        SourceUrl.AccountItem(MediaSourceKind.GoogleDrive, accountKey, [ChosenFolderId], ChosenFolderUrlName);

    /// <summary>
    /// Whether a stored source is this build's "Videos you chose". Builds
    /// before drive.file linked Drive folders, which can't be listed now.
    /// </summary>
    public static bool IsChosenFolder(string url) =>
        SourceUrl.ParseAccountItem(url) is { Kind: MediaSourceKind.GoogleDrive, Ids: [ChosenFolderId] };

    /// <summary>What the source is called in the library.</summary>
    public static string ChosenFolderName => AppText.Get("GoogleDrive_ChosenFolder");

    /// <summary>The source row's location: "Google Drive › Videos you chose".</summary>
    public static string ChosenFolderDisplayPath => $"{MediaSourceKind.GoogleDrive.DisplayName()} › {ChosenFolderName}";

    /// <summary>The Drive file id in a stored item path; null for anything else.</summary>
    public static string? FileId(string itemPath) =>
        SourceUrl.ParseAccountItem(itemPath) is { Kind: MediaSourceKind.GoogleDrive, Ids: [var id] } && id != ChosenFolderId ? id : null;

    public async Task ValidateAsync(CancellationToken cancellation)
    {
        var url = Url("about", [("fields", "user(emailAddress)")]);
        using var _ = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
    }

    public Task<IReadOnlyList<ConnectorEntry>> EnumerateVideosAsync(string folder, CancellationToken cancellation) =>
        ConnectorWalk.VideosAsync(folder, ListAsync, cancellation);

    /// <summary>Only "Videos you chose" lists, so only it can be linked.</summary>
    public bool CanIndex(string directory) => IsChosenFolder(directory);

    /// <summary>
    /// Only the "Videos you chose" folder lists: it holds every file the
    /// user picked, and no folder's contents are ever granted.
    /// </summary>
    public Task<IReadOnlyList<ConnectorEntry>> ListAsync(string directory, CancellationToken cancellation)
    {
        if (!IsChosenFolder(directory)) throw new ConnectorException(ConnectorFailure.InvalidAddress);
        return ListChosenFilesAsync(cancellation);
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    /// <summary>
    /// Every file the user picked, across pages. drive.file limits files.list
    /// to those, whether in My Drive, shared with them, or in shared drives.
    /// </summary>
    private async Task<IReadOnlyList<ConnectorEntry>> ListChosenFilesAsync(CancellationToken cancellation)
    {
        var entries = new List<ConnectorEntry>();
        string? pageToken = null;
        do
        {
            var parameters = new List<(string, string)>
            {
                ("q", "trashed = false"),
                ("fields", FileFields),
                ("pageSize", "1000"),
                ("supportsAllDrives", "true"),
                ("includeItemsFromAllDrives", "true"),
            };
            if (pageToken is not null) parameters.Add(("pageToken", pageToken));
            var url = Url("files", parameters);
            using var page = await _http.JsonAsync(() => new HttpRequestMessage(HttpMethod.Get, url), cancellation).ConfigureAwait(false);
            if (page.RootElement.Property("files") is { ValueKind: JsonValueKind.Array } files)
            {
                foreach (var file in files.EnumerateArray())
                {
                    if (Entry(file) is { } entry) entries.Add(entry);
                }
            }
            pageToken = page.RootElement.String("nextPageToken");
        }
        while (pageToken is not null);
        return ConnectorWalk.Sorted(entries);
    }

    /// <summary>A picked file as an entry. Folders, folder shortcuts, and other Google formats are skipped.</summary>
    private ConnectorEntry? Entry(JsonElement file)
    {
        if (file.String("id") is not { } id || file.String("name") is not { } name) return null;
        var mimeType = file.String("mimeType") ?? "";
        if (mimeType == ShortcutMimeType)
        {
            if (file.Property("shortcutDetails") is not { } shortcut || shortcut.String("targetId") is not { } target) return null;
            id = target;
            mimeType = shortcut.String("targetMimeType") ?? "";
        }
        // Folders go with the other Google formats: a picked folder grants
        // only itself, never the videos inside it.
        if (mimeType.StartsWith(GoogleFormatPrefix, StringComparison.Ordinal)) return null;

        DateTimeOffset? modified = DateTimeOffset.TryParse(file.String("modifiedTime"), CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal, out var date) ? date : null;

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

    public static string Url(string path, IEnumerable<(string Name, string Value)> query) =>
        $"{ApiBase}{path}?{OAuthClient.FormEncode(query)}";

    /// <summary>The file's bytes; supportsAllDrives reaches files in shared drives.</summary>
    public static string ContentUrl(string fileId) =>
        Url($"files/{Uri.EscapeDataString(fileId)}", [("alt", "media"), ("supportsAllDrives", "true")]);

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
