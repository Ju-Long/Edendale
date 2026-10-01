using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

/// <summary>The stores connectors rebuild themselves from.</summary>
public sealed record ConnectorEnvironment(
    ServerLoginStore Logins,
    CloudAccountVault Accounts,
    CloudTokenProvider Tokens,
    HostKeyStore HostKeys);

/// <summary>
/// Rebuilds connectors and playback sources from what the library stores
/// (ConnectorFactory.swift): the kind and the credential-free URL, plus the
/// login or account saved for its host or account key.
/// </summary>
public static class ConnectorFactory
{
    /// <summary>
    /// Builds NFS connectors, which list through LibVLC; set by the app so
    /// this factory stays free of LibVLC for the unit tests.
    /// </summary>
    public static Func<string, IMediaConnector?>? NfsConnectors { get; set; }

    /// <summary>
    /// The connector for a stored remote source, or null for local folders,
    /// SMB shares (read through UNC), and sources whose login is gone.
    /// </summary>
    public static IMediaConnector? ForSource(string sourceUrl, MediaSourceKind kind, ConnectorEnvironment environment)
    {
        switch (kind)
        {
            case MediaSourceKind.WebDav:
                return WebDavConnector.FromSource(sourceUrl, environment.Logins);
            case MediaSourceKind.S3:
                return S3Connector.FromSource(sourceUrl, environment.Logins);
            case MediaSourceKind.Sftp:
                return SftpConnector.FromSource(sourceUrl, environment.Logins, environment.HostKeys);
            case MediaSourceKind.Nfs:
                return NfsConnectors?.Invoke(sourceUrl);
            case MediaSourceKind.OneDrive:
                return SourceUrl.CredentialHost(sourceUrl) is { } oneDriveKey
                    && environment.Accounts.Account(MediaSourceKind.OneDrive, oneDriveKey) is { } oneDrive
                    ? OneDriveConnector.Create(oneDrive, environment.Tokens)
                    : null;
            case MediaSourceKind.Dropbox:
                return SourceUrl.CredentialHost(sourceUrl) is { } dropboxKey
                    && environment.Accounts.Account(MediaSourceKind.Dropbox, dropboxKey) is { } dropbox
                    ? new DropboxConnector(dropbox, environment.Tokens)
                    : null;
            default:
                return null;
        }
    }

    /// <summary>
    /// Whether an item plays through a custom input (HTTP and SFTP sources)
    /// rather than a path or URL LibVLC opens itself (local files, UNC, NFS).
    /// </summary>
    public static bool NeedsCustomInput(string path) =>
        SourceUrl.IsUrl(path) && MediaSourceKinds.FromPath(path) is MediaSourceKind.WebDav or MediaSourceKind.S3
            or MediaSourceKind.Sftp or MediaSourceKind.OneDrive or MediaSourceKind.Dropbox or MediaSourceKind.GoogleDrive;

    /// <summary>
    /// The byte source for a remote item. Throws a ConnectorException when
    /// its login or account is gone or the URL isn't a playable item.
    /// </summary>
    public static IByteSource ByteSourceFor(string itemUrl, ConnectorEnvironment environment, long? length = null)
    {
        var kind = MediaSourceKinds.FromPath(itemUrl);
        var provider = kind.DisplayName();
        switch (kind)
        {
            case MediaSourceKind.WebDav:
            {
                var host = SourceUrl.CredentialHost(itemUrl) ?? throw new ConnectorException(ConnectorFailure.InvalidAddress);
                var login = environment.Logins.Get(MediaSourceKind.WebDav, host);
                var client = WebDavConnector.LoginClients.For(itemUrl, login);
                return new RemoteByteSource(new WebDavContentResolver(itemUrl, client), length);
            }
            case MediaSourceKind.S3:
            {
                var item = SourceUrl.ParseS3(itemUrl);
                if (item is null || item.IsPrefix) throw new ConnectorException(ConnectorFailure.InvalidAddress);
                if (environment.Logins.Get(MediaSourceKind.S3, item.Account) is not { S3: { } configuration } login)
                {
                    throw new ConnectorException(ConnectorFailure.SignInRequired, provider);
                }
                return new RemoteByteSource(new S3ContentResolver(configuration, login.Username, login.Password, item.Key), length);
            }
            case MediaSourceKind.OneDrive:
            {
                var item = SourceUrl.ParseAccountItem(itemUrl) ?? throw new ConnectorException(ConnectorFailure.InvalidAddress);
                if (environment.Accounts.Account(MediaSourceKind.OneDrive, item.Account) is null)
                {
                    throw new ConnectorException(ConnectorFailure.SignInRequired, provider);
                }
                return new RemoteByteSource(new OneDriveContentResolver(item.Ids[0], item.Ids[1], item.Account, environment.Tokens), length);
            }
            case MediaSourceKind.Dropbox:
            {
                var item = SourceUrl.ParseAccountItem(itemUrl) ?? throw new ConnectorException(ConnectorFailure.InvalidAddress);
                if (environment.Accounts.Account(MediaSourceKind.Dropbox, item.Account) is null)
                {
                    throw new ConnectorException(ConnectorFailure.SignInRequired, provider);
                }
                return new RemoteByteSource(new DropboxContentResolver(item.Ids[0], item.Account, environment.Tokens), length);
            }
            case MediaSourceKind.Sftp:
            {
                var connector = SftpConnector.FromSource(itemUrl, environment.Logins, environment.HostKeys)
                    ?? throw new ConnectorException(ConnectorFailure.SignInRequired, provider);
                return connector.ByteSource(itemUrl);
            }
            default:
                throw new ConnectorException(ConnectorFailure.InvalidAddress);
        }
    }
}
