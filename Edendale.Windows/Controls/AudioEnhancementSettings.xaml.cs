using System.Globalization;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Edendale.Windows.Controls;

/// <summary>Settings → Audio Enhancement (DIFF.md §3.6).</summary>
public sealed partial class AudioEnhancementSettings : UserControl
{
    private readonly ChipGroup<AudioEnhancementProfile> _profiles;
    private readonly List<(Slider Slider, TextBlock Value, int Band)> _rows = [];
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
                AppServices.AudioEnhancement.SelectProfile(profile);
                Refresh();
            });

        AddRow(PreampRow, Loc.Get("Audio_Preamp"), band: -1);
        for (var band = 0; band < AudioEnhancementProfiles.BandCount; band++)
        {
            var row = new StackPanel();
            BandGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            Grid.SetColumn(row, band % 2);
            Grid.SetRow(row, band / 2);
            BandGrid.Children.Add(row);
            AddRow(row, Loc.Format("Audio_BandHertz", AudioEnhancementProfiles.BandFrequencyLabels[band]), band);
        }

        UpdateEqualizerToggle();
        Refresh();
        // The booster in Player Adjustments changes the effective preamp.
        AppServices.PlayerSettings.Changed += (_, key) =>
        {
            if (AudioEnhancement.Owns(key)) DispatcherQueue.TryEnqueue(Refresh);
        };
    }

    /// <summary><paramref name="band"/> −1 is the preamp.</summary>
    private void AddRow(Panel host, string title, int band)
    {
        var label = new TextBlock
        {
            Text = title,
            Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            VerticalAlignment = VerticalAlignment.Center,
        };
        var value = new TextBlock
        {
            Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            Foreground = (Brush)Application.Current.Resources["EdendaleGoldBrush"],
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var slider = new Slider
        {
            Minimum = AudioEnhancementProfiles.MinimumAmplification,
            Maximum = AudioEnhancementProfiles.MaximumAmplification,
            StepFrequency = 1,
            SmallChange = 1,
            LargeChange = 3,
            IsThumbToolTipEnabled = false,
        };
        AutomationProperties.SetName(slider, title);
        slider.ValueChanged += (_, args) =>
        {
            if (_updating) return;
            if (band < 0) AppServices.AudioEnhancement.SetUserPreampAdjustment(args.NewValue);
            else AppServices.AudioEnhancement.SetUserBandAdjustment(args.NewValue, band);
            Refresh();
        };

        var header = new Grid();
        header.Children.Add(label);
        header.Children.Add(value);
        host.Children.Add(header);
        host.Children.Add(slider);
        _rows.Add((slider, value, band));
    }

    private void Refresh()
    {
        var audio = AppServices.AudioEnhancement;
        _updating = true;
        try
        {
            _profiles.Select(audio.SelectedProfile);
            var user = audio.UserBandAdjustments;
            var effective = audio.EffectiveBands;
            foreach (var (slider, value, band) in _rows)
            {
                var adjustment = band < 0 ? audio.UserPreampAdjustment : user[band];
                var heard = band < 0 ? audio.EffectivePreamp : effective[band];
                slider.Value = adjustment;
                value.Text = Decibels(heard);
                AutomationProperties.SetHelpText(slider, Decibels(heard));
            }
            ResetRow.Visibility = audio.HasUserAdjustments ? Visibility.Visible : Visibility.Collapsed;
        }
        finally
        {
            _updating = false;
        }
    }

    private static string Decibels(double value) =>
        string.Format(CultureInfo.CurrentCulture, "{0:+0;−0;0} dB", value);

    private void EqualizerToggle_Click(object sender, RoutedEventArgs e)
    {
        EqualizerPanel.Visibility = EqualizerToggle.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        UpdateEqualizerToggle();
    }

    private void UpdateEqualizerToggle()
    {
        var label = Loc.Get(EqualizerToggle.IsChecked == true ? "Audio_HideEqualizer" : "Audio_ShowEqualizer");
        AutomationProperties.SetName(EqualizerToggle, label);
        ToolTipService.SetToolTip(EqualizerToggle, label);
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        AppServices.AudioEnhancement.ResetUserAdjustments();
        Refresh();
    }
}
