// Custom entry point (DISABLE_XAML_GENERATED_MAIN): protocol and file
// activations start a second process aimed at this exe, so Edendale is
// single-instance — the newcomer forwards its activation to the running
// instance and exits, and the link lands in the existing window.

using Microsoft.UI.Dispatching;
using Microsoft.Windows.AppLifecycle;

namespace Edendale.Windows;

public static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        WinRT.ComWrappersSupport.InitializeComWrappers();

        var mainInstance = AppInstance.FindOrRegisterForKey("EdendaleMain");
        var activation = AppInstance.GetCurrent().GetActivatedEventArgs();
        if (!mainInstance.IsCurrent)
        {
            // Redirect off the STA thread (the async wait deadlocks on it).
            var redirected = new SemaphoreSlim(0, 1);
            _ = Task.Run(async () =>
            {
                try
                {
                    await mainInstance.RedirectActivationToAsync(activation);
                }
                finally
                {
                    redirected.Release();
                }
            });
            redirected.Wait();
            return;
        }

        Microsoft.UI.Xaml.Application.Start(callbackParams =>
        {
            var context = new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread());
            SynchronizationContext.SetSynchronizationContext(context);
            _ = new App();
        });
    }
}
