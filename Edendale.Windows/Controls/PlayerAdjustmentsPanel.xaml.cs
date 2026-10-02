using System.Globalization;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using LibVLCSharp.Shared;
using LibVLCSharp.Shared.Structures;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;

namespace Edendale.Windows.Controls;

/// <summary>
/// Player Adjustments (F.4): track pickers (3.9), picture (3.7), enhancement
/// (E.4), and playback switches. Preferences are written to the device-local
/// store, where the player picks them up live; options that LibVLC reads only
/// when it opens the video output ask the shell to reopen at the same position.
/// </summary>
public sealed partial class PlayerAdjustmentsPanel : UserControl
{
    private const int SyncStepMilliseconds = 50;
    private const int SyncLimitMilliseconds = 10_000;

    private MediaPlayer? _player;
    private PlayerContext? _context;
    private bool _updating;
    private double _rate = 1.0;
    private GpuCapabilities _capabilities = GpuCapabilities.None;
    private readonly Dictionary<VideoAdjustment, (Slider Slider, TextBlock Value)> _pictureRows = [];
    private TextBlock? _audioDelayValue;
    private TextBlock? _subtitleDelayValue;
    private int _audioDelayMilliseconds;
    private int _subtitleDelayMilliseconds;

    public event RoutedEventHandler? CloseRequested;

    /// <summary>Fit/Fill changed; the shell applies the crop.</summary>
    public event EventHandler<bool>? AspectFillChanged;

    /// <summary>An option LibVLC reads at video-output time changed (F.3).</summary>
    public event EventHandler? EngineReopenRequested;

    /// <summary>The speed stepper asks for a new base rate.</summary>
    public event EventHandler<double>? RateChangeRequested;

