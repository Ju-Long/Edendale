using System.Globalization;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace Edendale.Windows.Controls;

/// <summary>
/// Settings → Subtitles (DIFF.md §3.8), with a live preview, and the saved
/// subtitles kept on this device (Windows-only).
/// </summary>
public sealed partial class SubtitleAppearanceSettings : UserControl
{
    private readonly ChipGroup<SubtitleFontStyle> _fonts;
    private readonly ChipGroup<SubtitleTextColor> _textColors;
    private readonly ChipGroup<SubtitleBackgroundColor> _boxColors;
    private bool _updating;

    public SubtitleAppearanceSettings()
    {
        InitializeComponent();
        var appearance = AppServices.SubtitleAppearance;

        _fonts = new ChipGroup<SubtitleFontStyle>(
            FontChips,
            [.. SubtitleAppearance.Fonts.Select(font => new ChipOption<SubtitleFontStyle>(
                font,
                new TextBlock { Text = SubtitleAppearance.DisplayName(font), FontFamily = new FontFamily(SubtitleAppearance.FontFamily(font)) },
                SubtitleAppearance.DisplayName(font)))],
            font =>
            {
                appearance.Font = font;
                Refresh();
            });

        _textColors = new ChipGroup<SubtitleTextColor>(
            TextColorChips,
            [.. SubtitleAppearance.TextColors.Select(color => new ChipOption<SubtitleTextColor>(
                color, Swatch(SubtitleAppearance.Rgb(color), SubtitleAppearance.DisplayName(color)), SubtitleAppearance.DisplayName(color)))],
            color =>
            {
                appearance.TextColor = color;
                Refresh();
            });

        _boxColors = new ChipGroup<SubtitleBackgroundColor>(
            BoxColorChips,
            [.. SubtitleAppearance.BackgroundColors.Select(color => new ChipOption<SubtitleBackgroundColor>(
                color, Swatch(SubtitleAppearance.Rgb(color), SubtitleAppearance.DisplayName(color)), SubtitleAppearance.DisplayName(color)))],
            color =>
            {
                appearance.BackgroundColor = color;
                Refresh();
            });

        Refresh();

        // Saved subtitles change behind the page (a download, the 30-day
        // prune), so the row listens while it is on screen.
        Loaded += (_, _) =>
        {
            AppServices.SavedSubtitles.Changed += SavedSubtitles_Changed;
            RefreshSaved();
        };
        Unloaded += (_, _) => AppServices.SavedSubtitles.Changed -= SavedSubtitles_Changed;
    }

    /// <summary>A chip label with a small disc of the preset color.</summary>
    private static UIElement Swatch(int rgb, string name)
    {
        var panel = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        panel.Children.Add(new Ellipse
        {
            Width = 12,
            Height = 12,
            Fill = new SolidColorBrush(Color(rgb)),
            Stroke = (Brush)Application.Current.Resources["EdendaleOutlineBrightBrush"],
            StrokeThickness = 1,
            VerticalAlignment = VerticalAlignment.Center,
        });
        panel.Children.Add(new TextBlock { Text = name, VerticalAlignment = VerticalAlignment.Center });
        return panel;
    }

    /// <summary>The preset palette is product data (SubtitleAppearance), not design tokens.</summary>
    private static global::Windows.UI.Color Color(int rgb, byte alpha = 255) =>
        global::Windows.UI.Color.FromArgb(alpha, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);

    private void Refresh()
    {
        var appearance = AppServices.SubtitleAppearance;
        _updating = true;
        try
        {
            _fonts.Select(appearance.Font);
            _textColors.Select(appearance.TextColor);
            _boxColors.Select(appearance.BackgroundColor);
            OpacitySlider.Value = Math.Round(appearance.BackgroundOpacity * 100);
            OpacityValue.Text = string.Format(CultureInfo.CurrentCulture, "{0:P0}", appearance.BackgroundOpacity);
            ResetRow.Visibility = appearance.IsDefault ? Visibility.Collapsed : Visibility.Visible;

            PreviewText.FontFamily = new FontFamily(SubtitleAppearance.FontFamily(appearance.Font));
            PreviewText.Foreground = new SolidColorBrush(Color(SubtitleAppearance.Rgb(appearance.TextColor)));
            PreviewBox.Background = new SolidColorBrush(Color(
                SubtitleAppearance.Rgb(appearance.BackgroundColor),
                (byte)Math.Round(appearance.BackgroundOpacity * 255)));
        }
        finally
        {
            _updating = false;
        }
    }

    private void OpacitySlider_ValueChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        if (_updating) return;
        AppServices.SubtitleAppearance.BackgroundOpacity = e.NewValue / 100;
        Refresh();
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        AppServices.SubtitleAppearance.Reset();
        Refresh();
    }

    // ------------------------------------------------------------------
    // Saved subtitles
    // ------------------------------------------------------------------

    private void SavedSubtitles_Changed(object? sender, EventArgs e) => DispatcherQueue.TryEnqueue(RefreshSaved);

    /// <summary>"3 subtitles · 120 KB", the switch, and Remove All only when there is something to remove.</summary>
    private void RefreshSaved()
    {
        var store = AppServices.SavedSubtitles;
        var (count, bytes) = store.Summary();
        SavedSummary.Text = count == 0
            ? Loc.Get("SavedSubtitles_None")
            : Loc.Format(
                count == 1 ? "SavedSubtitles_SummaryOne" : "SavedSubtitles_SummaryOther",
                count,
                SavedSubtitleRules.FormatSize(bytes, Loc.Get("Size_Kilobytes"), Loc.Get("Size_Megabytes"), CultureInfo.CurrentUICulture));
        RemoveSavedButton.IsEnabled = count > 0;

        _updating = true;
        try
        {
            RemoveUnusedToggle.IsOn = store.RemoveUnusedEnabled;
        }
        finally
        {
            _updating = false;
        }
    }

    private void RemoveUnusedToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.SavedSubtitles.RemoveUnusedEnabled = RemoveUnusedToggle.IsOn;
    }

    /// <summary>Deleting confirms first, with the safe button as the default.</summary>
    private async void RemoveSaved_Click(object sender, RoutedEventArgs e)
    {
        var confirm = new ContentDialog
        {
            Title = Loc.Get("SavedSubtitles_RemoveTitle"),
            Content = new TextBlock { Text = Loc.Get("SavedSubtitles_RemoveMessage"), TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = Loc.Get("Common_Remove"),
            CloseButtonText = Loc.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot,
        };
        if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
        await Task.Run(AppServices.SavedSubtitles.RemoveAll);
        RefreshSaved();
    }
}
