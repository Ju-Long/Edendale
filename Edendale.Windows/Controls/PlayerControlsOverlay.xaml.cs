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
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Edendale.Windows.Core;
using Edendale.Windows.Services;

namespace Edendale.Windows.Controls;

public sealed partial class PlayerControlsOverlay : UserControl
{
    private MediaPlayer? _mediaPlayer;
    private DispatcherQueueTimer _hideTimer;
    private DispatcherQueueTimer _progressTimer;
    private bool _isSliderManipulating;
    private bool _aspectFill;

    /// <summary>What is playing, for the online subtitle search. Null for a bare file.</summary>
    private PlaybackRequest? _playback;

    private CancellationTokenSource? _subtitleWork;

    /// <summary>Null means "search the reader's preferred languages".</summary>
    private IReadOnlyList<string>? _subtitleLanguages;

    private bool _languageBoxReady;

    public event RoutedEventHandler? CloseRequested;
    public event RoutedEventHandler? PlaylistRequested;
    public event RoutedEventHandler? PictureInPictureRequested;

    /// <summary>True when the frame should be cropped to fill the window.</summary>
    public event EventHandler<bool>? AspectFillChanged;

    public PlayerControlsOverlay()
    {
        this.InitializeComponent();

        // The XAML default is design-time only; the reader's decimal mark wins.
        SpeedButton.Content = RateLabel(1.0);

        _hideTimer = DispatcherQueue.CreateTimer();
        _hideTimer.Interval = TimeSpan.FromSeconds(3);
        _hideTimer.Tick += (s, e) => HideControls();

        _progressTimer = DispatcherQueue.CreateTimer();
        _progressTimer.Interval = TimeSpan.FromMilliseconds(250);
        _progressTimer.Tick += (s, e) => UpdateProgress();

        TimelineSlider.AddHandler(PointerPressedEvent, new PointerEventHandler(TimelineSlider_PointerPressed), true);
        TimelineSlider.AddHandler(PointerReleasedEvent, new PointerEventHandler(TimelineSlider_PointerReleased), true);
        TimelineSlider.AddHandler(PointerCanceledEvent, new PointerEventHandler(TimelineSlider_PointerReleased), true);
        TimelineSlider.AddHandler(PointerCaptureLostEvent, new PointerEventHandler(TimelineSlider_PointerReleased), true);
    }

    /// <summary>
    /// Binds the overlay to a player. <paramref name="request"/> is what the
    /// online subtitle search matches on; without it the browser still opens
    /// but can only search by the file's own hash.
    /// </summary>
    public void SetMediaPlayer(
        MediaPlayer? player, string title, string? subtitle, PlaybackRequest? request = null)
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

        // A different item invalidates any in-flight search and its results.
        CloseSubtitleBrowser();
        _playback = request;

        _mediaPlayer = player;
        TitleText.Text = title;
        SubtitleText.Text = subtitle ?? "";
        SubtitleText.Visibility = string.IsNullOrEmpty(subtitle) ? Visibility.Collapsed : Visibility.Visible;

        if (_mediaPlayer != null)
        {
            _mediaPlayer.Playing += MediaPlayer_StateChanged;
            _mediaPlayer.Paused += MediaPlayer_StateChanged;
            _mediaPlayer.Stopped += MediaPlayer_StateChanged;
            _mediaPlayer.EndReached += MediaPlayer_StateChanged;
            _mediaPlayer.EncounteredError += MediaPlayer_StateChanged;
            _mediaPlayer.LengthChanged += MediaPlayer_LengthChanged;

            UpdatePlayPauseIcon();
            UpdateDuration();
            UpdateProgress();

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

        if (_mediaPlayer.IsPlaying)
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
        var newPosition = _mediaPlayer.Time + (long)(seconds * 1000);
        newPosition = Math.Max(0, Math.Min(newPosition, _mediaPlayer.Length));
        _mediaPlayer.Time = newPosition;
        ShowControls();
    }

    private void SkipBack_Click(object sender, RoutedEventArgs e) => Skip(-10);
    private void SkipForward_Click(object sender, RoutedEventArgs e) => Skip(10);

    private void TimelineSlider_PointerPressed(object sender, PointerRoutedEventArgs e)
    {
        _isSliderManipulating = true;
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
        CurrentTimeText.Text = FormatTime(pos);
    }

    private void SpeedButton_Click(object sender, RoutedEventArgs e)
    {
        if (_mediaPlayer == null) return;
        var currentRate = (double)_mediaPlayer.Rate;
        double nextRate = currentRate switch
        {
            1.0 => 1.25,
            1.25 => 1.5,
            1.5 => 2.0,
            2.0 => 0.5,
            _ => 1.0
        };
        _mediaPlayer.SetRate((float)nextRate);
        SpeedButton.Content = RateLabel(nextRate);
    }

    /// <summary>Playback rate with the reader's decimal mark — "1,5x" in German.</summary>
    private static string RateLabel(double rate) =>
        string.Format(CultureInfo.CurrentCulture, "{0:0.0}x", rate);

    private void PictureInPictureButton_Click(object sender, RoutedEventArgs e)
    {
        PictureInPictureRequested?.Invoke(this, new RoutedEventArgs());
    }

