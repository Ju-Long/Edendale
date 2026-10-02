using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using LibVLCSharp.Shared;
using LibVLCSharp.Shared.Structures;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Media;
using Edendale.Windows.Core;
using Edendale.Windows.Services;

namespace Edendale.Windows.Controls;

/// <summary>
/// The player chrome over the video: title, transport, timeline, volume,
/// skips that follow Settings → App Controls, the HUD, Up Next, skip prompts,
/// press-and-hold speed, and the online subtitle browser. Gestures and the
/// HUD live in the partial files beside this one.
/// </summary>
public sealed partial class PlayerControlsOverlay : UserControl
{
    private MediaPlayer? _mediaPlayer;
    private DispatcherQueueTimer _hideTimer;
    private DispatcherQueueTimer _progressTimer;
    private bool _isSliderManipulating;
    private bool _isPictureInPicture;

    /// <summary>What is playing, for the online subtitle search. Null for a bare file.</summary>
    private PlaybackRequest? _playback;

    /// <summary>The library context behind the item: Up Next and attached subtitles.</summary>
    private PlayerContext? _context;

    /// <summary>The speed the reader chose; a hold overrides it until release.</summary>
    private double _baseRate = 1.0;

    /// <summary>W.2: the level carries over from file to file within the session.</summary>
    private int _volume = 100;
    private bool _muted;
    private bool _suppressVolumeSlider;

    /// <summary>3.18: the chosen output device; null follows Windows' default.</summary>
    private string? _audioDevice;

    private bool _audioApplied;

    private CancellationTokenSource? _subtitleWork;

    /// <summary>Null means "search the reader's preferred languages".</summary>
    private IReadOnlyList<string>? _subtitleLanguages;

    private bool _languageBoxReady;

    public event RoutedEventHandler? CloseRequested;
    public event RoutedEventHandler? PlaylistRequested;
    public event RoutedEventHandler? AdjustmentsRequested;
    public event RoutedEventHandler? PictureInPictureRequested;

    /// <summary>The full-screen button, or a mouse double click on the video.</summary>
    public event RoutedEventHandler? FullScreenRequested;

    /// <summary>The Up Next card was chosen.</summary>
    public event EventHandler<PlaybackRequest>? PlayNextRequested;

    /// <summary>A credits skip that runs to the end of the file finishes the item.</summary>
    public event RoutedEventHandler? EndReachedBySkip;

    /// <summary>The base speed changed (per-title memory, a reopen, or the panel).</summary>
    public event EventHandler<double>? BaseRateChanged;

    public PlayerControlsOverlay()
    {
        this.InitializeComponent();

        _hideTimer = DispatcherQueue.CreateTimer();
        _hideTimer.Interval = TimeSpan.FromSeconds(3);
        _hideTimer.Tick += (s, e) => HideControls();

        _progressTimer = DispatcherQueue.CreateTimer();
        _progressTimer.Interval = TimeSpan.FromMilliseconds(250);
        _progressTimer.Tick += (s, e) => UpdateProgress();

        InitializeGestures();
        InitializeHud();

        TimelineSlider.AddHandler(PointerPressedEvent, new PointerEventHandler(TimelineSlider_PointerPressed), true);
        TimelineSlider.AddHandler(PointerReleasedEvent, new PointerEventHandler(TimelineSlider_PointerReleased), true);
        TimelineSlider.AddHandler(PointerCanceledEvent, new PointerEventHandler(TimelineSlider_PointerReleased), true);
        TimelineSlider.AddHandler(PointerCaptureLostEvent, new PointerEventHandler(TimelineSlider_PointerReleased), true);

        RefreshSkipLabels();
        UpdateVolumeUi();
        SetFullScreenActive(false);
        SetPictureInPictureActive(false);
        UpdatePlayPauseLabels(isPlaying: false);

        // Skip lengths are read at every gesture; the labels follow live.
        AppServices.PlayerSettings.Changed += (_, key) =>
        {
            if (key is PlayerControlPreferences.SkipBackwardKey or PlayerControlPreferences.SkipForwardKey)
            {
                DispatcherQueue.TryEnqueue(RefreshSkipLabels);
            }
        };
    }

