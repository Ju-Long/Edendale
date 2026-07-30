using System;
using System.IO;
using System.Threading.Tasks;
using System.Reflection;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Edendale.Windows.Pages;

/// <summary>
/// Version, Windows core status, TMDB account connector, cloud sync status,
/// data location, and the TMDB attribution, laid out as Apple's inset-grouped
/// form (SettingsView.swift, Windows edition).
/// </summary>
public sealed partial class SettingsPage : Page
{
    /// <summary>
    /// UI-only projection of a <see cref="LibraryFolder"/> for the source list.
    /// LibraryFolder carries no kind flag and no item count, so the row's icon
    /// and "SMB · 12 items" subtitle — both of which SourceRow.swift shows —
    /// are derived here from the path shape and LibraryService.ItemCount rather
    /// than added to the service. <see cref="Folder"/> is what the Rescan and
    /// Remove buttons put in their Tag, so the handlers still see the real model.
    /// </summary>
    public sealed class SourceRowItem
    {
        public SourceRowItem(LibraryFolder folder, int itemCount)
        {
            Folder = folder;
            // Same test LibraryService.Scan() uses to spot a network share.
            var isRemote = folder.Path.StartsWith(@"\\", StringComparison.Ordinal);
            IconUri = new Uri(isRemote
                ? "ms-appx:///Assets/Icons/link.svg"
                : "ms-appx:///Assets/Icons/folder-closed.svg");
            var items = Loc.Plural("Plural_ItemOne", "Plural_ItemOther", itemCount);
            Subtitle = $"{(isRemote ? "SMB" : "Local Folder")} · {items}";
        }

        public LibraryFolder Folder { get; }
        public string Name => Folder.Name;
        public string Path => Folder.Path;
        public Uri IconUri { get; }
        public string Subtitle { get; }
    }

    public SettingsPage()
    {
        InitializeComponent();

        var version = Assembly.GetExecutingAssembly().GetName().Version;
        VersionText.Text = version is null ? Loc.Get("Settings_DevelopmentBuild") : $"{version.ToString(3)} (pre-release)";
        CoreText.Text = WindowsCore.CoreVersion;
        CredentialText.Text = WindowsCore.HasTmdbCredentials
            ? Loc.Get("Settings_Configured")
            : Loc.Get("Settings_CredentialsMissing");
        DataPathText.Text = AppPaths.DataDirectory;
        // Apple's equivalent row reads "Synced via your iCloud"; say what
        // Windows actually does instead of borrowing the claim.
        CloudSyncText.Text = AppPaths.CloudReplicaDirectory is string replica
            ? Loc.Format("Settings_ReplicatedTo", replica)
            : Loc.Get("Settings_StoredOnThisDevice");

        _suppressStartupToggle = true;
        StartupToggle.IsOn = StartupService.IsEnabled;
        StartupToggle.IsEnabled = StartupService.IsAvailable;
        _suppressStartupToggle = false;

        AppServices.Account.StateChanged += (_, _) => DispatcherQueue.TryEnqueue(UpdateAccountUi);
        AppServices.Library.Changed += (_, _) => DispatcherQueue.TryEnqueue(UpdateSourcesUi);
        UpdateAccountUi();
        UpdateSourcesUi();
    }

    private bool _suppressStartupToggle;
    private string? _renderedApprovalUrl;
    private string? _renderingApprovalUrl;

