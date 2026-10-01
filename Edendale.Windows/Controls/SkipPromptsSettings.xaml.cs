using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Edendale.Windows.Controls;

/// <summary>Settings → Skip Prompts (DIFF.md §3.5).</summary>
public sealed partial class SkipPromptsSettings : UserControl
{
    private bool _updating;

    public SkipPromptsSettings()
    {
        InitializeComponent();
        Refresh();
        // The same switch lives in Player Adjustments; stay in step with it.
        AppServices.PlayerSettings.Changed += (_, key) =>
        {
            if (key == Core.PlayerPreferences.SegmentPromptsEnabledKey) DispatcherQueue.TryEnqueue(Refresh);
        };
    }

    private void Refresh()
    {
        _updating = true;
        PromptsToggle.IsOn = AppServices.SegmentPrompts.IsEnabled;
        _updating = false;
    }

    private void PromptsToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.SegmentPrompts.IsEnabled = PromptsToggle.IsOn;
    }
}
