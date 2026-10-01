using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Remote;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using VirtualKey = Windows.System.VirtualKey;

namespace Edendale.Windows.Controls;

/// <summary>
/// The Link Source flow shared by the Downloaded page and Settings
/// (DIFF.md §3.12 and §3.15): pick a kind, fill in the form, approve an SSH
/// host key on first use, then pick the folder to import. The address is
/// focused on open and Tab moves between fields. Enter connects when every
/// field is filled and otherwise moves to the first empty one; a guest
/// connection (no username or password) is one click. Failures stay in the
/// form, so nothing typed is lost.
/// </summary>
public static class LinkSourceDialog
{
    /// <summary>Links a new source. Returns its stored path, or null when cancelled.</summary>
    public static Task<string?> ShowAsync(XamlRoot root, MediaSourceKind? kind = null, string? address = null) =>
        RunAsync(root, new LinkSourceForm(root, kind ?? MediaSourceKind.Smb, address, signInAgain: null));

    /// <summary>
    /// Signs in to an existing source again (a refused or forgotten login, a
    /// changed host key, a revoked account), then rescans it.
    /// </summary>
    public static async Task SignInAgainAsync(XamlRoot root, LibraryFolder folder)
    {
        var address = folder.SourceKind switch
        {
            MediaSourceKind.WebDav => WebDavConnector.HttpUrl(folder.Path),
            MediaSourceKind.S3 => null,
            _ => folder.Path,
        };
        await RunAsync(root, new LinkSourceForm(root, folder.SourceKind, address, signInAgain: folder));
    }

    private static async Task<string?> RunAsync(XamlRoot root, LinkSourceForm form)
    {
        // One ContentDialog may be open at a time, so the steps run in turn.
        while (true)
        {
            var result = await form.ShowAsync();
            if (result is null) return null;

            if (result.HostKey is { } key)
            {
                if (!await ApproveHostKeyAsync(root, result.Host!, key, result.HostKeyChanged)) return null;
                AppServices.HostKeys.Pin(key.Fingerprint, result.Host!, result.Port);
                form.ConnectOnOpen = true;
                continue;
            }

            if (result.SmbPath is { } path)
            {
                await AppServices.Library.ImportFolderAsync(path);
                return path;
            }

            var connector = result.Connector!;
            if (form.SignInAgain is { } existing)
            {
                (connector as IDisposable)?.Dispose();
                await AppServices.Library.RescanFolderAsync(existing);
                return existing.Path;
            }

            var choice = await RemoteFolderPicker.ShowAsync(root, connector);
            if (choice is null)
            {
                (connector as IDisposable)?.Dispose();
                return null;
            }
            await AppServices.Library.ImportRemoteFolderAsync(connector, choice.Url, choice.Name, choice.DisplayPath);
            (connector as IDisposable)?.Dispose();
            return choice.Url;
        }
    }