    /// <summary>
    /// Binds the overlay to a player. <paramref name="request"/> is what the
    /// online subtitle search matches on; <paramref name="context"/> feeds Up
    /// Next and records attached subtitles.
    /// </summary>
    public void SetMediaPlayer(
        MediaPlayer? player, string title, string? subtitle, PlaybackRequest? request, PlayerContext? context)
    {
        if (_mediaPlayer != null)
        {
            _mediaPlayer.Playing -= MediaPlayer_StateChanged;
            _mediaPlayer.Paused -= MediaPlayer_StateChanged;
            _mediaPlayer.Stopped -= MediaPlayer_StateChanged;
            _mediaPlayer.EndReached -= MediaPlayer_StateChanged;
            _mediaPlayer.EncounteredError -= MediaPlayer_StateChanged;
            _mediaPlayer.LengthChanged -= MediaPlayer_LengthChanged;
        }

        // A different item invalidates any in-flight search, the speed, and Up Next.
        if (!ReferenceEquals(request, _playback))
        {
            CloseSubtitleBrowser();
            _baseRate = 1.0;
            BaseRateChanged?.Invoke(this, _baseRate);
        }
        _playback = request;
        _context = context;
        CancelHold();
        HideUpNext();
        RefreshSegmentPrompt();

        _mediaPlayer = player;
        _audioApplied = false;
        if (title.Length > 0 || player is null)
        {
            TitleText.Text = title;
            SubtitleText.Text = subtitle ?? "";
            SubtitleText.Visibility = string.IsNullOrEmpty(subtitle) ? Visibility.Collapsed : Visibility.Visible;
        }

        if (_mediaPlayer != null)
        {
            _mediaPlayer.Playing += MediaPlayer_StateChanged;
            _mediaPlayer.Paused += MediaPlayer_StateChanged;
            _mediaPlayer.Stopped += MediaPlayer_StateChanged;
            _mediaPlayer.EndReached += MediaPlayer_StateChanged;
            _mediaPlayer.EncounteredError += MediaPlayer_StateChanged;
            _mediaPlayer.LengthChanged += MediaPlayer_LengthChanged;

            _mediaPlayer.SetRate((float)_baseRate);
            UpdatePlayPauseIcon();
            UpdateDuration();
            UpdateProgress();

            _progressTimer.Start();
            ShowControls();
        }
        else
        {
            _progressTimer.Stop();
            RefreshChapterMarks();
        }
    }

    private void HideControls()
    {
        // The subtitle browser is anchored to the bottom bar, so leave the
        // controls up for as long as it is open.
        if (SubtitleBrowser.Visibility == Visibility.Visible) return;

        if (_mediaPlayer?.IsPlaying == true)
        {
            VisualStateManager.GoToState(this, "ControlsHidden", true);
        }
    }

    private void ShowControls()
    {
        VisualStateManager.GoToState(this, "ControlsVisible", true);
        _hideTimer.Stop();
        _hideTimer.Start();
    }

    private bool AreControlsShown => OverlayContainer.IsHitTestVisible;

    private void UserControl_PointerMoved(object sender, PointerRoutedEventArgs e)
    {
        TrackPointerDrag(e);
        ShowControls();
    }

    private void UserControl_PointerExited(object sender, PointerRoutedEventArgs e)
    {
        HideControls();
    }

    /// <summary>Esc closes the online subtitle browser before anything else.</summary>
    public bool DismissTransient()
    {
        if (SubtitleBrowser.Visibility != Visibility.Visible) return false;
        CloseSubtitleBrowser();
        Focus(FocusState.Programmatic);
        return true;
    }

    private void BackButton_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke(this, new RoutedEventArgs());

    private void SidebarButton_Click(object sender, RoutedEventArgs e) => PlaylistRequested?.Invoke(this, new RoutedEventArgs());

    private void AdjustmentsButton_Click(object sender, RoutedEventArgs e) => AdjustmentsRequested?.Invoke(this, new RoutedEventArgs());

    private void FullScreenButton_Click(object sender, RoutedEventArgs e) => FullScreenRequested?.Invoke(this, new RoutedEventArgs());

    private void PlayPauseButton_Click(object sender, RoutedEventArgs e) => TogglePlayPause();

    public void TogglePlayPause()
    {
        if (_mediaPlayer == null) return;

        if (_mediaPlayer.IsPlaying)
        {
            _mediaPlayer.Pause();
        }
        else
        {
            _mediaPlayer.Play();
            // Reapply the rate after resuming so rapid toggles never stall it (26.1 fix).
            _mediaPlayer.SetRate((float)(_holdSide is { } side ? AppServices.Controls.HoldRate(side) : _baseRate));
        }
        ShowControls();
    }

    // ------------------------------------------------------------------
    // Skips (3.1): one length per direction drives every skip
    // ------------------------------------------------------------------

