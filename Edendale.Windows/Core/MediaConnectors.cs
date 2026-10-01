namespace Edendale.Windows.Core;

/// <summary>
/// One item in a listed directory: a subfolder to open or a file.
/// <see cref="Url"/> is canonical and credential-free (see <see cref="SourceUrl"/>).
/// </summary>
public sealed record ConnectorEntry(string Name, string Url, bool IsDirectory)
{
    /// <summary>Bytes, when the listing reports it.</summary>
    public long? Size { get; init; }

    public DateTimeOffset? Modified { get; init; }

    /// <summary>Seconds, when the provider reports it (the Graph video facet).</summary>
    public double? Duration { get; init; }

    /// <summary>A file with one of the video extensions the library imports.</summary>
    public bool IsVideo => !IsDirectory && VideoFiles.IsVideoName(Name);

    /// <summary>Dot-files and dot-folders (.DS_Store, .Trash) are skipped by enumeration.</summary>
    public bool IsHidden => Name.StartsWith('.');
}

/// <summary>
/// A connection to a remote file tree that can verify itself and list
/// directories (MediaConnector.swift). Connectors capture the address and how
/// to authenticate; nothing they return carries a password or token.
/// </summary>
public interface IMediaConnector
{
    MediaSourceKind Kind { get; }

    /// <summary>Top of the browsable tree (for example a server's root or an account's drives).</summary>
    string Root { get; }

    /// <summary>Username or account email shown with the source; never a secret.</summary>
    string? AccountLabel { get; }

    /// <summary>Confirms the source is reachable and the login or account works.</summary>
    Task ValidateAsync(CancellationToken cancellation);

    /// <summary>Lists one directory (non-recursive), folders first.</summary>
    Task<IReadOnlyList<ConnectorEntry>> ListAsync(string directory, CancellationToken cancellation);

    /// <summary>Every video under <paramref name="folder"/>.</summary>
    Task<IReadOnlyList<ConnectorEntry>> EnumerateVideosAsync(string folder, CancellationToken cancellation);

    /// <summary>Whether a directory can become a library source (virtual roots can't).</summary>
    bool CanIndex(string directory);
}

/// <summary>The breadth-first walk behind every connector's <c>EnumerateVideosAsync</c>.</summary>
public static class ConnectorWalk
{
    /// <summary>Caps runaway trees: symlink cycles and shortcut loops have no other guard.</summary>
    public const int MaxDirectories = 2000;

    /// <summary>
    /// Walks <paramref name="folder"/> breadth-first, skipping dot-files and
    /// already-visited folders, and stops after <paramref name="maxDirectories"/>
    /// listings. A failure listing <paramref name="folder"/> itself throws (the
    /// source is unreachable); a failure below it skips only that branch.
    /// </summary>
    public static async Task<IReadOnlyList<ConnectorEntry>> VideosAsync(
        string folder,
        Func<string, CancellationToken, Task<IReadOnlyList<ConnectorEntry>>> list,
        CancellationToken cancellation,
        int maxDirectories = MaxDirectories)
    {
        var videos = new List<ConnectorEntry>();
        var queue = new Queue<string>();
        queue.Enqueue(folder);
        var visited = new HashSet<string>(StringComparer.Ordinal) { folder };
        var listed = 0;

        while (queue.Count > 0 && listed < maxDirectories)
        {
            cancellation.ThrowIfCancellationRequested();
            var directory = queue.Dequeue();
            IReadOnlyList<ConnectorEntry> entries;
            if (listed == 0)
            {
                entries = await list(directory, cancellation).ConfigureAwait(false);
            }
            else
            {
                try
                {
                    entries = await list(directory, cancellation).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception)
                {
                    entries = [];
                }
            }
            listed++;

            foreach (var entry in entries)
            {
                if (entry.IsHidden) continue;
                if (entry.IsDirectory)
                {
                    if (visited.Add(entry.Url)) queue.Enqueue(entry.Url);
                }
                else if (entry.IsVideo)
                {
                    videos.Add(entry);
                }
            }
        }
        return videos;
    }

    /// <summary>Folders first, then names in Explorer order (numbers compare by value).</summary>
    public static IReadOnlyList<ConnectorEntry> Sorted(IEnumerable<ConnectorEntry> entries) =>
        entries
            .OrderByDescending(entry => entry.IsDirectory)
            .ThenBy(entry => entry.Name, NaturalStringComparer.Instance)
            .ToList();
}

/// <summary>
/// Explorer-style ordering: case-insensitive and culture-aware, with runs of
/// digits compared by value ("Episode 2" before "Episode 10").
/// </summary>
public sealed class NaturalStringComparer : IComparer<string>
{
    public static readonly NaturalStringComparer Instance = new();