    /// <summary>Trust on first use: the fingerprint and key type, pinned only on approval.</summary>
    private static async Task<bool> ApproveHostKeyAsync(XamlRoot root, string host, SshHostKey key, bool changed)
    {
        var body = new StackPanel { Spacing = 12, MaxWidth = 460 };
        body.Children.Add(new TextBlock
        {
            Text = changed
                ? Loc.Format("HostKey_ChangedMessage", host, key.TypeName)
                : Loc.Format("HostKey_FirstUseMessage", host, key.TypeName),
            TextWrapping = TextWrapping.Wrap,
        });
        body.Children.Add(new TextBlock
        {
            Text = key.Fingerprint,
            FontFamily = new Microsoft.UI.Xaml.Media.FontFamily("Consolas"),
            IsTextSelectionEnabled = true,
            TextWrapping = TextWrapping.Wrap,
        });
        body.Children.Add(new TextBlock
        {
            Text = Loc.Get("HostKey_CompareHint"),
            Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            TextWrapping = TextWrapping.Wrap,
        });
        var dialog = new ContentDialog
        {
            Title = Loc.Get(changed ? "HostKey_ChangedTitle" : "HostKey_FirstUseTitle"),
            Content = body,
            PrimaryButtonText = Loc.Get(changed ? "HostKey_TrustNew" : "HostKey_Trust"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            // A changed key may mean an attack: the safe button is the default.
            DefaultButton = changed ? ContentDialogButton.Close : ContentDialogButton.Primary,
            XamlRoot = root,
        };
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }
}

/// <summary>What the form connected to.</summary>
internal sealed record LinkResult
{
    public IMediaConnector? Connector { get; init; }
    public string? SmbPath { get; init; }
    public SshHostKey? HostKey { get; init; }
    public bool HostKeyChanged { get; init; }
    public string? Host { get; init; }
    public int Port { get; init; }
}

/// <summary>The form step: one per flow, so what was typed survives a host-key prompt.</summary>
internal sealed class LinkSourceForm
{
    private readonly XamlRoot _root;
    private readonly List<MediaSourceKind> _kinds;
    private readonly ComboBox _kindPicker;
    private readonly StackPanel _fields = new() { Spacing = 12 };
    private readonly TextBlock _error;
    private readonly ProgressRing _progress = new() { IsActive = false, Width = 18, Height = 18, HorizontalAlignment = HorizontalAlignment.Left };

    // Every kind's fields exist once, so switching kinds keeps what was typed.
    private readonly TextBox _address = Text("LinkSource_Address");
    private readonly TextBox _username = Text("LinkSource_Username");
    private readonly PasswordBox _password = Secret("LinkSource_Password");
    private readonly TextBox _endpoint = Text("LinkSource_Endpoint", "https://s3.us-east-1.amazonaws.com");
    private readonly TextBox _region = Text("LinkSource_Region", "us-east-1");
    private readonly TextBox _bucket = Text("LinkSource_Bucket");
    private readonly TextBox _accessKey = Text("LinkSource_AccessKey");
    private readonly PasswordBox _secretKey = Secret("LinkSource_SecretKey");
    private readonly ComboBox _accountPicker = new() { HorizontalAlignment = HorizontalAlignment.Stretch };

    private ContentDialog? _dialog;
    private CancellationTokenSource? _work;
    private LinkResult? _result;
    private bool _busy;