    /// <summary>Same button restores the full window while floating.</summary>
    public void SetPictureInPictureActive(bool active)
    {
        ToolTipService.SetToolTip(
            PictureInPictureButton,
            Loc.Get(active ? "Player_ExitPictureInPicture" : "Player_PictureInPicture"));
    }

    /// <summary>
    /// Fit letterboxes the whole frame; fill crops it to the window. The
    /// element lives in the shell, so the choice is raised rather than applied.
    /// </summary>
    private void AspectButton_Click(object sender, RoutedEventArgs e)
    {
        _aspectFill = !_aspectFill;
        AspectButton.Content = Loc.Get(_aspectFill ? "Player_AspectFill" : "Player_AspectFit");
        AspectFillChanged?.Invoke(this, _aspectFill);
        ShowControls();
    }

    /// <summary>Audio and subtitle tracks discovered by LibVLC.</summary>
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

        // LibVLC includes a synthetic "disable" entry on some outputs. The
        // Edendale menu presents playable audio streams only.
        var audioTracks = player.AudioTrackDescription
            .Where(track => track.Id >= 0)
            .ToArray();
        if (audioTracks.Length > 1)
        {
            flyout.Items.Add(new MenuFlyoutItem { Text = Loc.Get("Player_AudioHeader"), IsEnabled = false });
            for (var index = 0; index < audioTracks.Length; index++)
            {
                var track = audioTracks[index];
                var trackId = track.Id;
                var entry = new ToggleMenuFlyoutItem
                {
                    Text = TrackLabel(track.Name, null, index, Loc.Get("Player_AudioTrack")),
                    IsChecked = player.AudioTrack == trackId,
                };
                entry.Click += (_, _) => player.SetAudioTrack(trackId);
                flyout.Items.Add(entry);
            }
            flyout.Items.Add(new MenuFlyoutSeparator());
        }

        flyout.Items.Add(new MenuFlyoutItem { Text = Loc.Get("Player_SubtitlesHeader"), IsEnabled = false });
        var subtitleTracks = player.SpuDescription
            .Where(track => track.Id >= 0)
            .ToArray();

        var off = new ToggleMenuFlyoutItem
        {
            Text = Loc.Get("Player_SubtitlesOff"),
            IsChecked = player.Spu < 0,
        };
        off.Click += (_, _) => player.SetSpu(-1);
        flyout.Items.Add(off);

        for (var index = 0; index < subtitleTracks.Length; index++)
        {
            var track = subtitleTracks[index];
            var trackId = track.Id;
            var entry = new ToggleMenuFlyoutItem
            {
                Text = TrackLabel(track.Name, null, index, Loc.Get("Player_SubtitleTrack")),
                IsChecked = player.Spu == trackId,
            };
            entry.Click += (_, _) => player.SetSpu(trackId);
            flyout.Items.Add(entry);
        }

        if (subtitleTracks.Length == 0)
        {
            flyout.Items.Add(new MenuFlyoutItem { Text = Loc.Get("Player_NoTracksInFile"), IsEnabled = false });
        }

        AddOnlineSearchItem(flyout);
        flyout.ShowAt(SubtitlesButton);
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

    private static string TrackLabel(string? label, string? language, int index, string fallback)
    {
        if (!string.IsNullOrWhiteSpace(label)) return label;
        if (!string.IsNullOrWhiteSpace(language)) return language;
        return $"{fallback} {index + 1}";
    }

    private void MediaPlayer_StateChanged(object? sender, EventArgs args)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            UpdatePlayPauseIcon();
            if (_mediaPlayer?.IsPlaying == true)
            {
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
    }

    private void UpdateDuration()
    {
        if (_mediaPlayer == null) return;
        TotalTimeText.Text = FormatTime(TimeSpan.FromMilliseconds(Math.Max(0, _mediaPlayer.Length)));
    }

    private void UpdateProgress()
    {
        if (_mediaPlayer == null || _isSliderManipulating) return;
        var duration = _mediaPlayer.Length;
        var position = Math.Max(0, _mediaPlayer.Time);
        if (duration > 0)
        {
            TimelineSlider.Value = (double)position / duration * 100;
        }
        CurrentTimeText.Text = FormatTime(TimeSpan.FromMilliseconds(position));
    }

    private string FormatTime(TimeSpan time)
    {
        if (time.TotalHours >= 1)
        {
            return $"{(int)time.TotalHours}:{time.Minutes:D2}:{time.Seconds:D2}";
        }
        return $"{time.Minutes}:{time.Seconds:D2}";
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
    private static string DescribeCandidate(SubtitleCandidate candidate)
    {
        var parts = new List<string> { candidate.LanguageLabel };
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

            var attached = AttachSubtitle(player, downloaded);
            if (work.IsCancellationRequested) return;

            if (!attached)
            {
                ShowBrowserState(Loc.Get("Subtitles_AttachFailed"), busy: false);
                return;
            }

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

    /// <summary>Adds the downloaded file as LibVLC's selected subtitle slave.</summary>
    private static bool AttachSubtitle(MediaPlayer player, DownloadedSubtitle downloaded) =>
        player.AddSlave(
            MediaSlaveType.Subtitle,
            new Uri(downloaded.FilePath).AbsoluteUri,
            select: true);

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