    public PlayerAdjustmentsPanel()
    {
        InitializeComponent();
        BuildPictureRows();
        BuildSyncRows();
        RefreshToggles();
        ShowRate(1.0);
        ShowEnhancement(null, GpuCapabilities.None);
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke(this, new RoutedEventArgs());

    /// <summary>Binds the panel to the current player (or none between files).</summary>
    public void Bind(MediaPlayer? player, PlayerContext? context)
    {
        // Sync offsets belong to one file: a new item starts at zero, a reopen keeps them.
        if (!ReferenceEquals(context, _context))
        {
            _audioDelayMilliseconds = 0;
            _subtitleDelayMilliseconds = 0;
        }
        _player = player;
        _context = context;
        ApplySyncOffsets();
        RefreshToggles();
        RefreshPicture();
        RefreshTracks();
    }

    /// <summary>Moves keyboard focus into the panel when it opens.</summary>
    public void FocusFirst()
    {
        Control? target = VideoTrackSection.Visibility == Visibility.Visible ? VideoTrackBox
            : AudioTrackSection.Visibility == Visibility.Visible ? AudioTrackBox
            : _pictureRows.Count > 0 ? _pictureRows[VideoAdjustment.Brightness].Slider
            : null;
        target?.Focus(FocusState.Programmatic);
    }

    // ------------------------------------------------------------------
    // Tracks (3.9)
    // ------------------------------------------------------------------

    /// <summary>Rebuilds the track pickers and chapters from what LibVLC reports now.</summary>
    public void RefreshTracks()
    {
        _updating = true;
        try
        {
            if (_player is not { } player)
            {
                VideoTrackSection.Visibility = Visibility.Collapsed;
                AudioTrackSection.Visibility = Visibility.Collapsed;
                ChaptersSection.Visibility = Visibility.Collapsed;
                return;
            }

            var (video, audio, _) = PlayerEffects.Tracks(player, _context);
            FillTrackBox(VideoTrackSection, VideoTrackBox, video, player.VideoTrack, TrackLabels.VideoLabel);
            FillTrackBox(AudioTrackSection, AudioTrackBox, audio, player.AudioTrack, TrackLabels.AudioLabel);
            RefreshChapters(player);
        }
        finally
        {
            _updating = false;
        }
    }

    /// <summary>A picker appears only when the file has more than one track of the kind.</summary>
    private static void FillTrackBox(
        FrameworkElement section,
        ComboBox box,
        IReadOnlyList<PlayerTrack> tracks,
        int selectedId,
        Func<PlayerTrack, int, string> label)
    {
        section.Visibility = tracks.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        box.Items.Clear();
        if (tracks.Count <= 1) return;

        for (var index = 0; index < tracks.Count; index++)
        {
            var item = new ComboBoxItem { Content = label(tracks[index], index), Tag = tracks[index].Id };
            box.Items.Add(item);
            if (tracks[index].Id == selectedId) box.SelectedItem = item;
        }
    }

    private void VideoTrackBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updating || _player is null || (VideoTrackBox.SelectedItem as ComboBoxItem)?.Tag is not int id) return;
        _player.SetVideoTrack(id);
    }

    private void AudioTrackBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updating || _player is null || (AudioTrackBox.SelectedItem as ComboBoxItem)?.Tag is not int id) return;
        _player.SetAudioTrack(id);
    }

    // ------------------------------------------------------------------
    // Picture (3.7)
    // ------------------------------------------------------------------

    private void BuildPictureRows()
    {
        foreach (var adjustment in VideoAdjustmentRanges.All)
        {
            var title = new TextBlock
            {
                Text = adjustment.Title(),
                Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                Foreground = (Brush)Application.Current.Resources["EdendaleTextPrimaryBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            };
            var value = new TextBlock
            {
                Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Center,
            };
            var slider = new Slider
            {
                Minimum = adjustment.Minimum(),
                Maximum = adjustment.Maximum(),
                StepFrequency = adjustment.Step(),
                SmallChange = adjustment.Step(),
                LargeChange = adjustment.Step() * 4,
                Value = adjustment.Neutral(),
                IsThumbToolTipEnabled = false,
            };
            AutomationProperties.SetName(slider, adjustment.Title());
            var captured = adjustment;
            slider.ValueChanged += (_, args) =>
            {
                value.Text = captured.Label(args.NewValue);
                if (_updating) return;
                AppServices.VideoAdjustments.Set(captured, args.NewValue);
                SetPictureOriginalToggle(false);
            };

            var header = new Grid();
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            header.Children.Add(title);
            header.Children.Add(value);
            Grid.SetColumn(value, 1);

            var row = new StackPanel { Spacing = 0 };
            row.Children.Add(header);
            row.Children.Add(slider);
            PictureRows.Children.Add(row);
            _pictureRows[adjustment] = (slider, value);
        }
    }

    /// <summary>Shows the stored values (Ctrl+↑/↓ changes brightness from outside the panel).</summary>
    public void RefreshPicture()
    {
        _updating = true;
        try
        {
            var values = AppServices.VideoAdjustments.Values;
            foreach (var (adjustment, (slider, label)) in _pictureRows)
            {
                slider.Value = values[adjustment];
                label.Text = adjustment.Label(values[adjustment]);
            }
            PictureOriginalToggle.IsOn = AppServices.VideoAdjustments.IsShowingOriginal;
        }
        finally
        {
            _updating = false;
        }
    }

    private void SetPictureOriginalToggle(bool on)
    {
        if (PictureOriginalToggle.IsOn == on) return;
        var wasUpdating = _updating;
        _updating = true;
        PictureOriginalToggle.IsOn = on;
        _updating = wasUpdating;
    }

    /// <summary>Show Original applies neutral values without touching the stored ones.</summary>
    private void PictureOriginalToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.VideoAdjustments.IsShowingOriginal = PictureOriginalToggle.IsOn;
        if (_player is { } player) PlayerEffects.ApplyAdjustments(player, AppServices.VideoAdjustments.EffectiveValues);
    }

    private void PictureReset_Click(object sender, RoutedEventArgs e)
    {
        AppServices.VideoAdjustments.Reset();
        RefreshPicture();
    }

    // ------------------------------------------------------------------
    // Enhancement (E.4)
    // ------------------------------------------------------------------

    /// <summary>The preset menu, labels, and Motion Smoothing for what the builder decided.</summary>
    public void ShowEnhancement(VideoEnhancementResult? result, GpuCapabilities capabilities)
    {
        _capabilities = capabilities;
        _updating = true;
        try
        {
            var settings = AppServices.VideoEnhancement;
            PresetBox.Items.Clear();
            var presets = new List<VideoEnhancementPreset> { VideoEnhancementPreset.Off, VideoEnhancementPreset.Balanced };
            if (result?.ShowHighQuality ?? capabilities.GpuDenoise) presets.Add(VideoEnhancementPreset.HighQuality);
            var shown = result?.EffectivePreset == VideoEnhancementPreset.Off && settings.IsShowingOriginal
                ? settings.Preset
                : result?.EffectivePreset ?? settings.Preset;
            if (!presets.Contains(shown)) shown = VideoEnhancementPreset.Balanced;
            foreach (var preset in presets)
            {
                var item = new ComboBoxItem { Content = VideoEnhancementOptions.DisplayName(preset), Tag = preset };
                PresetBox.Items.Add(item);
                if (preset == shown) PresetBox.SelectedItem = item;
            }

            ResolutionLabel.Text = result?.ResolutionLabel ?? "";
            ResolutionLabel.Visibility = string.IsNullOrEmpty(result?.ResolutionLabel) ? Visibility.Collapsed : Visibility.Visible;

            var smoothingAvailable = result?.ShowMotionSmoothing == true;
            MotionSmoothingToggle.Visibility = smoothingAvailable ? Visibility.Visible : Visibility.Collapsed;
            MotionSmoothingToggle.IsOn = settings.MotionSmoothing;
            FrameRateLabel.Text = result?.FrameRateLabel ?? "";
            FrameRateLabel.Visibility = smoothingAvailable && !string.IsNullOrEmpty(result?.FrameRateLabel)
                ? Visibility.Visible
                : Visibility.Collapsed;
            EnhancementOriginalToggle.IsOn = settings.IsShowingOriginal;

            // What the hardware can't do is explained rather than offered.
            string? note = capabilities.IsSoftwareAdapter
                ? Loc.Get("Enhancement_SoftwareAdapter")
                : capabilities.RunningOnIntegratedGpu
                    ? Loc.Get("Enhancement_HybridLaptop")
                    : null;
            EnhancementNote.Text = note ?? "";
            EnhancementNote.Visibility = note is null ? Visibility.Collapsed : Visibility.Visible;
            PresetBox.IsEnabled = !capabilities.IsSoftwareAdapter;
        }
        finally
        {
            _updating = false;
        }
    }

    private void PresetBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updating || (PresetBox.SelectedItem as ComboBoxItem)?.Tag is not VideoEnhancementPreset preset) return;
        AppServices.VideoEnhancement.Preset = preset;
        AppServices.VideoEnhancement.IsShowingOriginal = false;
        EnhancementOriginalToggle.IsOn = false;
        EngineReopenRequested?.Invoke(this, EventArgs.Empty);
    }

    private void MotionSmoothingToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.VideoEnhancement.MotionSmoothing = MotionSmoothingToggle.IsOn;
        EngineReopenRequested?.Invoke(this, EventArgs.Empty);
    }

    // ------------------------------------------------------------------
    // Frame generation (ENHANCEMENT.md G)
    // ------------------------------------------------------------------

    /// <summary>
    /// The toggle shows on NVIDIA and Intel GPUs. The line under it names
    /// the rates and the engine while it runs, or why it can't for this file.
    /// </summary>
    public void ShowFrameGeneration(FrameGenerationBackend backend, string? status)
    {
        _updating = true;
        try
        {
            var available = backend != FrameGenerationBackend.None;
            FrameGenerationToggle.Visibility = available ? Visibility.Visible : Visibility.Collapsed;
            FrameGenerationToggle.IsOn = AppServices.VideoEnhancement.FrameGeneration;
            FrameGenerationLabel.Text = status ?? "";
            FrameGenerationLabel.Visibility = available && FrameGenerationToggle.IsOn && !string.IsNullOrEmpty(status)
                ? Visibility.Visible
                : Visibility.Collapsed;
        }
        finally
        {
            _updating = false;
        }
    }

    private void FrameGenerationToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.VideoEnhancement.FrameGeneration = FrameGenerationToggle.IsOn;
        EngineReopenRequested?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>
    /// Frame generation shows video half a frame late, so audio waits as long
    /// on top of the reader's own audio delay.
    /// </summary>
    public int AudioCompensationMilliseconds
    {
        get => _audioCompensationMilliseconds;
        set
        {
            if (_audioCompensationMilliseconds == value) return;
            _audioCompensationMilliseconds = value;
            ApplySyncOffsets();
        }
    }

    private int _audioCompensationMilliseconds;

    /// <summary>Show Original (decision D2) reopens the player unenhanced, about a second's pause.</summary>
    private void EnhancementOriginalToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.VideoEnhancement.IsShowingOriginal = EnhancementOriginalToggle.IsOn;
        EngineReopenRequested?.Invoke(this, EventArgs.Empty);
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private void RefreshToggles()
    {
        _updating = true;
        try
        {
            SkipPromptsToggle.IsOn = AppServices.SegmentPrompts.IsEnabled;
            BoosterToggle.IsOn = AppServices.AudioEnhancement.BoosterEnabled;
            SurroundToggle.IsOn = AppServices.PlayerPreferences.HeadphoneSurround;
            LoopToggle.IsOn = AppServices.PlayerPreferences.LoopEnabled;
            FillToggle.IsOn = AppServices.PlayerPreferences.AspectFill;
        }
        finally
        {
            _updating = false;
        }
    }

    /// <summary>The stepper label and bounds for the current base rate.</summary>
    public void ShowRate(double rate)
    {
        _rate = rate;
        SpeedValueButton.Content = PlayerLogic.RateLabel(rate);
        AutomationProperties.SetName(SpeedValueButton, Loc.Format("Adjustments_SpeedValue", PlayerLogic.RateLabel(rate)));
        SpeedDownButton.IsEnabled = rate > PlayerLogic.MinRate + PlayerLogic.RateTolerance;
        SpeedUpButton.IsEnabled = rate < PlayerLogic.MaxRate - PlayerLogic.RateTolerance;
    }

    private void SpeedDown_Click(object sender, RoutedEventArgs e) =>
        RateChangeRequested?.Invoke(this, PlayerLogic.DecrementedRate(_rate));

    private void SpeedUp_Click(object sender, RoutedEventArgs e) =>
        RateChangeRequested?.Invoke(this, PlayerLogic.IncrementedRate(_rate));

    /// <summary>The value itself resets to normal speed.</summary>
    private void SpeedReset_Click(object sender, RoutedEventArgs e) => RateChangeRequested?.Invoke(this, 1.0);

    private void SkipPromptsToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.SegmentPrompts.IsEnabled = SkipPromptsToggle.IsOn;
    }

    private void BoosterToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.AudioEnhancement.SetBooster(BoosterToggle.IsOn);
    }

    private void SurroundToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.PlayerPreferences.HeadphoneSurround = SurroundToggle.IsOn;
        EngineReopenRequested?.Invoke(this, EventArgs.Empty);
    }

    private void LoopToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.PlayerPreferences.LoopEnabled = LoopToggle.IsOn;
    }

    private void FillToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        AppServices.PlayerPreferences.AspectFill = FillToggle.IsOn;
        AspectFillChanged?.Invoke(this, FillToggle.IsOn);
    }

    // ------------------------------------------------------------------
    // Sync (X.6): audio and subtitle offsets for this file only
    // ------------------------------------------------------------------

    private void BuildSyncRows()
    {
        _audioDelayValue = AddSyncRow(Loc.Get("Adjustments_AudioDelay"), delta =>
        {
            _audioDelayMilliseconds = Math.Clamp(_audioDelayMilliseconds + delta, -SyncLimitMilliseconds, SyncLimitMilliseconds);
            ApplySyncOffsets();
        });
        _subtitleDelayValue = AddSyncRow(Loc.Get("Adjustments_SubtitleDelay"), delta =>
        {
            _subtitleDelayMilliseconds = Math.Clamp(_subtitleDelayMilliseconds + delta, -SyncLimitMilliseconds, SyncLimitMilliseconds);
            ApplySyncOffsets();
        });
    }

    private TextBlock AddSyncRow(string title, Action<int> step)
    {
        var row = new Grid { ColumnSpacing = 8 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var label = new TextBlock
        {
            Text = title,
            Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            Foreground = (Brush)Application.Current.Resources["EdendaleTextPrimaryBrush"],
            VerticalAlignment = VerticalAlignment.Center,
        };
        var value = new TextBlock
        {
            Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            MinWidth = 72,
            TextAlignment = TextAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var earlier = new Button { Content = "−", MinWidth = 40, Style = (Style)Application.Current.Resources["ArchiveSecondaryButtonStyle"] };
        var later = new Button { Content = "+", MinWidth = 40, Style = (Style)Application.Current.Resources["ArchiveSecondaryButtonStyle"] };
        AutomationProperties.SetName(earlier, Loc.Format("Adjustments_DelayEarlier", title));
        AutomationProperties.SetName(later, Loc.Format("Adjustments_DelayLater", title));
        earlier.Click += (_, _) => step(-SyncStepMilliseconds);
        later.Click += (_, _) => step(SyncStepMilliseconds);

        row.Children.Add(label);
        row.Children.Add(earlier);
        row.Children.Add(value);
        row.Children.Add(later);
        Grid.SetColumn(earlier, 1);
        Grid.SetColumn(value, 2);
        Grid.SetColumn(later, 3);
        SyncRows.Children.Add(row);
        return value;
    }

    private void ApplySyncOffsets()
    {
        if (_audioDelayValue is not null) _audioDelayValue.Text = DelayLabel(_audioDelayMilliseconds);
        if (_subtitleDelayValue is not null) _subtitleDelayValue.Text = DelayLabel(_subtitleDelayMilliseconds);
        if (_player is not { } player) return;
        // LibVLC takes microseconds.
        player.SetAudioDelay((_audioDelayMilliseconds + _audioCompensationMilliseconds) * 1000L);
        player.SetSpuDelay(_subtitleDelayMilliseconds * 1000L);
    }

    private static string DelayLabel(int milliseconds) =>
        string.Format(CultureInfo.CurrentCulture, "{0:+0;−0;0} ms", milliseconds);

    // ------------------------------------------------------------------
    // Chapters (X.7)
    // ------------------------------------------------------------------

    private void RefreshChapters(MediaPlayer player)
    {
        ChapterRows.Children.Clear();
        ChapterDescription[] chapters;
        try
        {
            chapters = player.FullChapterDescriptions();
        }
        catch (VLCException)
        {
            chapters = [];
        }

        ChaptersSection.Visibility = chapters.Length > 1 ? Visibility.Visible : Visibility.Collapsed;
        if (chapters.Length <= 1) return;

        for (var index = 0; index < chapters.Length; index++)
        {
            var chapter = chapters[index];
            var name = string.IsNullOrWhiteSpace(chapter.Name) ? Loc.Format("Adjustments_ChapterNumbered", index + 1) : chapter.Name;
            var offset = chapter.TimeOffset;
            var button = new Button
            {
                Style = (Style)Application.Current.Resources["ArchiveGhostButtonStyle"],
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Stretch,
                Padding = new Thickness(8, 6, 8, 6),
            };
            var content = new Grid();
            content.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            content.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var title = new TextBlock
            {
                Text = name,
                Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                Foreground = (Brush)Application.Current.Resources["EdendaleTextPrimaryBrush"],
                TextTrimming = TextTrimming.CharacterEllipsis,
                TextWrapping = TextWrapping.NoWrap,
            };
            var time = new TextBlock
            {
                Text = PlayerLogic.Timestamp(offset / 1000.0),
                Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                Margin = new Thickness(12, 0, 0, 0),
            };
            content.Children.Add(title);
            content.Children.Add(time);
            Grid.SetColumn(time, 1);
            button.Content = content;
            AutomationProperties.SetName(button, $"{name}, {time.Text}");
            button.Click += (_, _) =>
            {
                if (ReferenceEquals(_player, player)) player.Time = offset;
            };
            ChapterRows.Children.Add(button);
        }
    }
}
