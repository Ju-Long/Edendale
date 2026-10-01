using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Edendale.Windows.Controls;

/// <summary>Settings → Accounts (DIFF.md §3.12).</summary>
public sealed partial class AccountsSettings : UserControl
{
    public AccountsSettings()
    {
        InitializeComponent();
        void Refresh(object? sender, EventArgs e) => DispatcherQueue.TryEnqueue(Render);
        AppServices.SmbCredentials.Changed += Refresh;
        AppServices.ServerLogins.Changed += Refresh;
        AppServices.CloudAccounts.Changed += Refresh;
        // Source counts follow the library.
        AppServices.Library.Changed += Refresh;
        Render();
    }

    private void Render()
    {
        var logins = SavedLogins.All();
        NoAccountsRow.Visibility = logins.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        AccountsList.Children.Clear();
        var resources = Application.Current.Resources;

        foreach (var login in logins)
        {
            var row = new Grid { Padding = new Thickness(20), ColumnSpacing = 14 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var icon = new SvgIcon
            {
                UriSource = new Uri(login.Kind.IsCloudAccount()
                    ? "ms-appx:///Assets/Icons/circle-user-fill.svg"
                    : "ms-appx:///Assets/Icons/link.svg"),
                Width = 18,
                Height = 18,
                Foreground = (Brush)resources["EdendaleTextSecondaryBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            };
            row.Children.Add(icon);

            var sources = AppServices.Library.SourceCount(login.Kind, login.Key);
            var parts = new List<string> { login.Kind.DisplayName() };
            if (!string.IsNullOrEmpty(login.Detail)) parts.Add(login.Detail!);
            parts.Add(Loc.Plural("Plural_SourceOne", "Plural_SourceOther", sources));

            var text = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(new TextBlock
            {
                Text = login.Title,
                FontFamily = (FontFamily)resources["TextFontFamily"],
                FontSize = 15,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = (Brush)resources["EdendaleTextPrimaryBrush"],
                TextTrimming = TextTrimming.CharacterEllipsis,
            });
            text.Children.Add(new TextBlock
            {
                Text = string.Join(" · ", parts),
                Style = (Style)resources["BodySMTextStyle"],
                TextTrimming = TextTrimming.CharacterEllipsis,
            });
            Grid.SetColumn(text, 1);
            row.Children.Add(text);

            var forget = new Button
            {
                Style = (Style)resources["ArchiveGhostButtonStyle"],
                Content = Loc.Get(login.Kind.IsCloudAccount() ? "Account_SignOut" : "Account_Forget"),
                VerticalAlignment = VerticalAlignment.Center,
            };
            AutomationProperties.SetName(forget, $"{forget.Content}, {login.Title}");
            forget.Click += async (_, _) => await ForgetAsync(login, sources);
            Grid.SetColumn(forget, 2);
            row.Children.Add(forget);

            var container = new StackPanel();
            container.Children.Add(row);
            container.Children.Add(new Border { Style = (Style)resources["SettingsRowSeparatorStyle"] });
            AccountsList.Children.Add(container);
        }
    }

    private async Task ForgetAsync(SavedLogin login, int sources)
    {
        var message = sources > 0
            ? Loc.Format("Account_ForgetMessageInUse", login.Title, Loc.Plural("Plural_SourceOne", "Plural_SourceOther", sources))
            : Loc.Format("Account_ForgetMessage", login.Title);
        var confirm = new ContentDialog
        {
            Title = Loc.Get(login.Kind.IsCloudAccount() ? "Account_SignOutTitle" : "Account_ForgetTitle"),
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = Loc.Get(login.Kind.IsCloudAccount() ? "Account_SignOut" : "Account_Forget"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot,
        };
        if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
        SavedLogins.Forget(login);
    }
}
