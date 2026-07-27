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

        MainWindow = new MainWindow();
        MainWindow.Activate();
        AppServices.StartBackgroundSync();

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
}