    public void Skip(SkipDirection direction)
    {
        if (_mediaPlayer == null) return;
        var offset = AppServices.Controls.SkipOffset(direction);
        _mediaPlayer.Time = PlayerLogic.SkipTarget(_mediaPlayer.Time, offset, _mediaPlayer.Length);
        var seconds = Math.Abs(offset);
        ShowHud(
            Loc.Format(direction == SkipDirection.Backward ? "Hud_SkipBack" : "Hud_SkipForward", seconds),
            SkipIcon(direction, seconds));
    }

    /// <summary>X.1: the D-pad's fine seek, shown as the new time.</summary>
    public void SeekBy(int seconds)
    {
        if (_mediaPlayer == null) return;
        var target = PlayerLogic.SkipTarget(_mediaPlayer.Time, seconds, _mediaPlayer.Length);
        _mediaPlayer.Time = target;
        ShowHud(PlayerLogic.Timestamp(target / 1000.0), null);
    }

    /// <summary>X.1: a trigger held past its threshold plays at that side's hold speed.</summary>
    public void SetControllerHold(HoldSide? side)
    {
        if (side is { } held) BeginHold(held);
        else EndHold();
    }

    private void SkipBack_Click(object sender, RoutedEventArgs e) => Skip(SkipDirection.Backward);
    private void SkipForward_Click(object sender, RoutedEventArgs e) => Skip(SkipDirection.Forward);

    private static string SkipIcon(SkipDirection direction, int seconds) =>
        $"ms-appx:///Assets/Icons/arrow-rotate-{(direction == SkipDirection.Backward ? "left" : "right")}-{seconds}.svg";

    /// <summary>The skip buttons' glyphs, tooltips, and names show the current lengths.</summary>
    private void RefreshSkipLabels()
    {
        var back = (int)AppServices.Controls.SkipBackwardInterval;
        var forward = (int)AppServices.Controls.SkipForwardInterval;
        SkipBackIcon.UriSource = new Uri(SkipIcon(SkipDirection.Backward, back));
        SkipForwardIcon.UriSource = new Uri(SkipIcon(SkipDirection.Forward, forward));
        var backLabel = Loc.Format("Player_SkipBackSeconds", back);
        var forwardLabel = Loc.Format("Player_SkipForwardSeconds", forward);
        ToolTipService.SetToolTip(SkipBackButton, backLabel);
        ToolTipService.SetToolTip(SkipForwardButton, forwardLabel);
        AutomationProperties.SetName(SkipBackButton, backLabel);
        AutomationProperties.SetName(SkipForwardButton, forwardLabel);
    }

    // ------------------------------------------------------------------
    // Speed (F.4): the panel sets the base rate on the 0.05 grid
    // ------------------------------------------------------------------

    public double BaseRate => _baseRate;

    public void SetBaseRate(double rate)
    {
        _baseRate = PlayerLogic.NormalizedRate(rate);
        if (_holdSide is null) _mediaPlayer?.SetRate((float)_baseRate);
        BaseRateChanged?.Invoke(this, _baseRate);
    }

    // ------------------------------------------------------------------
    // Volume and mute (W.2)
    // ------------------------------------------------------------------

    /// <summary>↑/↓ and the mouse wheel: 5 % steps. Any volume change unmutes.</summary>
    public void ChangeVolume(int steps)
    {
        var level = PlayerLogic.AdjustedLevel(_volume / 100.0, steps * PlayerLogic.LevelStep);
        SetVolume((int)Math.Round(level * 100));
    }

    private void SetVolume(int volume)
    {
        _volume = Math.Clamp(volume, 0, 100);
        _muted = false;
        ApplyAudioLevels();
        UpdateVolumeUi();
        ShowHud(Loc.Format("Hud_Volume", _volume), "ms-appx:///Assets/Icons/volume-high.svg");
    }

    public void ToggleMute()
    {
        _muted = !_muted;
        ApplyAudioLevels();
        UpdateVolumeUi();
        ShowHud(
            Loc.Get(_muted ? "Hud_Muted" : "Hud_Unmuted"),
            _muted ? "ms-appx:///Assets/Icons/volume-xmark.svg" : "ms-appx:///Assets/Icons/volume-high.svg");
    }

    private void MuteButton_Click(object sender, RoutedEventArgs e) => ToggleMute();

