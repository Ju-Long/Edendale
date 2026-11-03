using Edendale.Windows.Core;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Handoff;
using Edendale.Windows.Services.Remote;
using Edendale.Windows.Services.Sync;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Edendale.Windows.Controls;

/// <summary>
/// Choose a Location (FEATURES-27.1.md §5.2, the windows column): the
/// OneDrive and iCloud for Windows folders on this PC as presets, any picked
/// folder (kept in the future-access list), a linked Google Drive, OneDrive,
/// or Dropbox account (or a new sign-in), or a server whose login is saved
/// under Sources. One location per device: choosing another keeps the local
/// store and offers to delete this PC's file at the old one (§4). The
/// privacy sentence of §5.1 is always shown.
/// </summary>
public static class SyncLocationDialog
{
    /// <summary>Suggested for the OneDrive migration, so every PC that accepts it lands in the same space.</summary>
    public const string SuggestedSpace = "Family";

    /// <param name="presetSpace">A space to pre-fill: a handoff's, or the migration's suggestion.</param>
    /// <param name="followUp">What a handoff left to do here: a folder to pick, or a Google sign-in with its hint and folder.</param>
    /// <param name="preferOneDrive">The migration offer: the OneDrive folder preselected.</param>
    public static async Task<bool> ShowAsync(XamlRoot root, string? presetSpace = null, HandoffFollowUp? followUp = null, bool preferOneDrive = false)
    {
        var previous = AppServices.SyncState.Location;
        var form = new SyncLocationForm(root, presetSpace ?? previous?.Space, followUp, preferOneDrive);
        var chosen = await form.ShowAsync();
        if (chosen is null) return false;
        if (previous is not null && !SameLocation(previous, chosen)) await OfferToDeleteOldFileAsync(root, previous);
        return true;
    }

    private static bool SameLocation(SyncLocationSettings a, SyncLocationSettings b) =>
        a.Kind == b.Kind && a.Space == b.Space && a.AccountKey == b.AccountKey
        && string.Equals(a.Path?.TrimEnd('/', '\\'), b.Path?.TrimEnd('/', '\\'), StringComparison.OrdinalIgnoreCase);

    /// <summary>§4: the old location keeps its files; this PC's own file goes only when asked.</summary>
    private static async Task OfferToDeleteOldFileAsync(XamlRoot root, SyncLocationSettings previous)
    {
        var dialog = new ContentDialog
        {
            Title = Loc.Get("Sync_SwitchTitle"),
            Content = new TextBlock { Text = Loc.Format("Sync_SwitchMessage", AppServices.FileSync.Describe(previous), previous.Space), TextWrapping = TextWrapping.Wrap, MaxWidth = 460 },
            PrimaryButtonText = Loc.Get("Sync_SwitchDelete"),
            CloseButtonText = Loc.Get("Sync_SwitchKeep"),
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = root,
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary)
        {
            await AppServices.FileSync.DeleteOwnFileAtAsync(previous, CancellationToken.None);
        }
    }
}

/// <summary>The form behind <see cref="SyncLocationDialog"/>.</summary>
internal sealed class SyncLocationForm
{
    private sealed record Choice(string Title, SyncLocationKind Kind)
    {
        public string? FolderRoot { get; init; }
        public bool PickFolder { get; init; }
        public CloudAccount? Account { get; init; }
        public MediaSourceKind? SignInKind { get; init; }
        public MediaSourceKind? ServerKind { get; init; }
        public string? ServerHost { get; init; }
        public string? ServerAddress { get; init; }
    }

    private readonly XamlRoot _root;
    private readonly HandoffFollowUp? _followUp;
    private readonly List<Choice> _choices;
    private readonly ComboBox _picker;
    private readonly StackPanel _fields = new() { Spacing = 12 };
    private readonly TextBox _space;
    private readonly TextBox _address = new() { IsSpellCheckEnabled = false, IsTextPredictionEnabled = false };
    private readonly TextBlock _folderPath;
    private readonly TextBlock _error;
    private readonly ProgressRing _progress = new() { IsActive = false, Width = 18, Height = 18, HorizontalAlignment = HorizontalAlignment.Left };
    private ContentDialog? _dialog;
    private CancellationTokenSource? _work;
    private SyncLocationSettings? _result;
    private string? _pickedFolder;
    private string? _pickedToken;
    private bool _busy;

