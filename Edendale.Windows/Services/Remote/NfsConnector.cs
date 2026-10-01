// NFS exports through LibVLC's NFS module (X.5). Windows has no default NFS
// client (it is an optional feature), but the bundled LibVLC ships libnfs, so
// listing parses an nfs:// folder as a directory and playback hands LibVLC
// the nfs:// URL itself. NFS uses AUTH_SYS, so there is no login to store.
// LibVLC connects from an unprivileged port, so the export needs the
// "insecure" option; the connection error says so.

using Edendale.Windows.Core;
using LibVLCSharp.Shared;

namespace Edendale.Windows.Services.Remote;

public sealed class NfsConnector : IMediaConnector
{
    private static readonly Lazy<LibVLC> Browser = new(() => new LibVLC("--quiet", "--no-lua"));

    private NfsConnector(string root, string host)
    {
        Root = root;
        Host = host;
    }

    /// <summary>A connector for nfs://host/export/path, from what the user typed or a stored source.</summary>
    public static NfsConnector? FromSource(string address)
    {
        var text = address.Trim();
        if (!text.Contains("://", StringComparison.Ordinal)) text = "nfs://" + text.TrimStart('/');
        if (!SourceUrl.TrySplit(text, out var parts) || !parts.Scheme.Equals("nfs", StringComparison.OrdinalIgnoreCase)
            || parts.Host.Length == 0)
        {
            return null;
        }
        var segments = parts.EncodedPath.Split('/', StringSplitOptions.RemoveEmptyEntries).Select(SourceUrl.Decode);
        var root = SourceUrl.Server("nfs", parts.Host, parts.Port, segments, isDirectory: true);
        return root is null ? null : new NfsConnector(root, parts.Host);
    }

    public MediaSourceKind Kind => MediaSourceKind.Nfs;
    public string Root { get; }
    public string Host { get; }
    public string? AccountLabel => null;

    public Task ValidateAsync(CancellationToken cancellation) => ListAsync(Root, cancellation);

    public bool CanIndex(string directory) => true;

    public Task<IReadOnlyList<ConnectorEntry>> EnumerateVideosAsync(string folder, CancellationToken cancellation) =>
        ConnectorWalk.VideosAsync(folder, ListAsync, cancellation);

    public async Task<IReadOnlyList<ConnectorEntry>> ListAsync(string directory, CancellationToken cancellation)
    {
        var url = directory.EndsWith('/') ? directory : directory + "/";
        using var media = new Media(Browser.Value, new Uri(url));
        var status = await media.Parse(MediaParseOptions.ParseNetwork, timeout: 15_000, cancellation).ConfigureAwait(false);
        if (status != MediaParsedStatus.Done)
        {
            throw new ConnectorException(ConnectorFailure.NfsMountFailed, Host);
        }

        var entries = new List<ConnectorEntry>();
        foreach (var item in media.SubItems)
        {
            using (item)
            {
                if (item.Mrl is not { Length: > 0 } mrl) continue;
                var isDirectory = item.Type == MediaType.Directory;
                var name = SourceUrl.FileName(mrl);
                // Rebuild the URL in the canonical encoding, so it matches what the library stores.
                var canonical = SourceUrl.Server("nfs", Host, SourceUrl.TrySplit(mrl, out var parts) ? parts.Port : null,
                    SourceUrl.PathSegments(mrl), isDirectory);
                if (canonical is null || name.Length == 0) continue;
                entries.Add(new ConnectorEntry(name, canonical, isDirectory));
            }
        }
        return ConnectorWalk.Sorted(entries.Where(entry => !entry.IsHidden));
    }
}
