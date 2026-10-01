using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Edendale.Windows.Controls;

/// <summary>
/// What a source row says and does, shared by the Downloaded page and
/// Settings → Sources (SourceRow.swift): the kind and item count, the last
/// scan's failure, Rescan, Sign In, and Remove.
/// </summary>
internal static class SourceActions
{
    /// <summary>"SMB · 12 items", with the linked username or account when there is one.</summary>
    public static string Subtitle(LibraryFolder folder, int itemCount)
    {
        var parts = new List<string> { folder.SourceKind.DisplayName() };
        if (!string.IsNullOrEmpty(folder.Username)) parts.Add(folder.Username!);
        parts.Add(Loc.Plural("Plural_ItemOne", "Plural_ItemOther", itemCount));
        return string.Join(" · ", parts);
    }

    public static string IconUri(LibraryFolder folder) => folder.IsRemote
        ? "ms-appx:///Assets/Icons/link.svg"
        : "ms-appx:///Assets/Icons/folder-closed.svg";

    /// <summary>
    /// The row's state line: why the last scan failed ("Offline — …" or
    /// "Sign in again — …"), or null when it scanned cleanly.
    /// </summary>
    public static string? StateText(LibraryFolder folder) =>
        AppServices.Library.StateFor(folder) is { } state
            ? Loc.Format(state.Kind == SourceStateKind.NeedsSignIn ? "Source_StateNeedsSignIn" : "Source_StateOffline", state.Message)
            : null;

    public static bool NeedsSignIn(LibraryFolder folder) =>
        AppServices.Library.StateFor(folder)?.Kind == SourceStateKind.NeedsSignIn;

    /// <summary>Signs in to the source again, then rescans it.</summary>
    public static async Task SignInAsync(XamlRoot root, LibraryFolder folder)
    {
        if (folder.SourceKind == MediaSourceKind.Smb)
        {
            // Linking the same path again rescans the existing source.
            await LinkSourceDialog.ShowAsync(root, folder.Path);
            return;
        }
        await AppServices.Library.RescanFolderAsync(folder);
    }

    /// <summary>
    /// Unlinking always confirms first. The copy says the two things the
    /// user needs: nothing is deleted where the files live, and a saved login
    /// stays in Settings → Accounts.
    /// </summary>
    public static async Task RemoveAsync(XamlRoot root, LibraryFolder folder)
    {
        var message = folder.IsRemote
            ? Loc.Format("Source_RemoveMessageRemote", folder.Name)
            : Loc.Format("Source_RemoveMessageLocal", folder.Name);
        var confirm = new ContentDialog
        {
            Title = Loc.Get("Source_RemoveTitle"),
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = Loc.Get("Common_Remove"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            // Destructive: the safe button is the default one.
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = root,
        };
        if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
        AppServices.Library.RemoveFolder(folder);
    }

    /// <summary>Opens the folder picker and imports the chosen folder.</summary>
    public static async Task AddLocalFolderAsync()
    {
        var picker = new global::Windows.Storage.Pickers.FolderPicker();
        picker.FileTypeFilter.Add("*");
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindow);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
        var folder = await picker.PickSingleFolderAsync();
        if (folder is null) return;
        await AppServices.Library.ImportFolderAsync(folder.Path);
    }
}