    private void VolumeSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_suppressVolumeSlider) return;
        var value = (int)Math.Round(e.NewValue);
        if (value == _volume && !_muted) return;
        SetVolume(value);
    }

    private void ApplyAudioLevels()
    {
        if (_mediaPlayer is not { } player) return;
        player.Volume = _volume;
        player.Mute = _muted;
    }

    private void UpdateVolumeUi()
    {
        _suppressVolumeSlider = true;
        VolumeSlider.Value = _volume;
        _suppressVolumeSlider = false;
        MuteIcon.UriSource = new Uri(_muted || _volume == 0
            ? "ms-appx:///Assets/Icons/volume-xmark.svg"
            : "ms-appx:///Assets/Icons/volume-high.svg");
        var label = Loc.Get(_muted ? "Player_Unmute" : "Player_Mute");
        ToolTipService.SetToolTip(MuteButton, label);
        AutomationProperties.SetName(MuteButton, label);
    }

    // ------------------------------------------------------------------
    // Timeline
    // ------------------------------------------------------------------

    private void TimelineSlider_PointerPressed(object sender, PointerRoutedEventArgs e)
    {
        _isSliderManipulating = true;
        RefreshSegmentPrompt();
    }

    private void TimelineSlider_PointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (!_isSliderManipulating) return;
        _isSliderManipulating = false;
        if (_mediaPlayer == null) return;

        _mediaPlayer.Position = (float)(TimelineSlider.Value / 100);
    }

    private void TimelineSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (!_isSliderManipulating || _mediaPlayer == null) return;
        var duration = Math.Max(0, _mediaPlayer.Length);
        var pos = TimeSpan.FromMilliseconds(e.NewValue * duration / 100);
        CurrentTimeText.Text = PlayerLogic.Timestamp(pos.TotalSeconds);
    }

    // ------------------------------------------------------------------
    // Window modes
    // ------------------------------------------------------------------

    private void PictureInPictureButton_Click(object sender, RoutedEventArgs e)
    {
        PictureInPictureRequested?.Invoke(this, new RoutedEventArgs());
    }

    /// <summary>Same button restores the full window while floating.</summary>
    public void SetPictureInPictureActive(bool active)
    {
        _isPictureInPicture = active;
        var label = Loc.Get(active ? "Player_ExitPictureInPicture" : "Player_PictureInPicture");
        ToolTipService.SetToolTip(PictureInPictureButton, label);
        AutomationProperties.SetName(PictureInPictureButton, label);
    }

    public void SetFullScreenActive(bool active)
    {
        FullScreenIcon.UriSource = new Uri(active ? "ms-appx:///Assets/Icons/compress.svg" : "ms-appx:///Assets/Icons/expand.svg");
        var label = Loc.Get(active ? "Player_ExitFullScreen" : "Player_FullScreen");
        ToolTipService.SetToolTip(FullScreenButton, label);
        AutomationProperties.SetName(FullScreenButton, label);
    }

    // ------------------------------------------------------------------
    // Audio output (3.18)
    // ------------------------------------------------------------------

    private void AudioOutputButton_Click(object sender, RoutedEventArgs e)
    {
        ShowControls();
        var flyout = new MenuFlyout { Placement = FlyoutPlacementMode.Top };

        var systemDefault = new ToggleMenuFlyoutItem
        {
            Text = Loc.Get("Player_AudioOutputDefault"),
            IsChecked = _audioDevice is null,
        };
        systemDefault.Click += (_, _) => SelectAudioDevice(null);
        flyout.Items.Add(systemDefault);

        if (_mediaPlayer is { } player)
        {
            AudioOutputDevice[] devices;
            try
            {
                devices = player.AudioOutputDeviceEnum;
            }
            catch (VLCException)
            {
                devices = [];
            }

            if (devices.Length > 0) flyout.Items.Add(new MenuFlyoutSeparator());
            foreach (var device in devices)
            {
                if (string.IsNullOrEmpty(device.DeviceIdentifier)) continue;
                var id = device.DeviceIdentifier;
                var entry = new ToggleMenuFlyoutItem
                {
                    Text = string.IsNullOrWhiteSpace(device.Description) ? id : device.Description,
                    IsChecked = id == _audioDevice,
                };
                entry.Click += (_, _) => SelectAudioDevice(id);
                flyout.Items.Add(entry);
            }
        }

        flyout.ShowAt(AudioOutputButton);
    }

    /// <summary>Null follows Windows' default device again.</summary>
    private void SelectAudioDevice(string? deviceId)
    {
        _audioDevice = deviceId;
        ApplyAudioDevice();
    }

    private void ApplyAudioDevice()
    {
        if (_mediaPlayer is not { } player) return;
        try
        {
            player.SetOutputDevice(_audioDevice!);
        }
        catch (VLCException)
        {
            // An unplugged device leaves output where it was.
        }
    }

    // ------------------------------------------------------------------
    // Subtitles flyout: tracks in the file plus the online search. Audio
    // tracks moved to Player Adjustments (3.9).
    // ------------------------------------------------------------------

    private void SubtitlesButton_Click(object sender, RoutedEventArgs e)
    {
        ShowControls();
        var flyout = new MenuFlyout { Placement = FlyoutPlacementMode.Top };

        if (_mediaPlayer is not { } player)
        {
            flyout.Items.Add(new MenuFlyoutItem { Text = Loc.Get("Player_NoTracks"), IsEnabled = false });
            AddOnlineSearchItem(flyout);
            flyout.ShowAt(SubtitlesButton);
            return;
        }

        flyout.Items.Add(new MenuFlyoutItem { Text = Loc.Get("Player_SubtitlesHeader"), IsEnabled = false });
        var subtitleTracks = PlayerEffects.Tracks(player, _context).Subtitles;

        var off = new ToggleMenuFlyoutItem
        {
            Text = Loc.Get("Player_SubtitlesOff"),
            IsChecked = player.Spu < 0,
        };
        off.Click += (_, _) => player.SetSpu(-1);
        flyout.Items.Add(off);

        var saved = SavedSubtitlesOn(subtitleTracks);
        for (var index = 0; index < subtitleTracks.Count; index++)
        {
            var track = subtitleTracks[index];
            var trackId = track.Id;
            var savedSubtitle = saved.GetValueOrDefault(trackId);
            var entry = new ToggleMenuFlyoutItem
            {
                Text = savedSubtitle is null
                    ? TrackLabels.BaseLabel(track, index)
                    : SavedSubtitleRules.Label(savedSubtitle, Loc.Get("Subtitles_Saved"),
                        includeRelease: saved.Values.Count(other => other.Language == savedSubtitle.Language) > 1),
                IsChecked = player.Spu == trackId,
            };
            entry.Click += (_, _) =>
            {
                player.SetSpu(trackId);
                // Turning a saved subtitle on uses it: its 30 days start again.
                if (_context is { } context && context.ExternalSubtitleTracks.TryGetValue(trackId, out var uri))
                {
                    AppServices.SavedSubtitles.MarkUsed(context.Request, uri);
                }
            };
            flyout.Items.Add(entry);
        }

        if (subtitleTracks.Count == 0)
        {
            flyout.Items.Add(new MenuFlyoutItem { Text = Loc.Get("Player_NoTracksInFile"), IsEnabled = false });
        }

        AddOnlineSearchItem(flyout);
        flyout.ShowAt(SubtitlesButton);
    }

    /// <summary>Track id → saved subtitle, for the attached tracks that are saved downloads.</summary>
    private Dictionary<int, SavedSubtitle> SavedSubtitlesOn(IReadOnlyList<PlayerTrack> tracks)
    {
        var saved = new Dictionary<int, SavedSubtitle>();
        if (_context is not { } context) return saved;
        foreach (var track in tracks)
        {
            if (!track.IsExternal || !context.ExternalSubtitleTracks.TryGetValue(track.Id, out var uri)) continue;
            if (AppServices.SavedSubtitles.Find(context.Request, uri) is { } subtitle) saved[track.Id] = subtitle;
        }
        return saved;
    }

    /// <summary>
    /// Offers the online subtitle browser, but only on a build that carries an
    /// API key — otherwise the entry would lead nowhere.
    /// </summary>
    private void AddOnlineSearchItem(MenuFlyout flyout)
    {
        if (!AppServices.Subtitles.IsConfigured) return;

        flyout.Items.Add(new MenuFlyoutSeparator());
        var online = new MenuFlyoutItem { Text = Loc.Get("Subtitles_SearchOnline") };
        online.Click += (_, _) => OpenSubtitleBrowser();
        flyout.Items.Add(online);
    }

    private void MediaPlayer_StateChanged(object? sender, EventArgs args)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(sender, _mediaPlayer)) return;
            UpdatePlayPauseIcon();
            if (_mediaPlayer?.IsPlaying == true)
            {
                if (!_audioApplied)
                {
                    // LibVLC creates its audio output only once playback
                    // starts, so the session's level and device go on here.
                    _audioApplied = true;
                    ApplyAudioLevels();
                    if (_audioDevice is not null) ApplyAudioDevice();
                }
                _hideTimer.Start();
            }
            else
            {
                ShowControls();
            }
        });
    }

    private void MediaPlayer_LengthChanged(object? sender, EventArgs args)
    {
        DispatcherQueue.TryEnqueue(UpdateDuration);
    }

    private void UpdatePlayPauseIcon()
    {
        if (_mediaPlayer == null) return;
        var isPlaying = _mediaPlayer.IsPlaying;
        var iconUri = isPlaying ? "ms-appx:///Assets/Icons/pause.svg" : "ms-appx:///Assets/Icons/play.svg";
        CenterPlayPauseIcon.UriSource = new Uri(iconUri);
        BottomPlayPauseIcon.UriSource = new Uri(iconUri);
        UpdatePlayPauseLabels(isPlaying);
    }

    private void UpdatePlayPauseLabels(bool isPlaying)
    {
        var label = Loc.Get(isPlaying ? "Player_Pause" : "Player_Play");
        foreach (var button in new[] { CenterPlayPauseButton, BottomPlayPauseButton })
        {
            ToolTipService.SetToolTip(button, label);
            AutomationProperties.SetName(button, label);
        }
    }

    private void UpdateDuration()
    {
        if (_mediaPlayer == null) return;
        TotalTimeText.Text = PlayerLogic.Timestamp(Math.Max(0, _mediaPlayer.Length) / 1000.0);
        RefreshChapterMarks();
    }

    // ------------------------------------------------------------------
    // Chapter marks (X.7)
    // ------------------------------------------------------------------

    /// <summary>Half the slider thumb's width: its centre travels from here to the far end less this.</summary>
    private const double TimelineThumbInset = 9;
    private const double ChapterMarkHeight = 6;
    private IReadOnlyList<double> _chapterMarks = [];

    /// <summary>Reads the file's chapters again; called once its duration or tracks are known.</summary>
    public void RefreshChapterMarks()
    {
        IReadOnlyList<double> marks = [];
        if (_mediaPlayer is { } player)
        {
            try
            {
                marks = PlayerLogic.ChapterMarks(player.FullChapterDescriptions().Select(chapter => chapter.TimeOffset), player.Length);
            }
            catch (VLCException)
            {
                // A file without chapters draws no marks.
            }
        }
        if (marks.SequenceEqual(_chapterMarks)) return;
        _chapterMarks = marks;
        DrawChapterMarks();
    }

    private void ChapterMarksCanvas_SizeChanged(object sender, SizeChangedEventArgs e) => DrawChapterMarks();

    /// <summary>Each chapter start cuts a narrow ink gap into the track.</summary>
    private void DrawChapterMarks()
    {
        ChapterMarksCanvas.Children.Clear();
        var track = ChapterMarksCanvas.ActualWidth - 2 * TimelineThumbInset;
        if (track <= 0) return;
        var brush = (Brush)Application.Current.Resources["EdendaleBackgroundBrush"];
        foreach (var mark in _chapterMarks)
        {
            var tick = new Microsoft.UI.Xaml.Shapes.Rectangle { Width = 2, Height = ChapterMarkHeight, Fill = brush };
            Canvas.SetLeft(tick, TimelineThumbInset + mark * track - 1);
            Canvas.SetTop(tick, (ChapterMarksCanvas.ActualHeight - ChapterMarkHeight) / 2);
            ChapterMarksCanvas.Children.Add(tick);
        }
    }

    private void UpdateProgress()
    {
        if (_mediaPlayer == null) return;
        var duration = _mediaPlayer.Length;
        var position = Math.Max(0, _mediaPlayer.Time);
        if (!_isSliderManipulating)
        {
            if (duration > 0)
            {
                TimelineSlider.Value = (double)position / duration * 100;
            }
            CurrentTimeText.Text = PlayerLogic.Timestamp(position / 1000.0);
        }

        UpdateUpNext(position, duration);
        UpdateSegmentPrompt(position, duration);
    }

    // ------------------------------------------------------------------
    // Online subtitles (Wyzie Subs)
    //
    // Nothing here runs until the reader opens the browser: the search is an
    // explicit action, like trailer playback. Only the item's TMDB id and the
    // wanted languages leave the device — never the file or its name.
    // ------------------------------------------------------------------

    private void OpenSubtitleBrowser()
    {
        BuildLanguageBox();

        SubtitleBrowserSubject.Text = _playback is null
            ? Loc.Get("Subtitles_ThisFile")
            : string.Join(" · ", new[] { _playback.Title, _playback.Subtitle }
                .Where(part => !string.IsNullOrWhiteSpace(part)));

        SubtitleBrowser.Visibility = Visibility.Visible;
        ShowControls();

        if (SubtitleResultsPanel.Children.Count == 0) _ = RunSearchAsync();
    }

    private void CloseSubtitleBrowser()
    {
        _subtitleWork?.Cancel();
        _subtitleWork = null;

        SubtitleBrowser.Visibility = Visibility.Collapsed;
        SubtitleResultsPanel.Children.Clear();
    }

    private void SubtitleBrowserClose_Click(object sender, RoutedEventArgs e) => CloseSubtitleBrowser();

    private void SubtitleRefresh_Click(object sender, RoutedEventArgs e) => _ = RunSearchAsync();

    private void SubtitleLanguageBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        // Populating the box raises this too; ignore it until the reader owns
        // the selection.
        if (!_languageBoxReady) return;

        _subtitleLanguages = (SubtitleLanguageBox.SelectedItem as ComboBoxItem)?.Tag as IReadOnlyList<string>;
        _ = RunSearchAsync();
    }

    /// <summary>
    /// Preferred languages first, then every offered language by its own name
    /// in the reader's language — so the filter needs no translated copy.
    /// </summary>
    private void BuildLanguageBox()
    {
        if (SubtitleLanguageBox.Items.Count > 0) return;

        _languageBoxReady = false;
        AutomationProperties.SetName(SubtitleLanguageBox, Loc.Get("Subtitles_Language"));

        SubtitleLanguageBox.Items.Add(new ComboBoxItem
        {
            Content = Loc.Get("Subtitles_PreferredLanguages"),
            Tag = null,
        });

        foreach (var code in SubtitleLanguages.Offered
            .OrderBy(SubtitleLanguages.DisplayName, StringComparer.CurrentCulture))
        {
            SubtitleLanguageBox.Items.Add(new ComboBoxItem
            {
                Content = SubtitleLanguages.DisplayName(code),
                Tag = (IReadOnlyList<string>)new[] { code },
            });
        }

        SubtitleLanguageBox.SelectedIndex = 0;
        _subtitleLanguages = null;
        _languageBoxReady = true;
    }

    private async Task RunSearchAsync()
    {
        _subtitleWork?.Cancel();
        var work = new CancellationTokenSource();
        _subtitleWork = work;

        SubtitleResultsPanel.Children.Clear();
        ShowBrowserState(Loc.Get("Subtitles_Searching"), busy: true);

        try
        {
            if (_playback is null)
            {
                ShowBrowserState(Loc.Get("Subtitles_NoFile"), busy: false);
                return;
            }

            // Wyzie looks items up by id, so a file the library never matched
            // to TMDB has nothing to search by. Say so plainly rather than
            // returning an empty list that reads like "none exist".
            if (!SubtitleService.CanSearch(_playback))
            {
                ShowBrowserState(Loc.Get("Subtitles_NotMatched"), busy: false);
                return;
            }

            var results = await AppServices.Subtitles.SearchAsync(
                _playback, _subtitleLanguages, work.Token);

            if (work.IsCancellationRequested) return;

            if (results.Count == 0)
            {
                ShowBrowserState(Loc.Get("Subtitles_NoResults"), busy: false);
                return;
            }

            RenderResults(results);
            ShowBrowserState(null, busy: false);
        }
        catch (OperationCanceledException)
        {
            // Superseded by a newer search or by the panel closing.
        }
        catch (SubtitleServiceException error)
        {
            if (!work.IsCancellationRequested) ShowBrowserState(error.Message, busy: false);
        }
        finally
        {
            if (ReferenceEquals(_subtitleWork, work)) _subtitleWork = null;
            work.Dispose();
        }
    }

    private void RenderResults(IReadOnlyList<SubtitleCandidate> results)
    {
        SubtitleResultsPanel.Children.Clear();
        foreach (var candidate in results)
        {
            SubtitleResultsPanel.Children.Add(CreateSubtitleRow(candidate));
        }
    }

    private UIElement CreateSubtitleRow(SubtitleCandidate candidate)
    {
        var button = new Button
        {
            Style = (Style)Application.Current.Resources["ArchiveGhostButtonStyle"],
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(12, 10, 12, 10),
            CornerRadius = new CornerRadius(12),
        };

        var lines = new StackPanel { Spacing = 2 };
        lines.Children.Add(new TextBlock
        {
            Text = candidate.Release ?? candidate.FileName ?? candidate.LanguageLabel,
            Style = (Style)Application.Current.Resources["BodyLGTextStyle"],
            Foreground = (Brush)Application.Current.Resources["EdendaleTextPrimaryBrush"],
            TextWrapping = TextWrapping.NoWrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
        });
        lines.Children.Add(new TextBlock
        {
            Text = DescribeCandidate(candidate),
            Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            Foreground = (Brush)Application.Current.Resources["EdendaleTextSecondaryBrush"],
            TextWrapping = TextWrapping.NoWrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
        });

        button.Content = lines;
        AutomationProperties.SetName(button, $"{candidate.Release ?? ""} {DescribeCandidate(candidate)}".Trim());
        button.Click += (_, _) => _ = SelectCandidateAsync(candidate);
        return button;
    }

    /// <summary>
    /// The detail line: the language as the provider labels it, then the
    /// qualities worth choosing between, then where it came from.
    /// </summary>
    private string DescribeCandidate(SubtitleCandidate candidate)
    {
        var parts = new List<string> { candidate.LanguageLabel };
        // Already on this device for this title: choosing it needs no download.
        if (AppServices.SavedSubtitles.IsSaved(_playback, candidate.Id)) parts.Add(Loc.Get("Subtitles_Saved"));
        if (candidate.IsHearingImpaired) parts.Add(Loc.Get("Subtitles_HearingImpaired"));
        if (candidate.IsAiTranslated) parts.Add(Loc.Get("Subtitles_AutoTranslated"));
        if (!string.IsNullOrWhiteSpace(candidate.Origin)) parts.Add(candidate.Origin);
        if (candidate.DownloadCount > 0) parts.Add(Loc.Format("Subtitles_Downloads", candidate.DownloadCount));
        if (!string.IsNullOrWhiteSpace(candidate.Source)) parts.Add(candidate.Source);
        return string.Join(" · ", parts);
    }

    /// <summary>Downloads the chosen subtitle, attaches it, and turns it on.</summary>
    private async Task SelectCandidateAsync(SubtitleCandidate candidate)
    {
        _subtitleWork?.Cancel();
        var work = new CancellationTokenSource();
        _subtitleWork = work;

        ShowBrowserState(Loc.Get("Subtitles_Downloading"), busy: true);

        try
        {
            var downloaded = await AppServices.Subtitles.DownloadAsync(candidate, work.Token);
            if (work.IsCancellationRequested) return;

            if (_mediaPlayer is not { } player)
            {
                ShowBrowserState(Loc.Get("Subtitles_AttachFailed"), busy: false);
                return;
            }

            // A file attached already (saved for this title, or chosen
            // earlier) is turned on rather than added a second time.
            var uri = new Uri(downloaded.FilePath).AbsoluteUri;
            bool attached;
            if (_context?.TrackFor(uri) is int existing)
            {
                attached = player.SetSpu(existing);
            }
            else
            {
                attached = AttachSubtitle(player, downloaded);
            }
            if (work.IsCancellationRequested) return;

            if (!attached)
            {
                ShowBrowserState(Loc.Get("Subtitles_AttachFailed"), busy: false);
                return;
            }

            // Kept for this title, so its next playback has it without a search.
            if (_playback is not null) AppServices.SavedSubtitles.Record(_playback, downloaded);
            CloseSubtitleBrowser();
        }
        catch (OperationCanceledException)
        {
            // Superseded.
        }
        catch (SubtitleServiceException error)
        {
            if (!work.IsCancellationRequested) ShowBrowserState(error.Message, busy: false);
        }
        catch (IOException)
        {
            if (!work.IsCancellationRequested) ShowBrowserState(Loc.Get("Subtitles_AttachFailed"), busy: false);
        }
        finally
        {
            if (ReferenceEquals(_subtitleWork, work)) _subtitleWork = null;
            work.Dispose();
        }
    }

    /// <summary>
    /// Adds the downloaded file as LibVLC's selected subtitle slave. The
    /// context records it, so it is reattached after a reopen and never
    /// stored in the per-title memory.
    /// </summary>
    private bool AttachSubtitle(MediaPlayer player, DownloadedSubtitle downloaded)
    {
        var uri = new Uri(downloaded.FilePath).AbsoluteUri;
        _context?.ExternalSubtitleAttaching(uri);
        return player.AddSlave(MediaSlaveType.Subtitle, uri, select: true);
    }

    /// <summary>
    /// Busy shows the ring, a message replaces the list, and null restores the
    /// results.
    /// </summary>
    private void ShowBrowserState(string? message, bool busy)
    {
        var hasState = busy || message is not null;
        SubtitleStatePanel.Visibility = hasState ? Visibility.Visible : Visibility.Collapsed;
        SubtitleResultsScroller.Visibility = hasState ? Visibility.Collapsed : Visibility.Visible;

        SubtitleProgressRing.IsActive = busy;
        SubtitleProgressRing.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;

        SubtitleStateText.Text = message ?? "";
        SubtitleStateText.Visibility = message is null ? Visibility.Collapsed : Visibility.Visible;
        SubtitleRefreshButton.IsEnabled = !busy;
    }
}