    public LinkSourceForm(XamlRoot root, MediaSourceKind kind, string? address, LibraryFolder? signInAgain)
    {
        _root = root;
        SignInAgain = signInAgain;
        _kinds = [MediaSourceKind.Smb, MediaSourceKind.Sftp, MediaSourceKind.WebDav, MediaSourceKind.S3, MediaSourceKind.Nfs];
        // A provider without a client ID in this build is hidden.
        if (CloudProviders.IsConfigured(MediaSourceKind.OneDrive)) _kinds.Add(MediaSourceKind.OneDrive);
        if (CloudProviders.IsConfigured(MediaSourceKind.Dropbox)) _kinds.Add(MediaSourceKind.Dropbox);
        if (!_kinds.Contains(kind)) _kinds.Add(kind);

        _kindPicker = new ComboBox
        {
            Header = Loc.Get("LinkSource_Kind"),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            ItemsSource = _kinds.Select(KindName).ToList(),
            SelectedIndex = _kinds.IndexOf(kind),
            // Signing in again keeps the source's own kind.
            IsEnabled = signInAgain is null,
        };
        _kindPicker.SelectionChanged += (_, _) => BuildFields();
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(_accountPicker, Loc.Get("LinkSource_Account"));
        _accountPicker.Header = Loc.Get("LinkSource_Account");

        _address.Text = address ?? "";
        if (signInAgain is not null) PrefillLogin(signInAgain);

        _error = new TextBlock
        {
            Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EdendaleGoldBrush"],
            TextWrapping = TextWrapping.Wrap,
            Visibility = Visibility.Collapsed,
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetLiveSetting(_error, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);

        foreach (var field in new Control[] { _address, _username, _password, _endpoint, _region, _bucket, _accessKey, _secretKey })
        {
            field.KeyDown += Field_KeyDown;
        }
    }

    public LibraryFolder? SignInAgain { get; }

    /// <summary>Set after a host key was approved: the form connects as soon as it reopens.</summary>
    public bool ConnectOnOpen { get; set; }

    private MediaSourceKind Kind => _kinds[Math.Max(_kindPicker.SelectedIndex, 0)];

    private static string KindName(MediaSourceKind kind) => kind switch
    {
        MediaSourceKind.Smb => Loc.Get("LinkSource_KindSmb"),
        _ => kind.DisplayName(),
    };

    private static TextBox Text(string headerKey, string? placeholder = null) => new()
    {
        Header = Loc.Get(headerKey),
        PlaceholderText = placeholder ?? "",
        IsSpellCheckEnabled = false,
        IsTextPredictionEnabled = false,
    };

    private static PasswordBox Secret(string headerKey) => new() { Header = Loc.Get(headerKey) };

    private void PrefillLogin(LibraryFolder folder)
    {
        var host = SourceUrl.CredentialHost(folder.Path);
        if (host is null) return;
        if (folder.SourceKind == MediaSourceKind.Smb && AppServices.SmbCredentials.Get(host) is { } smb)
        {
            _username.Text = smb.Username;
        }
        else if (AppServices.ServerLogins.Get(folder.SourceKind, host) is { } login)
        {
            _username.Text = login.Username;
            if (login.S3 is { } s3)
            {
                _endpoint.Text = s3.Endpoint;
                _region.Text = s3.Region;
                _bucket.Text = s3.Bucket;
                _accessKey.Text = login.Username;
            }
        }
        else if (folder.SourceKind == MediaSourceKind.S3 && SourceUrl.ParseS3(folder.Path) is { } item)
        {
            _bucket.Text = item.Bucket;
        }
    }

    // ------------------------------------------------------------------
    // The dialog
    // ------------------------------------------------------------------

    public async Task<LinkResult?> ShowAsync()
    {
        _result = null;
        _error.Visibility = Visibility.Collapsed;
        var content = new StackPanel { Spacing = 12, MinWidth = 440, MaxWidth = 520 };
        content.Children.Add(_kindPicker);
        content.Children.Add(_fields);
        content.Children.Add(_progress);
        content.Children.Add(_error);

        _dialog = new ContentDialog
        {
            Title = Loc.Get(SignInAgain is null ? "Smb_AddNetworkSource" : "LinkSource_SignInAgainTitle"),
            Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
            CloseButtonText = Loc.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = _root,
        };
        BuildFields();
        _dialog.PrimaryButtonClick += (dialog, args) =>
        {
            // The form stays open while it connects; success closes it.
            args.Cancel = true;
            _ = ConnectAsync(guest: false);
        };
        _dialog.SecondaryButtonClick += (dialog, args) =>
        {
            args.Cancel = true;
            _ = ConnectAsync(guest: true);
        };
        _dialog.Closing += (_, _) => _work?.Cancel();
        _dialog.Opened += (_, _) =>
        {
            if (ConnectOnOpen)
            {
                ConnectOnOpen = false;
                _ = ConnectAsync(guest: false);
                return;
            }
            FirstEmptyField()?.Focus(FocusState.Programmatic);
        };
        await _dialog.ShowAsync();
        return _result;
    }

    /// <summary>The fields the chosen kind needs, in order; Enter checks them all.</summary>
    private Control[] RequiredFields => Kind switch
    {
        MediaSourceKind.Smb or MediaSourceKind.Sftp or MediaSourceKind.WebDav => [_address, _username, _password],
        MediaSourceKind.S3 => [_endpoint, _region, _bucket, _accessKey, _secretKey],
        MediaSourceKind.Nfs => [_address],
        _ => [],
    };

    private void BuildFields()
    {
        if (_dialog is null) return;
        _fields.Children.Clear();
        _error.Visibility = Visibility.Collapsed;
        var kind = Kind;

        void Note(string key) => _fields.Children.Add(new TextBlock
        {
            Text = Loc.Get(key),
            Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            TextWrapping = TextWrapping.Wrap,
        });

        switch (kind)
        {
            case MediaSourceKind.Smb:
                _address.PlaceholderText = @"\\SMB-SERVER\Share\Movies";
                Note("Smb_UncPrompt");
                _fields.Children.Add(_address);
                Note("Smb_CredentialNote");
                _fields.Children.Add(_username);
                _fields.Children.Add(_password);
                break;
            case MediaSourceKind.Sftp:
                _address.PlaceholderText = "nas.local:22/home/me/Films";
                Note("LinkSource_SftpNote");
                _fields.Children.Add(_address);
                _fields.Children.Add(_username);
                _fields.Children.Add(_password);
                break;
            case MediaSourceKind.WebDav:
                _address.PlaceholderText = "https://cloud.example.com/remote.php/dav/files/me";
                Note("LinkSource_WebDavNote");
                _fields.Children.Add(_address);
                _fields.Children.Add(_username);
                _fields.Children.Add(_password);
                break;
            case MediaSourceKind.S3:
                Note("LinkSource_S3Note");
                _fields.Children.Add(_endpoint);
                _fields.Children.Add(_region);
                _fields.Children.Add(_bucket);
                _fields.Children.Add(_accessKey);
                _fields.Children.Add(_secretKey);
                break;
            case MediaSourceKind.Nfs:
                _address.PlaceholderText = "nas.local/export/video";
                Note("LinkSource_NfsNote");
                _fields.Children.Add(_address);
                break;
            default:
                Note(kind == MediaSourceKind.OneDrive ? "LinkSource_OneDriveNote" : "LinkSource_DropboxNote");
                var accounts = AppServices.CloudAccounts.AccountsOf(kind);
                if (accounts.Count > 0 && SignInAgain is null)
                {
                    var items = accounts.Select(account => account.Label).ToList();
                    items.Add(Loc.Get("LinkSource_AnotherAccount"));
                    _accountPicker.ItemsSource = items;
                    _accountPicker.SelectedIndex = 0;
                    _fields.Children.Add(_accountPicker);
                }
                break;
        }

        var cloud = kind.IsCloudAccount();
        _dialog.PrimaryButtonText = Loc.Get(cloud ? "LinkSource_SignIn" : "LinkSource_Connect");
        // Guests are one click on the kinds that allow them.
        _dialog.SecondaryButtonText = SignInAgain is null && kind is MediaSourceKind.Smb or MediaSourceKind.WebDav
            ? Loc.Get("LinkSource_ConnectAsGuest")
            : "";
    }

    private Control? FirstEmptyField() => RequiredFields.FirstOrDefault(IsEmpty);

    private static bool IsEmpty(Control field) => field switch
    {
        TextBox text => string.IsNullOrWhiteSpace(text.Text),
        PasswordBox password => password.Password.Length == 0,
        _ => false,
    };

    private void Field_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter) return;
        // Stop the dialog's own Enter handling: the rule below decides.
        e.Handled = true;
        if (FirstEmptyField() is { } empty)
        {
            empty.Focus(FocusState.Keyboard);
            return;
        }
        _ = ConnectAsync(guest: false);
    }