    public int Compare(string? x, string? y)
    {
        if (ReferenceEquals(x, y)) return 0;
        if (x is null) return -1;
        if (y is null) return 1;

        var culture = System.Globalization.CultureInfo.CurrentCulture.CompareInfo;
        int i = 0, j = 0;
        while (i < x.Length && j < y.Length)
        {
            if (char.IsAsciiDigit(x[i]) && char.IsAsciiDigit(y[j]))
            {
                var startX = i;
                var startY = j;
                while (i < x.Length && char.IsAsciiDigit(x[i])) i++;
                while (j < y.Length && char.IsAsciiDigit(y[j])) j++;
                var digitsX = x[startX..i].TrimStart('0');
                var digitsY = y[startY..j].TrimStart('0');
                if (digitsX.Length != digitsY.Length) return digitsX.Length.CompareTo(digitsY.Length);
                var byValue = string.CompareOrdinal(digitsX, digitsY);
                if (byValue != 0) return byValue;
            }
            else
            {
                var startX = i;
                var startY = j;
                while (i < x.Length && !char.IsAsciiDigit(x[i])) i++;
                while (j < y.Length && !char.IsAsciiDigit(y[j])) j++;
                var byText = culture.Compare(x[startX..i], y[startY..j], System.Globalization.CompareOptions.IgnoreCase);
                if (byText != 0) return byText;
            }
        }
        return (x.Length - i).CompareTo(y.Length - j);
    }
}

/// <summary>Why a connector failed, in the categories the library and UI act on.</summary>
public enum ConnectorFailure
{
    InvalidAddress,
    Unreachable,
    ListingFailed,
    /// <summary>The account or saved login this source uses is gone or was revoked.</summary>
    SignInRequired,
    /// <summary>This build has no client ID for the provider (see tools/Edendale.Secrets).</summary>
    NotConfigured,
    AccessDenied,
    NotFound,
    RateLimited,
    ServerError,
    /// <summary>Plain HTTP is only allowed to servers on the local network.</summary>
    InsecureConnection,
    /// <summary>The server's TLS certificate isn't valid (D10: no self-signed exceptions).</summary>
    CertificateInvalid,
    RangeRequestsUnsupported,
    HostKeyMismatch,
    HostKeyUnverified,
    AuthenticationFailed,
    SecureConnectionFailed,
    PasswordLoginUnavailable,
    SftpUnavailable,
    BucketInAnotherRegion,
    /// <summary>LibVLC couldn't mount or read the NFS export.</summary>
    NfsMountFailed,
}

/// <summary>
/// A connector error with a readable message. Messages never include a URL
/// with credentials, a token, or a header.
/// </summary>
public sealed class ConnectorException : Exception
{
    public ConnectorException(ConnectorFailure failure, string? subject = null, int? status = null, Exception? inner = null)
        : base(Describe(failure, subject, status), inner)
    {
        Failure = failure;
        Subject = subject;
        Status = status;
    }

    public ConnectorFailure Failure { get; }

    /// <summary>The host, provider, path, or region the message names.</summary>
    public string? Subject { get; }

    /// <summary>The HTTP status, for <see cref="ConnectorFailure.ServerError"/>.</summary>
    public int? Status { get; }

    /// <summary>
    /// The source needs the user to sign in or approve something again,
    /// rather than being temporarily unreachable.
    /// </summary>
    public bool NeedsUserAction => Failure is ConnectorFailure.SignInRequired
        or ConnectorFailure.HostKeyMismatch
        or ConnectorFailure.HostKeyUnverified
        or ConnectorFailure.AuthenticationFailed
        or ConnectorFailure.NotConfigured
        or ConnectorFailure.PasswordLoginUnavailable;

    private static string Describe(ConnectorFailure failure, string? subject, int? status) => failure switch
    {
        ConnectorFailure.InvalidAddress => AppText.Get("Connector_InvalidAddress"),
        ConnectorFailure.Unreachable => AppText.Format("Connector_Unreachable", subject),
        ConnectorFailure.ListingFailed => AppText.Format("Connector_ListingFailed", subject),
        ConnectorFailure.SignInRequired => AppText.Format("Connector_SignInRequired", subject),
        ConnectorFailure.NotConfigured => AppText.Format("Connector_NotConfigured", subject),
        ConnectorFailure.AccessDenied => AppText.Format("Connector_AccessDenied", subject),
        ConnectorFailure.NotFound => AppText.Format("Connector_NotFound", subject),
        ConnectorFailure.RateLimited => AppText.Format("Connector_RateLimited", subject),
        ConnectorFailure.ServerError => AppText.Format("Connector_ServerError", subject, status),
        ConnectorFailure.InsecureConnection => AppText.Get("Connector_InsecureConnection"),
        ConnectorFailure.CertificateInvalid => AppText.Format("Connector_CertificateInvalid", subject),
        ConnectorFailure.RangeRequestsUnsupported => AppText.Format("Connector_RangeUnsupported", subject),
        ConnectorFailure.HostKeyMismatch => AppText.Format("Connector_HostKeyMismatch", subject),
        ConnectorFailure.HostKeyUnverified => AppText.Format("Connector_HostKeyUnverified", subject),
        ConnectorFailure.AuthenticationFailed => AppText.Format("Connector_AuthenticationFailed", subject),
        ConnectorFailure.SecureConnectionFailed => AppText.Format("Connector_SecureConnectionFailed", subject),
        ConnectorFailure.PasswordLoginUnavailable => AppText.Format("Connector_PasswordLoginUnavailable", subject),
        ConnectorFailure.SftpUnavailable => AppText.Format("Connector_SftpUnavailable", subject),
        ConnectorFailure.NfsMountFailed => AppText.Format("Connector_NfsMountFailed", subject),
        ConnectorFailure.BucketInAnotherRegion => subject is null
            ? AppText.Get("Connector_BucketInAnotherRegionUnknown")
            : AppText.Format("Connector_BucketInAnotherRegion", subject),
        _ => failure.ToString(),
    };
}
