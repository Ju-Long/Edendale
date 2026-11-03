using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Remote;
using Edendale.Windows.Services.Sync;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Edendale.Windows.Controls;

/// <summary>
/// Settings → Sync (FEATURES-27.1.md §5.1): off by default. Once a location
/// is chosen it shows the location, the space, this device's name, when it
/// last synced (never claiming instant sync), the warnings of the last read,
/// and the other devices whose files it read, each removable (§8.3). Set Up
/// Another Device and Pair with Another Device open the handoff (§6).
/// </summary>
public sealed partial class SyncSettings : UserControl
{
    private readonly StackPanel _rows = new();

    public SyncSettings()
    {
        var resources = Application.Current.Resources;
        var root = new StackPanel();
        root.Children.Add(new TextBlock { Text = Loc.Get("Sync_Header"), Style = (Style)resources["SettingsSectionHeaderTextStyle"] });
        root.Children.Add(new Border { Style = (Style)resources["SettingsGroupBorderStyle"], Child = _rows });
        Content = root;

        AppServices.FileSync.StatusChanged += (_, _) => DispatcherQueue.TryEnqueue(Render);
        AppServices.CloudAccounts.Changed += (_, _) => DispatcherQueue.TryEnqueue(Render);
        Render();
    }

    private static ResourceDictionary Resources => Application.Current.Resources;