    // ------------------------------------------------------------------
    // Connecting
    // ------------------------------------------------------------------

    private async Task ConnectAsync(bool guest)
    {
        if (_busy || _dialog is null) return;
        SetBusy(true);
        _work = new CancellationTokenSource();
        try
        {
            _result = await ConnectKindAsync(guest, _work.Token);
            if (_result is not null) _dialog.Hide();
        }
        catch (OperationCanceledException)
        {
            // Cancelled with the dialog.
        }
        catch (Exception failure) when (failure is ConnectorException or OAuthException)
        {
            ShowError(failure.Message);
        }
        catch (Exception failure)
        {
            ShowError(Loc.Format("LinkSource_Failed", failure.Message));
        }
        finally
        {
            SetBusy(false);
        }
    }

    /// <summary>Connects and saves the login. Null (with an error shown) when a field needs fixing.</summary>
    private async Task<LinkResult?> ConnectKindAsync(bool guest, CancellationToken cancellation)
    {
        var username = guest ? "" : _username.Text.Trim();
        var password = guest ? "" : _password.Password;

        switch (Kind)
        {
            case MediaSourceKind.Smb:
            {
                var path = SmbCredentialsStore.NormalizeUncPath(_address.Text);
                if (path is null) return Invalid(_address, Loc.Get("Smb_UncPrompt"));
                var share = SmbCredentialsStore.ShareFromUncPath(path)!;
                if (username.Length > 0) await Task.Run(() => NetworkShare.Connect(share, username, password), cancellation);
                if (!await Task.Run(() => Directory.Exists(path), cancellation)) return Invalid(_address, Loc.Format("Smb_CouldNotAccess", path));
                if (username.Length > 0) AppServices.SmbCredentials.Save(SmbCredentialsStore.HostFromUncPath(path)!, username, password);
                return new LinkResult { SmbPath = path };
            }

            case MediaSourceKind.Sftp:
            {
                if (ParseSftpAddress(_address.Text) is not { } address) return Invalid(_address, new ConnectorException(ConnectorFailure.InvalidAddress).Message);
                if (username.Length == 0) return Invalid(_username, Loc.Get("LinkSource_UsernameRequired"));
                var key = await SftpConnector.ProbeHostKeyAsync(address.Host, address.Port, cancellation);
                var pinned = AppServices.HostKeys.PinnedFingerprint(address.Host, address.Port);
                if (pinned != key.Fingerprint)
                {
                    return new LinkResult { HostKey = key, HostKeyChanged = pinned is not null, Host = address.Host, Port = address.Port };
                }
                var login = new ServerLogin { Kind = "sftp", Host = address.Host.ToLowerInvariant(), Username = username, Password = password, Port = address.Port };
                var connector = new SftpConnector(address.Host, address.Port, login, AppServices.HostKeys, address.Path);
                await connector.ValidateAsync(cancellation);
                AppServices.ServerLogins.Save(login);
                return new LinkResult { Connector = connector };
            }

            case MediaSourceKind.WebDav:
            {
                var host = WebDavConnector.CanonicalRoot(_address.Text) is { } root && SourceUrl.CredentialHost(root) is { } parsed ? parsed : null;
                if (host is null) return Invalid(_address, new ConnectorException(ConnectorFailure.InvalidAddress).Message);
                var login = username.Length > 0 || password.Length > 0
                    ? new ServerLogin { Kind = "webdav", Host = host, Username = username, Password = password }
                    : null;
                var connector = WebDavConnector.FromAddress(_address.Text, login)!;
                await connector.ValidateAsync(cancellation);
                if (login is not null) AppServices.ServerLogins.Save(login);
                return new LinkResult { Connector = connector };
            }

            case MediaSourceKind.S3:
            {
                var endpoint = _endpoint.Text.Trim();
                if (endpoint.Length == 0) return Invalid(_endpoint, new ConnectorException(ConnectorFailure.InvalidAddress).Message);
                if (!endpoint.Contains("://", StringComparison.Ordinal)) endpoint = "https://" + endpoint;
                if (!Uri.TryCreate(endpoint, UriKind.Absolute, out var endpointUri) || endpointUri.Scheme is not ("https" or "http"))
                {
                    return Invalid(_endpoint, new ConnectorException(ConnectorFailure.InvalidAddress).Message);
                }
                var bucket = _bucket.Text.Trim();
                var region = _region.Text.Trim() is { Length: > 0 } typed ? typed : "us-east-1";
                var configuration = new S3Configuration(endpoint.TrimEnd('/'), region, bucket, S3Connector.DefaultUsesPathStyle(endpoint, bucket));
                var connector = new S3Connector(configuration, _accessKey.Text.Trim(), _secretKey.Password);
                await connector.ValidateAsync(cancellation);
                AppServices.ServerLogins.Save(connector.ToLogin());
                return new LinkResult { Connector = connector };
            }

            case MediaSourceKind.Nfs:
            {
                var connector = NfsConnector.FromSource(_address.Text);
                if (connector is null) return Invalid(_address, new ConnectorException(ConnectorFailure.InvalidAddress).Message);
                await connector.ValidateAsync(cancellation);
                return new LinkResult { Connector = connector };
            }

            default:
            {
                var kind = Kind;
                var accounts = AppServices.CloudAccounts.AccountsOf(kind);
                CloudAccount account;
                if (SignInAgain is null && accounts.Count > 0 && _accountPicker.SelectedIndex >= 0 && _accountPicker.SelectedIndex < accounts.Count)
                {
                    account = accounts[_accountPicker.SelectedIndex];
                }
                else
                {
                    ShowStatus(Loc.Format("LinkSource_Waiting", kind.DisplayName()));
                    account = await CloudSignIn.SignInAsync(kind, SignedInPage(), cancellation);
                }
                IMediaConnector? connector = kind == MediaSourceKind.OneDrive
                    ? OneDriveConnector.Create(account, AppServices.CloudTokens)
                    : new DropboxConnector(account, AppServices.CloudTokens);
                if (connector is null) throw new ConnectorException(ConnectorFailure.SignInRequired, kind.DisplayName());
                await connector.ValidateAsync(cancellation);
                return new LinkResult { Connector = connector };
            }
        }
    }