    public SyncLocationForm(XamlRoot root, string? space, HandoffFollowUp? followUp, bool preferOneDrive)
    {
        _root = root;
        _followUp = followUp;
        _choices = BuildChoices();
        var resources = Application.Current.Resources;

        _picker = new ComboBox
        {
            Header = Loc.Get("Sync_LocationLabel"),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            ItemsSource = _choices.Select(choice => choice.Title).ToList(),
            SelectedIndex = Math.Max(0, InitialChoice(preferOneDrive)),
        };
        _picker.SelectionChanged += (_, _) => BuildFields();

        _space = new TextBox
        {
            Header = Loc.Get("Sync_SpaceHint"),
            PlaceholderText = SyncLocationDialog.SuggestedSpace,
            Text = space ?? "",
            MaxLength = 64,
            IsSpellCheckEnabled = false,
        };
        _folderPath = new TextBlock { Style = (Style)resources["BodySMTextStyle"], TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
        _error = new TextBlock
        {
            Style = (Style)resources["BodySMTextStyle"],
            Foreground = (Microsoft.UI.Xaml.Media.Brush)resources["EdendaleGoldBrush"],
            TextWrapping = TextWrapping.Wrap,
            Visibility = Visibility.Collapsed,
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetLiveSetting(_error, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
    }

    private Choice Selected => _choices[Math.Clamp(_picker.SelectedIndex, 0, _choices.Count - 1)];

    private static SyncLocationKind KindOf(MediaSourceKind kind) => kind switch
    {
        MediaSourceKind.GoogleDrive => SyncLocationKind.GoogleDrive,
        MediaSourceKind.OneDrive => SyncLocationKind.OneDrive,
        MediaSourceKind.Dropbox => SyncLocationKind.Dropbox,
        MediaSourceKind.WebDav => SyncLocationKind.WebDav,
        MediaSourceKind.Sftp => SyncLocationKind.Sftp,
        MediaSourceKind.Smb => SyncLocationKind.Smb,
        _ => SyncLocationKind.Folder,
    };

    private static List<Choice> BuildChoices()
    {
        var choices = new List<Choice>();
        if (SyncLocationFactory.OneDrivePresetRoot is { } oneDrive)
        {
            choices.Add(new Choice(Loc.Get("Sync_FolderOneDrive"), SyncLocationKind.Folder) { FolderRoot = oneDrive });
        }
        if (SyncLocationFactory.ICloudPresetRoot is { } icloud)
        {
            choices.Add(new Choice(Loc.Get("Sync_FolderICloud"), SyncLocationKind.Folder) { FolderRoot = icloud });
        }
        choices.Add(new Choice(Loc.Get("Sync_ProviderFolder"), SyncLocationKind.Folder) { PickFolder = true });
        foreach (var account in AppServices.CloudAccounts.All)
        {
            choices.Add(new Choice($"{account.SourceKind.DisplayName()} · {account.Label}", KindOf(account.SourceKind)) { Account = account });
        }
        foreach (var kind in new[] { MediaSourceKind.GoogleDrive, MediaSourceKind.OneDrive, MediaSourceKind.Dropbox })
        {
            if (!CloudProviders.IsConfigured(kind)) continue;
            choices.Add(new Choice($"{kind.DisplayName()} · {Loc.Get("LinkSource_AnotherAccount")}", KindOf(kind)) { SignInKind = kind });
        }
        foreach (var login in AppServices.ServerLogins.Logins.Where(login => login.Kind is MediaSourceKind.WebDav or MediaSourceKind.Sftp))
        {
            choices.Add(new Choice($"{login.Kind.DisplayName()} · {login.Title}", KindOf(login.Kind))
            {
                ServerKind = login.Kind,
                ServerHost = login.Key,
                ServerAddress = SuggestedAddress(login.Kind, login.Key),
            });
        }
        foreach (var (host, _) in AppServices.SmbCredentials.Logins)
        {
            choices.Add(new Choice($"SMB · {host}", SyncLocationKind.Smb)
            {
                ServerKind = MediaSourceKind.Smb,
                ServerHost = host,
                ServerAddress = SuggestedAddress(MediaSourceKind.Smb, host),
            });
        }
        // A share this Windows account already reaches needs no saved login.
        choices.Add(new Choice("SMB", SyncLocationKind.Smb) { ServerKind = MediaSourceKind.Smb, ServerAddress = @"\\" });
        return choices;
    }

    /// <summary>The first library source on that server, so the folder field starts somewhere real.</summary>
    private static string SuggestedAddress(MediaSourceKind kind, string host)
    {
        var source = AppServices.Library.Folders.FirstOrDefault(folder =>
            folder.SourceKind == kind && string.Equals(SourceUrl.CredentialHost(folder.Path) ?? SmbCredentialsStore.HostFromUncPath(folder.Path), host, StringComparison.OrdinalIgnoreCase));
        switch (kind)
        {
            case MediaSourceKind.WebDav:
                return source is not null ? WebDavConnector.HttpUrl(source.Path) ?? source.Path : $"https://{host}/";
            case MediaSourceKind.Sftp:
            {
                if (source is not null && SourceUrl.TrySplit(source.Path, out var parts))
                {
                    return $"{parts.Host}{(parts.Port is int port ? ":" + port : "")}{SftpConnector.PathOf(source.Path)}";
                }
                var login = AppServices.ServerLogins.Get(MediaSourceKind.Sftp, host);
                return $"{host}{(login?.Port is int saved && saved != 22 ? ":" + saved : "")}/";
            }
            default:
                return source is not null && SmbCredentialsStore.ShareFromUncPath(source.Path) is { } share ? share : $@"\\{host}\";
        }
    }

    /// <summary>A handoff's follow-up or the migration picks the first choice that fits.</summary>
    private int InitialChoice(bool preferOneDrive)
    {
        if (_followUp is HandoffFollowUp.GoogleSignIn google)
        {
            var linked = _choices.FindIndex(choice => choice.Account is { SourceKind: MediaSourceKind.GoogleDrive } account
                && string.Equals(account.Email, google.Hint, StringComparison.OrdinalIgnoreCase));
            return linked >= 0 ? linked : _choices.FindIndex(choice => choice.SignInKind == MediaSourceKind.GoogleDrive);
        }
        if (_followUp is HandoffFollowUp.PickFolder { Provider: "icloudDrive" } && SyncLocationFactory.ICloudPresetRoot is { } icloud)
        {
            return _choices.FindIndex(choice => choice.FolderRoot == icloud);
        }
        if (_followUp is HandoffFollowUp.PickFolder) return _choices.FindIndex(choice => choice.PickFolder);
        if (preferOneDrive && SyncLocationFactory.OneDrivePresetRoot is { } oneDrive) return _choices.FindIndex(choice => choice.FolderRoot == oneDrive);

        // The current location, when there is one.
        if (AppServices.SyncState.Location is { } current)
        {
            var index = _choices.FindIndex(choice => current.Kind switch
            {
                SyncLocationKind.Folder => choice.FolderRoot is { } root && string.Equals(root, current.Path, StringComparison.OrdinalIgnoreCase),
                SyncLocationKind.GoogleDrive or SyncLocationKind.OneDrive or SyncLocationKind.Dropbox => choice.Account?.Key == current.AccountKey,
                _ => choice.ServerKind is not null && choice.Kind == current.Kind && current.Path is { } path
                    && choice.ServerHost is { } host && path.Contains(host, StringComparison.OrdinalIgnoreCase),
            });
            if (index >= 0) return index;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // The dialog
    // ------------------------------------------------------------------

    public async Task<SyncLocationSettings?> ShowAsync()
    {
        var resources = Application.Current.Resources;
        var content = new StackPanel { Spacing = 12, MinWidth = 440, MaxWidth = 520 };
        if (_followUp is HandoffFollowUp.GoogleSignIn { Hint: { } hint } google)
        {
            content.Children.Add(Note(Loc.Format("Sync_GoogleRelink", hint, google.Space)));
        }
        else if (_followUp is HandoffFollowUp.PickFolder folder)
        {
            content.Children.Add(Note(Loc.Format("Sync_FolderRelink", folder.Space)));
        }
        content.Children.Add(_picker);
        content.Children.Add(_fields);
        content.Children.Add(_space);
        content.Children.Add(Note(Loc.Get("Sync_SpaceHelp")));
        content.Children.Add(Note(Loc.Get("Sync_PrivacyNote")));
        if (!_choices.Any(choice => choice.Account is not null || choice.ServerHost is not null)) content.Children.Add(Note(Loc.Get("Sync_ServersHint")));
        content.Children.Add(_progress);
        content.Children.Add(_error);

        _dialog = new ContentDialog
        {
            Title = Loc.Get("Sync_DialogTitle"),
            Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
            PrimaryButtonText = Loc.Get("LinkSource_Connect"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = _root,
        };
        BuildFields();
        _dialog.PrimaryButtonClick += (sender, args) =>
        {
            // The form stays open while it connects; success closes it.
            args.Cancel = true;
            _ = ConnectAsync();
        };
        _dialog.Closing += (_, _) => _work?.Cancel();
        await _dialog.ShowAsync();
        return _result;
    }

    private static TextBlock Note(string text) => new()
    {
        Text = text,
        Style = (Style)Application.Current.Resources["BodySMTextStyle"],
        TextWrapping = TextWrapping.Wrap,
    };

    private void BuildFields()
    {
        _fields.Children.Clear();
        _error.Visibility = Visibility.Collapsed;
        var choice = Selected;
        if (choice.FolderRoot is { } root)
        {
            _folderPath.Text = Path.Combine(root, SyncFormat.RootFolderName);
            _fields.Children.Add(_folderPath);
        }
        else if (choice.PickFolder)
        {
            _folderPath.Text = _pickedFolder ?? "";
            var pick = new Button { Content = Loc.Get("Sync_PickFolder") };
            pick.Click += async (_, _) => await PickFolderAsync();
            _fields.Children.Add(pick);
            _fields.Children.Add(_folderPath);
        }
        else if (choice.ServerKind is { } kind)
        {
            _address.Header = choice.ServerHost is { } host ? Loc.Format("Sync_ServerFolderLabel", host) : Loc.Get("LinkSource_Address");
            _address.PlaceholderText = kind switch
            {
                MediaSourceKind.WebDav => "https://cloud.example.com/remote.php/dav/files/me/",
                MediaSourceKind.Sftp => "nas.local:22/home/me",
                _ => @"\\SMB-SERVER\Share",
            };
            if (string.IsNullOrEmpty(_address.Text) || _address.Tag as string != choice.Title)
            {
                _address.Text = choice.ServerAddress ?? "";
                _address.Tag = choice.Title;
            }
            _fields.Children.Add(_address);
        }
        else if (choice.Account is { } account && !CloudProviders.CanWriteSyncFiles(account))
        {
            _fields.Children.Add(Note(Loc.Format("Sync_NeedsConsent", account.SourceKind.DisplayName())));
        }
    }

    /// <summary>
    /// A folder from the system picker, kept in the future-access list so a
    /// packaged build keeps reaching it. Unpackaged builds have no list and
    /// reach the path directly.
    /// </summary>
    private async Task PickFolderAsync()
    {
        var picker = new global::Windows.Storage.Pickers.FolderPicker();
        picker.FileTypeFilter.Add("*");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindow));
        var folder = await picker.PickSingleFolderAsync();
        if (folder is null) return;
        _pickedFolder = folder.Path;
        try
        {
            global::Windows.Storage.AccessCache.StorageApplicationPermissions.FutureAccessList.AddOrReplace("syncFolder", folder);
            _pickedToken = "syncFolder";
        }
        catch (Exception)
        {
            _pickedToken = null;
        }
        _folderPath.Text = _pickedFolder;
        _error.Visibility = Visibility.Collapsed;
    }

    // ------------------------------------------------------------------
    // Connecting
    // ------------------------------------------------------------------

    private async Task ConnectAsync()
    {
        if (_busy || _dialog is null) return;
        if (SyncSpaceName.Normalize(_space.Text) is not { } space)
        {
            ShowError(Loc.Get("Sync_SpaceHelp"));
            _space.Focus(FocusState.Programmatic);
            return;
        }
        SetBusy(true);
        _work = new CancellationTokenSource();
        try
        {
            var settings = await SettingsAsync(Selected, space, _work.Token);
            if (settings is null) return;
            await AppServices.FileSync.SetLocationAsync(settings, _work.Token);
            _result = settings;
            _dialog.Hide();
        }
        catch (OperationCanceledException)
        {
            // Cancelled with the dialog.
        }
        catch (Exception error) when (error is ConnectorException or OAuthException)
        {
            ShowError(error.Message);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            ShowError(Loc.Format("Sync_Failed", error.Message));
        }
        catch (Exception error)
        {
            ShowError(Loc.Format("Sync_Failed", error.GetType().Name));
        }
        finally
        {
            SetBusy(false);
        }
    }

    /// <summary>The settings for the choice, signing in where needed; null (with an error shown) when a field needs fixing.</summary>
    private async Task<SyncLocationSettings?> SettingsAsync(Choice choice, string space, CancellationToken cancellation)
    {
        var googleFolder = _followUp is HandoffFollowUp.GoogleSignIn google ? google.FolderId : null;
        if (choice.FolderRoot is { } root)
        {
            Directory.CreateDirectory(root);
            return new SyncLocationSettings { Kind = SyncLocationKind.Folder, Space = space, Path = root };
        }
        if (choice.PickFolder)
        {
            if (_pickedFolder is null)
            {
                ShowError(Loc.Format("Sync_FolderRelink", space));
                return null;
            }
            return new SyncLocationSettings { Kind = SyncLocationKind.Folder, Space = space, Path = _pickedFolder, FutureAccessToken = _pickedToken };
        }
        if (choice.Account is { } linked)
        {
            var account = linked;
            if (!CloudProviders.CanWriteSyncFiles(account))
            {
                // §10.5: an account from before 27.1 consents to the write scope once.
                ShowStatus(Loc.Format("LinkSource_Waiting", account.SourceKind.DisplayName()));
                account = await CloudSignIn.SignInForSyncAsync(account.SourceKind, account.Email, LinkSourceForm.SignedInPage(), cancellation);
            }
            return new SyncLocationSettings
            {
                Kind = choice.Kind,
                Space = space,
                AccountKey = account.Key,
                GoogleFolderId = account.SourceKind == MediaSourceKind.GoogleDrive ? googleFolder : null,
            };
        }
        if (choice.SignInKind is { } kind)
        {
            ShowStatus(Loc.Format("LinkSource_Waiting", kind.DisplayName()));
            var hint = _followUp is HandoffFollowUp.GoogleSignIn followUp && kind == MediaSourceKind.GoogleDrive ? followUp.Hint : null;
            var account = await CloudSignIn.SignInForSyncAsync(kind, hint, LinkSourceForm.SignedInPage(), cancellation);
            return new SyncLocationSettings
            {
                Kind = choice.Kind,
                Space = space,
                AccountKey = account.Key,
                GoogleFolderId = kind == MediaSourceKind.GoogleDrive ? googleFolder : null,
            };
        }
        switch (choice.ServerKind)
        {
            case MediaSourceKind.WebDav:
            {
                if (WebDavConnector.CanonicalRoot(_address.Text) is not { } davRoot) return Invalid(new ConnectorException(ConnectorFailure.InvalidAddress).Message);
                return new SyncLocationSettings { Kind = SyncLocationKind.WebDav, Space = space, Path = davRoot };
            }
            case MediaSourceKind.Sftp:
            {
                if (LinkSourceForm.ParseSftpAddress(_address.Text) is not { Path: { } path } address) return Invalid(new ConnectorException(ConnectorFailure.InvalidAddress).Message);
                if (AppServices.ServerLogins.Get(MediaSourceKind.Sftp, address.Host) is null || AppServices.HostKeys.PinnedFingerprint(address.Host, address.Port) is null)
                {
                    return Invalid(Loc.Get("Sync_ServerNeedsLogin"));
                }
                var sftpRoot = SourceUrl.Server("sftp", address.Host, address.Port == 22 ? null : address.Port,
                    path.Split('/', StringSplitOptions.RemoveEmptyEntries), isDirectory: true);
                return sftpRoot is null ? Invalid(new ConnectorException(ConnectorFailure.InvalidAddress).Message)
                    : new SyncLocationSettings { Kind = SyncLocationKind.Sftp, Space = space, Path = sftpRoot };
            }
            case MediaSourceKind.Smb:
            {
                if (SmbCredentialsStore.NormalizeUncPath(_address.Text) is not { } unc || SmbCredentialsStore.ShareFromUncPath(unc) is null)
                {
                    return Invalid(Loc.Get("Smb_UncPrompt"));
                }
                return new SyncLocationSettings { Kind = SyncLocationKind.Smb, Space = space, Path = unc };
            }
            default:
                return null;
        }
    }

    private SyncLocationSettings? Invalid(string message)
    {
        ShowError(message);
        _address.Focus(FocusState.Programmatic);
        return null;
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        _progress.IsActive = busy;
        if (_dialog is not null) _dialog.IsPrimaryButtonEnabled = !busy;
        _picker.IsEnabled = !busy;
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
