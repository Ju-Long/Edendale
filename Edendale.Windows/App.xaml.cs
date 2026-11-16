using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace Edendale.Windows;

/// <summary>
/// Application entry point for the Edendale WinUI 3 app.
/// </summary>
public partial class App : Application
{
    /// <summary>The shell window; pickers need its HWND for initialization.</summary>
    public static Window? MainWindow { get; private set; }

    public App()
    {
        // Core and the data services are compiled into the test library too,
        // so they read copy through AppText; point it at the resource loader.
        AppText.Resolver = Loc.Get;
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        ActivationService.Register();
        var googleDriveRemoved = AppServices.MigrateGoogleDriveToFileAccess();

        var shell = new MainWindow();
        MainWindow = shell;
        shell.Activate();
        AppServices.StartBackgroundSync();
        if (googleDriveRemoved)
        {
            shell.ShowNotice(Loc.Get("GoogleDrive_RelinkTitle"), Loc.Get("GoogleDrive_RelinkMessage"));
        }
        else
        {
            OfferOneDriveFolderOnce(shell);
        }

        // A protocol/file launch lands once the shell exists; later
        // activations arrive redirected from short-lived second processes.
        ActivationService.HandleCurrentActivation();
        AppInstance.GetCurrent().Activated += (_, activation) =>
            MainWindow?.DispatcherQueue.TryEnqueue(() =>
            {
                MainWindow?.Activate();
                ActivationService.Handle(activation);
            });
    }

    /// <summary>
    /// SYNC-PROTOCOL.md §8.4 and FEATURES-27.1.md §5.5: on the first launch
    /// after the update, a PC that has OneDrive (where builds before 27.1
    /// kept their replica, now imported) is offered the OneDrive folder as its
    /// sync location, once. Sync stays off until the person says yes.
    /// </summary>
    private static void OfferOneDriveFolderOnce(MainWindow shell)
    {
        var state = AppServices.SyncState;
        if (state.IsOn || state.DefaultLocationOffered || Services.Sync.SyncLocationFactory.OneDrivePresetRoot is null) return;
        state.DefaultLocationOffered = true;
        shell.ShowOffer(Loc.Get("Sync_OfferTitle"), Loc.Get("Sync_OfferMessage"), Loc.Get("Sync_OfferAction"), async () =>
        {
            // Once a location is set, Settings → Sync shows it syncing.
            if (shell.Content.XamlRoot is { } root
                && await Controls.SyncLocationDialog.ShowAsync(root, Controls.SyncLocationDialog.SuggestedSpace, preferOneDrive: true))
            {
                shell.ShowSettings(Core.SettingsCategory.Sync);
            }
        });
    }
}