    /// <summary>What the browser shows after the redirect: a plain page in the app's colors.</summary>
    private static string SignedInPage()
    {
        var heading = System.Net.WebUtility.HtmlEncode(Loc.Get("LinkSource_SignedInHeading"));
        var body = System.Net.WebUtility.HtmlEncode(Loc.Get("LinkSource_SignedInBody"));
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Edendale</title></head>"
            + "<body style=\"font-family:'Segoe UI',sans-serif;background:#0A0A0F;color:#E4E1E9;padding:48px\">"
            + $"<h1 style=\"color:#F4BE5D\">{heading}</h1><p>{body}</p></body></html>";
    }

    private LinkResult? Invalid(Control field, string message)
    {
        ShowError(message);
        field.Focus(FocusState.Programmatic);
        return null;
    }

    /// <summary>"host", "host:port", "host:port/path", or "sftp://host:port/path".</summary>
    internal static (string Host, int Port, string? Path)? ParseSftpAddress(string text)
    {
        var value = text.Trim();
        if (value.Length == 0) return null;
        if (!value.Contains("://", StringComparison.Ordinal)) value = "sftp://" + value;
        if (!SourceUrl.TrySplit(value, out var parts) || !parts.Scheme.Equals("sftp", StringComparison.OrdinalIgnoreCase) || parts.Host.Length == 0)
        {
            return null;
        }
        var segments = SourceUrl.PathSegments(value);
        return (parts.Host, parts.Port ?? 22, segments.Count > 0 ? "/" + string.Join("/", segments) : null);
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        _progress.IsActive = busy;
        if (_dialog is null) return;
        _dialog.IsPrimaryButtonEnabled = !busy;
        _dialog.IsSecondaryButtonEnabled = !busy;
        _kindPicker.IsEnabled = !busy && SignInAgain is null;
        if (busy) _error.Visibility = Visibility.Collapsed;
    }

    private void ShowStatus(string message)
    {
        _error.Text = message;
        _error.Visibility = Visibility.Visible;
    }

    private void ShowError(string message)
    {
        _error.Text = message;
        _error.Visibility = Visibility.Visible;
    }
}
