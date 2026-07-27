using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Windows.Media.Core;
using Windows.Media.Playback;
using Microsoft.UI.Dispatching;
using System.ComponentModel;
using Microsoft.UI.Xaml.Media.Imaging;

namespace Edendale.Windows.Controls;

public sealed partial class PlayerControlsOverlay : UserControl
{
    private MediaPlayer? _mediaPlayer;
    private DispatcherQueueTimer _hideTimer;
    private DispatcherQueueTimer _progressTimer;
    private bool _isSliderManipulating;
    private bool _aspectFill;

    public event RoutedEventHandler? CloseRequested;
    public event RoutedEventHandler? PlaylistRequested;
    public event RoutedEventHandler? PictureInPictureRequested;

    /// <summary>True when the frame should be cropped to fill the window.</summary>
    public event EventHandler<bool>? AspectFillChanged;

    public PlayerControlsOverlay()
    {
        this.InitializeComponent();

        _hideTimer = DispatcherQueue.CreateTimer();
        _hideTimer.Interval = TimeSpan.FromSeconds(3);
        _hideTimer.Tick += (s, e) => HideControls();

        _progressTimer = DispatcherQueue.CreateTimer();
        _progressTimer.Interval = TimeSpan.FromMilliseconds(250);
        _progressTimer.Tick += (s, e) => UpdateProgress();
    }

    public void SetMediaPlayer(MediaPlayer? player, string title, string? subtitle)
    {
        if (_mediaPlayer != null)
        {
            _mediaPlayer.PlaybackSession.PlaybackStateChanged -= PlaybackSession_PlaybackStateChanged;
            _mediaPlayer.PlaybackSession.PositionChanged -= PlaybackSession_PositionChanged;
            _mediaPlayer.PlaybackSession.NaturalDurationChanged -= PlaybackSession_NaturalDurationChanged;
        }

        _mediaPlayer = player;
        TitleText.Text = title;
        SubtitleText.Text = subtitle ?? "";
        SubtitleText.Visibility = string.IsNullOrEmpty(subtitle) ? Visibility.Collapsed : Visibility.Visible;

        if (_mediaPlayer != null)
        {
            _mediaPlayer.PlaybackSession.PlaybackStateChanged += PlaybackSession_PlaybackStateChanged;
            _mediaPlayer.PlaybackSession.PositionChanged += PlaybackSession_PositionChanged;
            _mediaPlayer.PlaybackSession.NaturalDurationChanged += PlaybackSession_NaturalDurationChanged;

            UpdatePlayPauseIcon();
            UpdateDuration();
            UpdateProgress();
            UpdateLoopIcon();

            _progressTimer.Start();
            ShowControls();
        }
        else
        {
            _progressTimer.Stop();
        }
    }

    private void HideControls()
    {
        if (_mediaPlayer?.PlaybackSession.PlaybackState == MediaPlaybackState.Playing)
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

    private void UserControl_PointerMoved(object sender, PointerRoutedEventArgs e)
    {
        ShowControls();
    }

    private void UserControl_PointerExited(object sender, PointerRoutedEventArgs e)
    {
        HideControls();
    }

    private void UserControl_Tapped(object sender, TappedRoutedEventArgs e)
    {
        ShowControls();
    }

    private void BackButton_Click(object sender, RoutedEventArgs e)
    {
        CloseRequested?.Invoke(this, new RoutedEventArgs());
    }

    private void SidebarButton_Click(object sender, RoutedEventArgs e)
    {
        PlaylistRequested?.Invoke(this, new RoutedEventArgs());
    }

    private void PlayPauseButton_Click(object sender, RoutedEventArgs e)
    {
        TogglePlayPause();
    }

    public void TogglePlayPause()
    {
        if (_mediaPlayer == null) return;

        if (_mediaPlayer.PlaybackSession.PlaybackState == MediaPlaybackState.Playing)
        {
            _mediaPlayer.Pause();
            ShowControls();
        }
        else
        {
            _mediaPlayer.Play();
            ShowControls();
        }
    }

    public void Skip(double seconds)
    {
        if (_mediaPlayer == null) return;
        var newPosition = _mediaPlayer.PlaybackSession.Position.TotalSeconds + seconds;
        var duration = _mediaPlayer.PlaybackSession.NaturalDuration.TotalSeconds;
        newPosition = Math.Max(0, Math.Min(newPosition, duration));
        _mediaPlayer.PlaybackSession.Position = TimeSpan.FromSeconds(newPosition);
        ShowControls();
    }

    private void SkipBack_Click(object sender, RoutedEventArgs e) => Skip(-10);
    private void SkipForward_Click(object sender, RoutedEventArgs e) => Skip(10);

    private void TimelineSlider_ManipulationStarted(object sender, ManipulationStartedRoutedEventArgs e)
    {
        _isSliderManipulating = true;
    }

    private void TimelineSlider_ManipulationCompleted(object sender, ManipulationCompletedRoutedEventArgs e)
    {
        _isSliderManipulating = false;
        if (_mediaPlayer == null) return;

        var duration = _mediaPlayer.PlaybackSession.NaturalDuration.TotalSeconds;
        _mediaPlayer.PlaybackSession.Position = TimeSpan.FromSeconds(TimelineSlider.Value * duration / 100);
    }

    private void TimelineSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (!_isSliderManipulating || _mediaPlayer == null) return;
        var duration = _mediaPlayer.PlaybackSession.NaturalDuration.TotalSeconds;
        var pos = TimeSpan.FromSeconds(e.NewValue * duration / 100);
        CurrentTimeText.Text = FormatTime(pos);
    }