    private void Render()
    {
        var status = AppServices.FileSync.Status;
        _rows.Children.Clear();

        if (!status.IsOn || AppServices.SyncState.Location is not { } location)
        {
            AddBody(Loc.Get("Sync_OffNote"));
            AddSeparator();
            AddActions(
                GhostButton("Sync_ChooseLocationButton", "folder-circle-plus", async () => await SyncLocationDialog.ShowAsync(XamlRoot)),
                GhostButton("Sync_PairButton", "link", async () => await HandoffDialog.ShowAsync(XamlRoot, sending: false)));
            return;
        }

        AddValueRow(Loc.Get("Sync_LocationLabel"), status.LocationDescription ?? "");
        AddSeparator();
        AddValueRow(Loc.Get("Sync_SpaceLabel"), status.Space ?? "");
        AddSeparator();
        AddValueRow(Loc.Get("Sync_DeviceLabel"), AppServices.SyncState.DeviceName);
        AddSeparator();
        AddBody(status.IsSyncing
            ? Loc.Get("Sync_Syncing")
            : status.LastSyncMillis > 0
                // "t" is the reader's own short clock, as the TMDB section shows it.
                ? Loc.Format("Sync_LastSynced", DateTimeOffset.FromUnixTimeMilliseconds(status.LastSyncMillis).ToLocalTime().ToString("t"))
                : Loc.Get("Sync_NeverSynced"));

        // A login that is gone, or an account from before the write scope (§10.5).
        if (location.SourceKind is { } kind && kind.IsCloudAccount())
        {
            var account = location.AccountKey is { } key ? AppServices.CloudAccounts.Account(kind, key) : null;
            if (account is null)
            {
                AddWarning(Loc.Get("Sync_LoginMissing"));
            }
            else if (!CloudProviders.CanWriteSyncFiles(account))
            {
                AddWarning(Loc.Format("Sync_NeedsConsent", kind.DisplayName()));
                AddActions(GhostButton("Sync_SignInButton", "circle-user-fill", () => SignInAgainAsync(account)));
            }
        }
        if (status.LastError is { } error) AddWarning(Loc.Format("Sync_Failed", error));
        foreach (var warning in status.Warnings) AddWarning(warning);

        AddSeparator();
        if (status.Peers.Count == 0)
        {
            AddBody(Loc.Get("Sync_NoOtherDevices"));
        }
        foreach (var peer in status.Peers)
        {
            AddPeer(peer);
        }

        AddSeparator();
        AddActions(
            GhostButton("Sync_NowButton", "arrow-rotate-right", () => AppServices.FileSync.SyncNowAsync()),
            GhostButton("Sync_ChooseLocationButton", "folder-circle-plus", async () => await SyncLocationDialog.ShowAsync(XamlRoot)),
            GhostButton("Sync_SetUpButton", "link", async () => await HandoffDialog.ShowAsync(XamlRoot, sending: true)),
            GhostButton("Sync_PairButton", "link", async () => await HandoffDialog.ShowAsync(XamlRoot, sending: false)),
            GhostButton("Sync_TurnOffButton", "cloud-slash", TurnOffAsync));
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private void AddBody(string text) =>
        _rows.Children.Add(new TextBlock { Text = text, Style = (Style)Resources["SettingsBodyRowTextStyle"] });

    private void AddWarning(string text)
    {
        var block = new TextBlock
        {
            Text = text,
            Style = (Style)Resources["SettingsBodyRowTextStyle"],
            Foreground = (Brush)Resources["EdendaleGoldBrush"],
        };
        AutomationProperties.SetLiveSetting(block, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Polite);
        _rows.Children.Add(block);
    }

    private void AddSeparator() => _rows.Children.Add(new Border { Style = (Style)Resources["SettingsRowSeparatorStyle"] });

    private void AddValueRow(string label, string value)
    {
        var row = new Grid { Style = (Style)Resources["SettingsRowGridStyle"] };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.Children.Add(new TextBlock { Text = label, Style = (Style)Resources["SettingsRowLabelTextStyle"] });
        var valueText = new TextBlock { Text = value, Style = (Style)Resources["SettingsRowValueTextStyle"], IsTextSelectionEnabled = true };
        Grid.SetColumn(valueText, 1);
        row.Children.Add(valueText);
        _rows.Children.Add(row);
    }

    private void AddActions(params Button[] buttons)
    {
        var panel = new StackPanel { Style = (Style)Resources["SettingsActionRowPanelStyle"] };
        foreach (var button in buttons) panel.Children.Add(button);
        _rows.Children.Add(panel);
    }

    private static Button GhostButton(string key, string icon, Func<Task> action)
    {
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        content.Children.Add(new SvgIcon { UriSource = new Uri($"ms-appx:///Assets/Icons/{icon}.svg"), Width = 12, Height = 12 });
        content.Children.Add(new TextBlock { Text = Loc.Get(key) });
        var button = new Button { Style = (Style)Resources["ArchiveGhostButtonStyle"], Content = content };
        button.Click += async (_, _) =>
        {
            button.IsEnabled = false;
            try
            {
                await action();
            }
            finally
            {
                button.IsEnabled = true;
            }
        };
        return button;
    }

    /// <summary>One device whose file this device read: its name and kind, and Remove (§8.3).</summary>
    private void AddPeer(SyncPeer peer)
    {
        var row = new Grid { Padding = new Thickness(20, 14, 20, 14), ColumnSpacing = 14 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.Children.Add(new SvgIcon
        {
            UriSource = new Uri(peer.Platform is "tvos" or "androidtv" ? "ms-appx:///Assets/Icons/tv.svg" : "ms-appx:///Assets/Icons/link.svg"),
            Width = 18,
            Height = 18,
            Foreground = (Brush)Resources["EdendaleTextSecondaryBrush"],
            VerticalAlignment = VerticalAlignment.Center,
        });
        var text = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        var name = string.IsNullOrEmpty(peer.Name) ? Loc.Get("Sync_UnknownPlatform") : peer.Name;
        text.Children.Add(new TextBlock
        {
            Text = name,
            FontFamily = (FontFamily)Resources["TextFontFamily"],
            FontSize = 15,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = (Brush)Resources["EdendaleTextPrimaryBrush"],
            TextTrimming = TextTrimming.CharacterEllipsis,
        });
        text.Children.Add(new TextBlock { Text = PlatformName(peer.Platform), Style = (Style)Resources["BodySMTextStyle"] });
        Grid.SetColumn(text, 1);
        row.Children.Add(text);

        var remove = new Button { Style = (Style)Resources["ArchiveGhostButtonStyle"], Content = Loc.Get("Sync_RemoveDeviceButton") };
        AutomationProperties.SetName(remove, $"{Loc.Get("Sync_RemoveDevice")}, {name}");
        remove.Click += async (_, _) => await RemoveDeviceAsync(peer, name);
        Grid.SetColumn(remove, 2);
        row.Children.Add(remove);
        _rows.Children.Add(row);
    }

    /// <summary>The §3 platform names as people know them; proper names, not translated.</summary>
    private static string PlatformName(string platform) => platform switch
    {
        "ios" => "iPhone",
        "ipados" => "iPad",
        "macos" => "Mac",
        "tvos" => "Apple TV",
        "visionos" => "Apple Vision Pro",
        "android" => "Android",
        "androidtv" => "Android TV",
        "horizon" => "Meta Quest",
        "windows" => "Windows",
        "xbox" => "Xbox",
        // §5: an unknown platform reads as "another device".
        _ => Loc.Get("Sync_UnknownPlatform"),
    };

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /// <summary>
    /// §8.3 and H6: deleting the file stops that device's history reaching
    /// the others, but it keeps its login until access is revoked at the
    /// provider, which the dialog links to.
    /// </summary>
    private async Task RemoveDeviceAsync(SyncPeer peer, string name)
    {
        var body = new StackPanel { Spacing = 12, MaxWidth = 460 };
        body.Children.Add(new TextBlock { Text = Loc.Get("Sync_RemoveDeviceMessage"), TextWrapping = TextWrapping.Wrap });
        if (AppServices.SyncState.Location?.SourceKind is { } kind && ManageAccessUrl(kind) is { } url)
        {
            body.Children.Add(new HyperlinkButton { Content = Loc.Format("Sync_ManageAccess", kind.DisplayName()), NavigateUri = new Uri(url) });
        }
        var dialog = new ContentDialog
        {
            Title = $"{Loc.Get("Sync_RemoveDeviceTitle")}: {name}",
            Content = body,
            PrimaryButtonText = Loc.Get("Common_Remove"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            await AppServices.FileSync.RemoveDeviceAsync(peer.Id, CancellationToken.None);
        }
        catch (Exception error) when (error is ConnectorException or IOException or OAuthException)
        {
            await ShowMessageAsync(Loc.Format("Sync_Failed", error.Message));
        }
    }

    /// <summary>Where a person revokes Edendale's access (H6); servers change their password instead.</summary>
    private static string? ManageAccessUrl(MediaSourceKind kind) => kind switch
    {
        MediaSourceKind.GoogleDrive => "https://myaccount.google.com/connections",
        MediaSourceKind.OneDrive => "https://account.live.com/consent/Manage",
        MediaSourceKind.Dropbox => "https://www.dropbox.com/account/connected_apps",
        _ => null,
    };

    private async Task TurnOffAsync()
    {
        var description = AppServices.FileSync.Status.LocationDescription ?? "";
        var dialog = new ContentDialog
        {
            Title = Loc.Get("Sync_TurnOffTitle"),
            Content = new TextBlock { Text = Loc.Format("Sync_TurnOffMessage", description), TextWrapping = TextWrapping.Wrap, MaxWidth = 460 },
            PrimaryButtonText = Loc.Get("Sync_TurnOffKeep"),
            SecondaryButtonText = Loc.Get("Sync_TurnOffDelete"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot,
        };
        var choice = await dialog.ShowAsync();
        if (choice == ContentDialogResult.None) return;
        await AppServices.FileSync.TurnOffAsync(deleteOwnFile: choice == ContentDialogResult.Secondary, CancellationToken.None);
    }

    /// <summary>§10.5: an account from before the write scope consents again, then syncs.</summary>
    private async Task SignInAgainAsync(CloudAccount account)
    {
        try
        {
            using var cancellation = new CancellationTokenSource(CloudSignIn.BrowserTimeout);
            var signedIn = await CloudSignIn.SignInForSyncAsync(account.SourceKind, account.Email, LinkSourceForm.SignedInPage(), cancellation.Token);
            if (AppServices.SyncState.Location is { } location && signedIn.Key != location.AccountKey)
            {
                await AppServices.FileSync.SetLocationAsync(location with { AccountKey = signedIn.Key }, CancellationToken.None);
            }
            else
            {
                await AppServices.FileSync.SyncNowAsync();
            }
        }
        catch (Exception error) when (error is ConnectorException or OAuthException or IOException)
        {
            await ShowMessageAsync(error.Message);
        }
    }

    private async Task ShowMessageAsync(string message)
    {
        var dialog = new ContentDialog
        {
            Title = Loc.Get("Sync_DialogTitle"),
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap, MaxWidth = 460 },
            CloseButtonText = Loc.Get("Common_OK"),
            XamlRoot = XamlRoot,
        };
        await dialog.ShowAsync();
    }
}
