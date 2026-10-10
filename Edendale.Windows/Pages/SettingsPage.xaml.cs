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
    /// UI-only projection of a <see cref="LibraryFolder"/> for the source list
    /// (SourceRow.swift): its icon, "SMB · 12 items" subtitle, location, and
    /// the last scan's failure. <see cref="Folder"/> is what the row's buttons
    /// put in their Tag, so the handlers still see the real model.
    /// </summary>
    public sealed class SourceRowItem
    {
        public SourceRowItem(LibraryFolder folder, int itemCount)
        {
            Folder = folder;
            IconUri = new Uri(Controls.SourceActions.IconUri(folder));
            Subtitle = Controls.SourceActions.Subtitle(folder, itemCount);
            StateText = Controls.SourceActions.StateText(folder) ?? "";
            StateVisibility = StateText.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            SignInVisibility = Controls.SourceActions.NeedsSignIn(folder) ? Visibility.Visible : Visibility.Collapsed;
        }

        public LibraryFolder Folder { get; }
        public string Name => Folder.Name;
        /// <summary>Credential-free: a local path, a UNC share, or a readable provider path.</summary>
        public string Path => Folder.LocationDescription;
        public Uri IconUri { get; }
        public string Subtitle { get; }
        public string StateText { get; }
        public Visibility StateVisibility { get; }
        public Visibility SignInVisibility { get; }
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

        _suppressAudienceToggle = true;
        AudienceToggle.IsOn = AppServices.YoungAudience.IsEnabled;
        _suppressAudienceToggle = false;

        AppServices.Account.StateChanged += (_, _) => DispatcherQueue.TryEnqueue(UpdateAccountUi);
        AppServices.Library.Changed += (_, _) => DispatcherQueue.TryEnqueue(UpdateSourcesUi);
        UpdateAccountUi();
        UpdateSourcesUi();
    }

    private bool _suppressStartupToggle;
    private bool _suppressAudienceToggle;
    private string? _renderedApprovalUrl;
    private string? _renderingApprovalUrl;

    private void AudienceToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_suppressAudienceToggle) return;
        AppServices.YoungAudience.IsEnabled = AudienceToggle.IsOn;
    }

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

    private async void AddFolder_Click(object sender, RoutedEventArgs e) =>
        await Controls.SourceActions.AddLocalFolderAsync();

    private async void AddNetworkFolder_Click(object sender, RoutedEventArgs e) =>
        await Controls.LinkSourceDialog.ShowAsync(XamlRoot);

    private async void RescanFolder_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as Button)?.Tag is LibraryFolder folder)
        {
            await AppServices.Library.RescanFolderAsync(folder);
        }
    }

    private async void SignInFolder_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as Button)?.Tag is LibraryFolder folder)
        {
            await Controls.SourceActions.SignInAsync(XamlRoot, folder);
        }
    }

    private async void RemoveFolder_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as Button)?.Tag is LibraryFolder folder)
        {
            await Controls.SourceActions.RemoveAsync(XamlRoot, folder);
        }
    }
}
