using System;
using System.Globalization;
using System.IO;
using System.Threading.Tasks;
using System.Reflection;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;

namespace Edendale.Windows.Pages;

/// <summary>
/// Settings in Apple's categories (SettingsView.swift, Windows edition): the
/// category list beside the open category's page, as on the Mac, or a picker
/// above it when the page is narrow. Each page is laid out as Apple's
/// inset-grouped form. The page is cached for the session, so it reopens on
/// the category last shown; nothing is persisted.
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
            ChooseMoreVisibility = Controls.SourceActions.CanChooseMoreVideos(folder) ? Visibility.Visible : Visibility.Collapsed;
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
        /// <summary>Google Drive sources whose account is signed in here (3.20 R7).</summary>
        public Visibility ChooseMoreVisibility { get; }

        // What a screen reader calls the row's buttons: the action and the source, as on the Downloaded page.
        public string SignInName => $"{Loc.Get("LinkSource_SignIn")}, {Name}";
        public string ChooseMoreName => $"{Loc.Get("GoogleDrive_ChooseMoreTitle")}, {Name}";
        public string RescanName => $"{Loc.Get("Source_Rescan")}, {Name}";
        public string RemoveName => $"{Loc.Get("Source_Remove")}, {Name}";
    }

    /// <summary>
    /// Below this page width the category list gives way to the picker above
    /// the page. It is the page's own width, so folding the shell's sidebar
    /// (Ctrl+B) counts.
    /// </summary>
    private const double NarrowPageWidth = 720;

    /// <summary>TheIntroDB, credited in About as Apple's AboutSettingsSection links it.</summary>
    private static readonly Uri TheIntroDbSite = new("https://theintrodb.org");

    private readonly List<SettingsCategory> _categories = [.. SettingsCategories.Listed];
    private readonly Dictionary<SettingsCategory, FrameworkElement> _pages;
    private SettingsCategory? _category;
    private bool? _isNarrow;
    private bool _syncingSelection;

    public SettingsPage()
    {
        InitializeComponent();

        _pages = new()
        {
            [SettingsCategory.General] = GeneralPage,
            [SettingsCategory.PlayerControls] = PlayerControlsPage,
            [SettingsCategory.Audio] = AudioPage,
            [SettingsCategory.Subtitles] = SubtitlesPage,
            [SettingsCategory.Library] = LibraryPage,
            [SettingsCategory.Sync] = SyncPage,
            [SettingsCategory.Accounts] = AccountsPage,
            [SettingsCategory.ParentalControls] = ParentalControlsPage,
            [SettingsCategory.About] = AboutPage,
        };
        BuildCategories();

        VersionText.Text = AppVersionText.Display(
                typeof(App).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion)
            ?? Loc.Get("Settings_DevelopmentBuild");
        DataPathText.Text = AppPaths.DataDirectory;
        // Apple's equivalent row reads "Synced via your iCloud"; say what
        // Windows actually does instead of borrowing the claim.
        UpdateWatchProgressRow();
        AppServices.FileSync.StatusChanged += (_, _) => DispatcherQueue.TryEnqueue(UpdateWatchProgressRow);

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

    // ------------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------------

    /// <summary>
    /// A row per category in the list (icon and name) and in the picker. Both
    /// carry the category's name and an automation id for UI tests, so a
    /// screen reader hears "Player Controls, 2 of 9" rather than a type name.
    /// </summary>
    private void BuildCategories()
    {
        var name = Loc.Get("Settings_CategoryListName");
        AutomationProperties.SetName(CategoryList, name);
        AutomationProperties.SetName(CategoryPicker, name);

        var resources = Application.Current.Resources;
        foreach (var category in _categories)
        {
            var title = category.Title();

            // Apple draws the glyph in TextSecondary; the name follows the
            // row's state, gold when open (ListView.Resources).
            var icon = new Controls.SvgIcon
            {
                UriSource = new Uri($"ms-appx:///Assets/Icons/{category.IconName()}.svg"),
                Width = 16,
                Height = 16,
                Foreground = (Brush)resources["EdendaleTextSecondaryBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            };
            AutomationProperties.SetAccessibilityView(icon, AccessibilityView.Raw);
            var label = new TextBlock
            {
                Text = title,
                FontFamily = (FontFamily)resources["TextFontFamily"],
                FontSize = 14,
                TextWrapping = TextWrapping.WrapWholeWords,
                MaxLines = 2,
                VerticalAlignment = VerticalAlignment.Center,
            };
            var row = new Grid { ColumnSpacing = 12, MinHeight = 40 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            Grid.SetColumn(label, 1);
            row.Children.Add(icon);
            row.Children.Add(label);

            // At rest the name reads in TextPrimary, never the system's pure white.
            var item = new ListViewItem
            {
                Content = row,
                Tag = category,
                Foreground = (Brush)resources["EdendaleTextPrimaryBrush"],
            };
            AutomationProperties.SetName(item, title);
            AutomationProperties.SetAutomationId(item, "SettingsCategory_" + category.Tag());
            CategoryList.Items.Add(item);

            var choice = new ComboBoxItem { Content = title, Tag = category };
            AutomationProperties.SetAutomationId(choice, "SettingsCategoryPicker_" + category.Tag());
            CategoryPicker.Items.Add(choice);
        }
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        Show(e.Parameter is SettingsCategory requested ? requested : _category ?? SettingsCategories.Default);
    }

    /// <summary>
    /// Opens <paramref name="category"/>: its page alone is shown, at the top,
    /// under its name, and the list and the picker both select it. Focus stays
    /// where it is, so arrowing through the list keeps working.
    /// </summary>
    public void Show(SettingsCategory category)
    {
        _category = category;
        var title = category.Title();
        PageTitle.Text = title.ToUpper(CultureInfo.CurrentUICulture);
        AutomationProperties.SetName(PageTitle, title);
        foreach (var (page, panel) in _pages)
        {
            panel.Visibility = page == category ? Visibility.Visible : Visibility.Collapsed;
        }

        var index = _categories.IndexOf(category);
        _syncingSelection = true;
        CategoryList.SelectedIndex = index;
        CategoryPicker.SelectedIndex = index;
        _syncingSelection = false;
        PageScroll.ChangeView(null, 0, null, disableAnimation: true);
    }

    private void CategoryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingSelection) return;
        if (CategoryList.SelectedItem is ListViewItem { Tag: SettingsCategory category })
        {
            Show(category);
        }
        else if (_category is { } open)
        {
            // Ctrl+click can clear a single selection; the open category stays selected.
            _syncingSelection = true;
            CategoryList.SelectedIndex = _categories.IndexOf(open);
            _syncingSelection = false;
        }
    }

    private void CategoryPicker_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingSelection || CategoryPicker.SelectedItem is not ComboBoxItem { Tag: SettingsCategory category }) return;
        Show(category);
    }

    /// <summary>
    /// Moves the keyboard to the open category, in the list or, on a narrow
    /// page, the picker (Ctrl+, from the shell). Queued behind layout, so a
    /// page that has only just been navigated to has its rows by then.
    /// </summary>
    public void FocusCategories() =>
        DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, () =>
        {
            if (_isNarrow == true)
            {
                CategoryPicker.Focus(FocusState.Keyboard);
            }
            else if (CategoryList.ContainerFromIndex(CategoryList.SelectedIndex) is ListViewItem row)
            {
                row.Focus(FocusState.Keyboard);
            }
            else
            {
                CategoryList.Focus(FocusState.Keyboard);
            }
        });

    private void SettingsRoot_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var narrow = e.NewSize.Width < NarrowPageWidth;
        if (narrow == _isNarrow) return;

        // The list or the picker that had the keyboard is about to collapse;
        // hand the keyboard to the one taking its place.
        var hadFocus = _isNarrow is { } wasNarrow && HasFocusWithin(wasNarrow ? CategoryPicker : CategoryList);
        _isNarrow = narrow;
        VisualStateManager.GoToState(this, narrow ? "Narrow" : "Wide", false);
        if (hadFocus) FocusCategories();
    }

    private bool HasFocusWithin(DependencyObject container)
    {
        if (XamlRoot is null) return false;
        for (var node = FocusManager.GetFocusedElement(XamlRoot) as DependencyObject; node is not null; node = VisualTreeHelper.GetParent(node))
        {
            if (node == container) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    /// <summary>"Syncs through the OneDrive folder on this PC", or "Stored on this device" while sync is off.</summary>
    private void UpdateWatchProgressRow()
    {
        var status = AppServices.FileSync.Status;
        CloudSyncText.Text = status is { IsOn: true, LocationDescription: { } location }
            ? Loc.Format("Settings_SyncsThrough", location)
            : Loc.Get("Settings_StoredOnThisDevice");
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

        // A build without TMDB credentials has nothing to sign in to, so the
        // section hides unless a session or an approval is already there.
        TmdbAccountSection.Visibility =
            SettingsCategories.ShowsTmdbAccount(account.CanConnect, account.IsConnected, account.HasPendingApproval)
                ? Visibility.Visible
                : Visibility.Collapsed;

        // Short phrases, because this is now a trailing value beside an
        // "Account" label rather than a paragraph (SettingsView.swift reads
        // "Account / Connected").
        AccountStatusText.Text = account.IsConnected
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
        // A session left by a build with credentials can still sign out here, but not sync.
        SyncNowButton.Visibility = account.IsConnected && account.CanConnect ? Visibility.Visible : Visibility.Collapsed;
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

    private async void TheIntroDb_Click(object sender, RoutedEventArgs e) =>
        await global::Windows.System.Launcher.LaunchUriAsync(TheIntroDbSite);

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

    private async void ChooseMoreVideos_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as Button)?.Tag is LibraryFolder folder)
        {
            await Controls.SourceActions.ChooseMoreVideosAsync(XamlRoot, folder);
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
