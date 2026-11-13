using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Documents;

namespace Edendale.Windows.Controls;

/// <summary>
/// Settings → Audio → Audio Enhancement (DIFF.md §3.6,
/// AudioEnhancementSection.swift): the profile chips, the equalizer graph,
/// and the preamp, all in absolute levels. The graph and the slider ask for
/// a level; the model stores it as an offset from the profile and this
/// control draws the result back, so what is drawn is always what plays
/// (before the player's booster). A profile change and Reset sweep the graph
/// to its new curve; drags and keys move it at once.
/// </summary>
public sealed partial class AudioEnhancementSettings : UserControl
{
    private readonly ChipGroup<AudioEnhancementProfile> _profiles;
    private bool _updating;

    public AudioEnhancementSettings()
    {
        InitializeComponent();

        _profiles = new ChipGroup<AudioEnhancementProfile>(
            ProfileChips,
            [.. AudioEnhancementProfiles.All.Select(profile => new ChipOption<AudioEnhancementProfile>(
                profile, profile.DisplayName(), profile.DisplayName()))],
            profile =>
            {
                // Another profile resets the adjustments: the points sweep to its curve.
                AppServices.AudioEnhancement.SelectProfile(profile);
                Refresh(animate: true);
            });

        PreampLabel.Text = Loc.Get("Audio_Preamp");
        AutomationProperties.SetName(PreampSlider, PreampLabel.Text);
        // Apple's monospacedDigit: the value keeps its width as it changes.
        Typography.SetNumeralAlignment(PreampValue, FontNumeralAlignment.Tabular);
        Graph.BandChanged += Graph_BandChanged;

        Refresh(animate: false);
        // Player Adjustments writes the booster into the same settings. It no
        // longer moves the preamp shown here, but every write redraws, and
        // levels that are already drawn change nothing (nor cut a sweep short).
        AppServices.PlayerSettings.Changed += (_, key) =>
        {
            if (AudioEnhancement.Owns(key)) DispatcherQueue.TryEnqueue(() => Refresh(animate: false));
        };
    }

    private void Graph_BandChanged(object? sender, EqualizerBandChangedEventArgs e)
    {
        AppServices.AudioEnhancement.SetBandLevel(e.Level, e.Band);
        Refresh(animate: false);
    }

    private void PreampSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_updating) return;
        AppServices.AudioEnhancement.SetPreampLevel(Math.Round(e.NewValue, MidpointRounding.AwayFromZero));
        Refresh(animate: false);
    }

    private void Refresh(bool animate)
    {
        var audio = AppServices.AudioEnhancement;
        _updating = true;
        try
        {
            _profiles.Select(audio.SelectedProfile);
            Graph.SetLevels(audio.EffectiveBands, animate);
            var preamp = audio.PreampLevel;
            PreampSlider.Value = preamp;
            PreampValue.Text = DecibelLabel.Text(preamp);
            ResetRow.Visibility = audio.HasUserAdjustments ? Visibility.Visible : Visibility.Collapsed;
        }
        finally
        {
            _updating = false;
        }
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        AppServices.AudioEnhancement.ResetUserAdjustments();
        Refresh(animate: true);
    }
}
