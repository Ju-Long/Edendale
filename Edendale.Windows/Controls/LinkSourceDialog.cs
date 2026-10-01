using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using VirtualKey = Windows.System.VirtualKey;

namespace Edendale.Windows.Controls;

/// <summary>
/// The Link Source form shared by the Downloaded page and Settings
/// (DIFF.md §3.15). The address is focused on open and Tab moves between
/// fields. Enter connects when every field is filled and otherwise moves to
/// the first empty one; a guest connection (no username or password) is one
/// click. Failures stay in the form, so nothing typed is lost.
/// </summary>
public sealed class LinkSourceDialog
{
    private readonly ContentDialog _dialog;
    private readonly TextBox _address;
    private readonly TextBox _username;
    private readonly PasswordBox _password;
    private readonly TextBlock _error;
    private readonly ProgressRing _progress;
    private string? _linkedPath;
    private bool _busy;

    private LinkSourceDialog(XamlRoot root, string? address)
    {
        _address = new TextBox
        {
            Header = Loc.Get("LinkSource_Address"),
            PlaceholderText = @"\\SMB-SERVER\Share\Movies",
            Text = address ?? "",
            IsSpellCheckEnabled = false,
        };
        _username = new TextBox
        {
            Header = Loc.Get("LinkSource_Username"),
            PlaceholderText = Loc.Get("Smb_UsernameOptional"),
            IsSpellCheckEnabled = false,
        };
        _password = new PasswordBox
        {
            Header = Loc.Get("LinkSource_Password"),
            PlaceholderText = Loc.Get("Smb_Password"),
        };
        if (address is not null && SmbCredentialsStore.HostFromUncPath(address) is { } host
            && AppServices.SmbCredentials.Get(host) is { } saved)
        {
            _username.Text = saved.Username;
        }

        _error = new TextBlock
        {
            Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EdendaleGoldBrush"],
            TextWrapping = TextWrapping.Wrap,
            Visibility = Visibility.Collapsed,
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetLiveSetting(
            _error, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        _progress = new ProgressRing { IsActive = false, Width = 18, Height = 18, HorizontalAlignment = HorizontalAlignment.Left };

        foreach (var field in new Control[] { _address, _username, _password })
        {
            field.KeyDown += Field_KeyDown;
        }

        _dialog = new ContentDialog
        {
            Title = Loc.Get("Smb_AddNetworkSource"),
            Content = new StackPanel
            {
                Spacing = 12,
                MinWidth = 420,
                Children =
                {
                    new TextBlock { Text = Loc.Get("Smb_UncPrompt"), TextWrapping = TextWrapping.Wrap },
                    _address,
                    new TextBlock
                    {
                        Text = Loc.Get("Smb_CredentialNote"),
                        Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                        TextWrapping = TextWrapping.Wrap,
                    },
                    _username,
                    _password,
                    _progress,
                    _error,
                },
            },
            PrimaryButtonText = Loc.Get("LinkSource_Connect"),
            SecondaryButtonText = Loc.Get("LinkSource_ConnectAsGuest"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = root,
        };
        _dialog.Opened += (_, _) =>
        {
            // Signing in again to a known source starts at the username.
            var first = string.IsNullOrWhiteSpace(_address.Text) ? (Control)_address : _username;
            first.Focus(FocusState.Programmatic);
        };
        _dialog.PrimaryButtonClick += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            args.Cancel = !await ConnectAsync(guest: false);
            deferral.Complete();
        };
        _dialog.SecondaryButtonClick += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            args.Cancel = !await ConnectAsync(guest: true);
            deferral.Complete();
        };
    }

    /// <summary>
    /// Shows the form and links the share when it connects. Returns the linked
    /// path, or null when cancelled. <paramref name="address"/> pre-fills the
    /// form to sign in to a source again.
    /// </summary>
    public static async Task<string?> ShowAsync(XamlRoot root, string? address = null)
    {
        var form = new LinkSourceDialog(root, address);
        await form._dialog.ShowAsync();
        if (form._linkedPath is not { } path) return null;
        await AppServices.Library.ImportFolderAsync(path);
        return path;
    }

    private async void Field_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter) return;
        // Stop the dialog's own Enter handling: the rule below decides.
        e.Handled = true;
        Control? empty = string.IsNullOrWhiteSpace(_address.Text) ? _address
            : string.IsNullOrWhiteSpace(_username.Text) ? _username
            : _password.Password.Length == 0 ? _password
            : null;
        if (empty is not null)
        {
            empty.Focus(FocusState.Keyboard);
            return;
        }
        if (await ConnectAsync(guest: false)) _dialog.Hide();
    }

    /// <summary>Signs in (unless a guest), checks the folder is reachable, and saves the login.</summary>
    private async Task<bool> ConnectAsync(bool guest)
    {
        if (_busy) return false;
        var path = SmbCredentialsStore.NormalizeUncPath(_address.Text);
        if (path is null)
        {
            ShowError(Loc.Get("Smb_UncPrompt"));
            _address.Focus(FocusState.Programmatic);
            return false;
        }

        var username = guest ? "" : _username.Text.Trim();
        var password = guest ? "" : _password.Password;
        SetBusy(true);
        try
        {
            var share = SmbCredentialsStore.ShareFromUncPath(path)!;
            if (username.Length > 0)
            {
                await Task.Run(() => NetworkShare.Connect(share, username, password));
            }
            if (!await Task.Run(() => Directory.Exists(path)))
            {
                ShowError(Loc.Format("Smb_CouldNotAccess", path));
                return false;
            }
            if (username.Length > 0)
            {
                AppServices.SmbCredentials.Save(SmbCredentialsStore.HostFromUncPath(path)!, username, password);
            }
            _linkedPath = path;
            return true;
        }
        catch (Exception failure)
        {
            ShowError(Loc.Format("Smb_ConnectFailed", path, failure.Message));
            return false;
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        _progress.IsActive = busy;
        _dialog.IsPrimaryButtonEnabled = !busy;
        _dialog.IsSecondaryButtonEnabled = !busy;
        if (busy) _error.Visibility = Visibility.Collapsed;
    }

    private void ShowError(string message)
    {
        _error.Text = message;
        _error.Visibility = Visibility.Visible;
    }
}