    private void StartupToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_suppressStartupToggle) return;
        var wanted = StartupToggle.IsOn;
        if (StartupService.SetEnabled(wanted))
        {
            StartupStatusRow.Visibility = Visibility.Collapsed;
            return;
        }

        // Registry write refused — revert the switch and say so.
        _suppressStartupToggle = true;
        StartupToggle.IsOn = !wanted;
        _suppressStartupToggle = false;
        StartupStatusText.Text = Loc.Get("Settings_StartupRefused");
        StartupStatusRow.Visibility = Visibility.Visible;
    }

    private void UpdateAccountUi()
    {
        var account = AppServices.Account;

        // Short phrases, because this is now a trailing value beside an
        // "Account" label rather than a paragraph (SettingsView.swift reads
        // "Account / Connected").
        AccountStatusText.Text = !account.CanConnect
            ? Loc.Get("Settings_TmdbUnavailable")
            : account.IsConnected
                ? Loc.Format("Settings_ConnectedAs", account.AccountLabel ?? "TMDB user")
                : account.HasPendingApproval
                    ? Loc.Get("Settings_AwaitingApproval")
                    : Loc.Get("Settings_NotConnected");

        SyncStatusText.Text = account.LastSyncStatus ?? "";
        SyncStatusRow.Visibility = string.IsNullOrEmpty(account.LastSyncStatus)
            ? Visibility.Collapsed
            : Visibility.Visible;

        ApprovalPanel.Visibility = account.HasPendingApproval
            ? Visibility.Visible
            : Visibility.Collapsed;
        if (account.PendingApprovalUrl is string approvalUrl)
        {
            _ = RenderApprovalQrCodeAsync(approvalUrl);
        }
        else
        {
            _renderedApprovalUrl = null;
            _renderingApprovalUrl = null;
            ApprovalQrImage.Source = null;
        }

        ConnectButton.Visibility = account.CanConnect && !account.IsConnected && !account.HasPendingApproval
            ? Visibility.Visible
            : Visibility.Collapsed;
        ApproveDoneButton.Visibility = account.HasPendingApproval ? Visibility.Visible : Visibility.Collapsed;
        CancelConnectButton.Visibility = account.HasPendingApproval ? Visibility.Visible : Visibility.Collapsed;
        SyncNowButton.Visibility = account.IsConnected ? Visibility.Visible : Visibility.Collapsed;
        DisconnectButton.Visibility = account.IsConnected ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void ConnectAccount_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var approvalUrl = await AppServices.Account.BeginConnectAsync();
            await RenderApprovalQrCodeAsync(approvalUrl);
            var launched = await global::Windows.System.Launcher.LaunchUriAsync(new Uri(approvalUrl));
            if (!launched)
            {
                AccountStatusText.Text = Loc.Get("Tmdb_NoBrowser");
            }
        }
        catch (Exception failure)
        {
            AccountStatusText.Text = Loc.Format("Tmdb_ConnectFailed", failure.Message);
        }
    }

    private async Task RenderApprovalQrCodeAsync(string approvalUrl)
    {
        if (_renderedApprovalUrl == approvalUrl || _renderingApprovalUrl == approvalUrl) return;
        _renderingApprovalUrl = approvalUrl;
        try
        {
            ApprovalQrImage.Source = await QrCodeImageFactory.CreateAsync(approvalUrl);
            _renderedApprovalUrl = approvalUrl;
        }
        catch (Exception failure)
        {
            AccountStatusText.Text = Loc.Format("Tmdb_QrFailed", failure.Message);
        }
        finally
        {
            if (_renderingApprovalUrl == approvalUrl) _renderingApprovalUrl = null;
        }
    }

    private async void FinishConnect_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await AppServices.Account.CompleteConnectAsync();
        }
        catch (Exception failure)
        {
            AccountStatusText.Text =
                Loc.Format("Tmdb_ApprovalRejected", failure.Message);
        }
    }

    private void CancelConnect_Click(object sender, RoutedEventArgs e) =>
        AppServices.Account.CancelPendingConnect();

    private async void SyncNow_Click(object sender, RoutedEventArgs e) =>
        await AppServices.Account.SyncNowAsync();

    private async void Disconnect_Click(object sender, RoutedEventArgs e) =>
        await AppServices.Account.DisconnectAsync();

    private async void OpenDataFolder_Click(object sender, RoutedEventArgs e)
    {
        await global::Windows.System.Launcher.LaunchFolderPathAsync(AppPaths.DataDirectory);
    }

    private void UpdateSourcesUi()
    {
        var folders = AppServices.Library.Folders;
        var rows = new List<SourceRowItem>(folders.Count);
        foreach (var folder in folders)
        {
            rows.Add(new SourceRowItem(folder, AppServices.Library.ItemCount(folder)));
        }
        SourcesList.ItemsSource = rows;
        NoSourcesRow.Visibility = folders.Count > 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    private async void AddFolder_Click(object sender, RoutedEventArgs e)
    {
        var picker = new global::Windows.Storage.Pickers.FolderPicker();
        picker.FileTypeFilter.Add("*");
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindow);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
        var folder = await picker.PickSingleFolderAsync();
        if (folder is null) return;
        await AppServices.Library.ImportFolderAsync(folder.Path);
    }

    private async void AddNetworkFolder_Click(object sender, RoutedEventArgs e)
    {
        var pathBox = new TextBox { PlaceholderText = @"\\SMB-SERVER\Share\Movies" };
        var usernameBox = new TextBox { PlaceholderText = Loc.Get("Smb_UsernameOptional") };
        var passwordBox = new PasswordBox { PlaceholderText = Loc.Get("Smb_Password") };

        var dialog = new ContentDialog
        {
            Title = Loc.Get("Smb_AddNetworkSource"),
            Content = new StackPanel
            {
                Spacing = 12,
                MinWidth = 400,
                Children =
                {
                    new TextBlock { Text = Loc.Get("Smb_UncPrompt") },
                    pathBox,
                    new TextBlock
                    {
                        Text = Loc.Get("Smb_CredentialNote"),
                        Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                    },
                    usernameBox,
                    passwordBox,
                }
            },
            PrimaryButtonText = Loc.Get("Common_Add"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot,
        };

        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        var path = pathBox.Text.Trim();
        if (string.IsNullOrEmpty(path)) return;
        var username = usernameBox.Text.Trim();
        var password = passwordBox.Password;

        string? failure = null;
        try
        {
            var share = SmbCredentialsStore.ShareFromUncPath(path);
            if (share is not null && username.Length > 0)
            {
                await Task.Run(() => NetworkShare.Connect(share, username, password));
                var host = SmbCredentialsStore.HostFromUncPath(path)!;
                AppServices.SmbCredentials.Save(host, username, password);
            }

            if (await Task.Run(() => Directory.Exists(path)))
            {
                await AppServices.Library.ImportFolderAsync(path);
                return;
            }
            failure = Loc.Format("Smb_CouldNotAccess", path);
        }
        catch (Exception connectFailure)
        {
            failure = Loc.Format("Smb_ConnectFailed", path, connectFailure.Message);
        }

        if (failure is not null)
        {
            var errDialog = new ContentDialog
            {
                Title = Loc.Get("Smb_ConnectionFailedTitle"),
                Content = new TextBlock { Text = failure, TextWrapping = TextWrapping.Wrap },
                CloseButtonText = Loc.Get("Common_OK"),
                XamlRoot = XamlRoot,
            };
            await errDialog.ShowAsync();
        }
    }

    private async void RescanFolder_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as Button)?.Tag is LibraryFolder folder)
        {
            await AppServices.Library.RescanFolderAsync(folder);
        }
    }

    /// <summary>
    /// Unlinking always confirms first (SourceRow.swift). The copy says the two
    /// things the user needs: nothing is deleted where the files live, and the
    /// share's saved login goes with it once nothing else needs it.
    /// </summary>
    private async void RemoveFolder_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as Button)?.Tag is not LibraryFolder folder) return;

        var host = SmbCredentialsStore.HostFromUncPath(folder.Path);
        var message = host is not null
            ? Loc.Format("Source_RemoveMessageSmb", folder.Name, host)
            : Loc.Format("Source_RemoveMessageLocal", folder.Name);

        var confirm = new ContentDialog
        {
            Title = Loc.Get("Source_RemoveTitle"),
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = Loc.Get("Common_Remove"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            // Destructive: the safe button is the default one.
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot,
        };

        if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
        AppServices.Library.RemoveFolder(folder);
    }
}