    private void SpeedButton_Click(object sender, RoutedEventArgs e)
    {
        if (_mediaPlayer == null) return;
        var currentRate = _mediaPlayer.PlaybackSession.PlaybackRate;
        double nextRate = currentRate switch
        {
            1.0 => 1.25,
            1.25 => 1.5,
            1.5 => 2.0,
            2.0 => 0.5,
            _ => 1.0
        };
        _mediaPlayer.PlaybackSession.PlaybackRate = nextRate;
        SpeedButton.Content = $"{nextRate:0.0}x";
    }

    private void PictureInPictureButton_Click(object sender, RoutedEventArgs e)
    {
        PictureInPictureRequested?.Invoke(this, new RoutedEventArgs());
    }

    /// <summary>Same button restores the full window while floating.</summary>
    public void SetPictureInPictureActive(bool active)
    {
        ToolTipService.SetToolTip(
            PictureInPictureButton,
            active ? "Exit Picture in Picture" : "Picture in Picture");
    }

    private void LoopButton_Click(object sender, RoutedEventArgs e)
    {
        if (_mediaPlayer == null) return;
        _mediaPlayer.IsLoopingEnabled = !_mediaPlayer.IsLoopingEnabled;
        UpdateLoopIcon();
        ShowControls();
    }

    private void UpdateLoopIcon()
    {
        var looping = _mediaPlayer?.IsLoopingEnabled == true;
        LoopIcon.Foreground = looping
            ? (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EdendaleGoldBrush"]
            : (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EdendaleTextPrimaryBrush"];
        ToolTipService.SetToolTip(LoopButton, looping ? "Stop looping" : "Loop");
    }

    /// <summary>
    /// Fit letterboxes the whole frame; fill crops it to the window. The
    /// element lives in the shell, so the choice is raised rather than applied.
    /// </summary>
    private void AspectButton_Click(object sender, RoutedEventArgs e)
    {
        _aspectFill = !_aspectFill;
        AspectButton.Content = _aspectFill ? "FILL" : "FIT";
        AspectFillChanged?.Invoke(this, _aspectFill);
        ShowControls();
    }

    /// <summary>
    /// Audio and subtitle tracks of the playing item. Requires the source to be
    /// a <see cref="MediaPlaybackItem"/> — a bare MediaSource exposes no track
    /// lists — which is how MainWindow builds it.
    /// </summary>
    private void SubtitlesButton_Click(object sender, RoutedEventArgs e)
    {
        ShowControls();
        var flyout = new MenuFlyout { Placement = FlyoutPlacementMode.Top };

        if (_mediaPlayer?.Source is not MediaPlaybackItem item)
        {
            flyout.Items.Add(new MenuFlyoutItem { Text = "No tracks available", IsEnabled = false });
            flyout.ShowAt(SubtitlesButton);
            return;
        }

        if (item.AudioTracks.Count > 1)
        {
            flyout.Items.Add(new MenuFlyoutItem { Text = "AUDIO", IsEnabled = false });
            for (var index = 0; index < item.AudioTracks.Count; index++)
            {
                var trackIndex = index;
                var track = item.AudioTracks[index];
                var entry = new ToggleMenuFlyoutItem
                {
                    Text = TrackLabel(track.Label, track.Language, index, "Audio"),
                    IsChecked = item.AudioTracks.SelectedIndex == index,
                };
                entry.Click += (_, _) => item.AudioTracks.SelectedIndex = trackIndex;
                flyout.Items.Add(entry);
            }
            flyout.Items.Add(new MenuFlyoutSeparator());
        }

        flyout.Items.Add(new MenuFlyoutItem { Text = "SUBTITLES", IsEnabled = false });
        var subtitleIndices = new List<int>();
        for (var index = 0; index < item.TimedMetadataTracks.Count; index++)
        {
            var kind = item.TimedMetadataTracks[index].TimedMetadataKind;
            if (kind is TimedMetadataKind.Subtitle or TimedMetadataKind.Caption) subtitleIndices.Add(index);
        }

        var anyShown = subtitleIndices.Any(index =>
            item.TimedMetadataTracks.GetPresentationMode((uint)index)
                != TimedMetadataTrackPresentationMode.Disabled);

        var off = new ToggleMenuFlyoutItem { Text = "Off", IsChecked = !anyShown };
        off.Click += (_, _) =>
        {
            foreach (var index in subtitleIndices)
            {
                item.TimedMetadataTracks.SetPresentationMode(
                    (uint)index, TimedMetadataTrackPresentationMode.Disabled);
            }
        };
        flyout.Items.Add(off);

        foreach (var index in subtitleIndices)
        {
            var trackIndex = index;
            var track = item.TimedMetadataTracks[index];
            var entry = new ToggleMenuFlyoutItem
            {
                Text = TrackLabel(track.Label, track.Language, index, "Subtitle"),
                IsChecked = item.TimedMetadataTracks.GetPresentationMode((uint)index)
                    != TimedMetadataTrackPresentationMode.Disabled,
            };
            entry.Click += (_, _) =>
            {
                // Exactly one subtitle track at a time, like the Apple player.
                foreach (var other in subtitleIndices)
                {
                    item.TimedMetadataTracks.SetPresentationMode(
                        (uint)other,
                        other == trackIndex
                            ? TimedMetadataTrackPresentationMode.PlatformPresented
                            : TimedMetadataTrackPresentationMode.Disabled);
                }
            };
            flyout.Items.Add(entry);
        }

        if (subtitleIndices.Count == 0)
        {
            flyout.Items.Add(new MenuFlyoutItem { Text = "This file carries none", IsEnabled = false });
        }

        flyout.ShowAt(SubtitlesButton);
    }

    private static string TrackLabel(string? label, string? language, int index, string fallback)
    {
        if (!string.IsNullOrWhiteSpace(label)) return label;
        if (!string.IsNullOrWhiteSpace(language)) return language;
        return $"{fallback} {index + 1}";
    }

    private void PlaybackSession_PlaybackStateChanged(MediaPlaybackSession sender, object args)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            UpdatePlayPauseIcon();
            if (sender.PlaybackState == MediaPlaybackState.Playing)
            {
                _hideTimer.Start();
            }
            else
            {
                ShowControls();
            }
        });
    }

    private void PlaybackSession_PositionChanged(MediaPlaybackSession sender, object args)
    {
        DispatcherQueue.TryEnqueue(UpdateProgress);
    }

    private void PlaybackSession_NaturalDurationChanged(MediaPlaybackSession sender, object args)
    {
        DispatcherQueue.TryEnqueue(UpdateDuration);
    }

    private void UpdatePlayPauseIcon()
    {
        if (_mediaPlayer == null) return;
        var isPlaying = _mediaPlayer.PlaybackSession.PlaybackState == MediaPlaybackState.Playing;
        var iconUri = isPlaying ? "ms-appx:///Assets/Icons/pause.svg" : "ms-appx:///Assets/Icons/play.svg";
        CenterPlayPauseIcon.UriSource = new Uri(iconUri);
        BottomPlayPauseIcon.UriSource = new Uri(iconUri);
    }

    private void UpdateDuration()
    {
        if (_mediaPlayer == null) return;
        TotalTimeText.Text = FormatTime(_mediaPlayer.PlaybackSession.NaturalDuration);
    }

    private void UpdateProgress()
    {
        if (_mediaPlayer == null || _isSliderManipulating) return;
        var duration = _mediaPlayer.PlaybackSession.NaturalDuration.TotalSeconds;
        var position = _mediaPlayer.PlaybackSession.Position;
        if (duration > 0)
        {
            TimelineSlider.Value = (position.TotalSeconds / duration) * 100;
        }
        CurrentTimeText.Text = FormatTime(position);
    }

    private string FormatTime(TimeSpan time)
    {
        if (time.TotalHours >= 1)
        {
            return $"{(int)time.TotalHours}:{time.Minutes:D2}:{time.Seconds:D2}";
        }
        return $"{time.Minutes}:{time.Seconds:D2}";
    }
}
