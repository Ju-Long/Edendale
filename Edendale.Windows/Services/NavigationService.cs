using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Animation;

namespace Edendale.Windows.Services;

/// <summary>Routes page pushes through the shell's single content frame.</summary>
public static class NavigationService
{
    public static Frame? Frame { get; set; }

    public static void Navigate(Type pageType, object? parameter = null)
    {
        Frame?.Navigate(pageType, parameter, new DrillInNavigationTransitionInfo());
    }

    public static void GoBack()
    {
        if (Frame?.CanGoBack == true) Frame.GoBack();
    }
}
