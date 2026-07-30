using Edendale.Windows.Pages;
using Edendale.Windows.Services;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace Edendale.Windows;

/// <summary>
/// The Edendale shell: custom title bar, fixed sidebar navigation over a
/// content frame, and the full-window player overlay. Mirrors the macOS
/// RootView arrangement (sidebar tabs, Settings pinned at the bottom).
/// </summary>
public sealed partial class MainWindow : Window
{
    private MediaPlayer? _mediaPlayer;
    private PlaybackRequest? _currentPlayback;
    private DispatcherQueueTimer? _progressTimer;
    private bool _isCompactOverlay;

    public MainWindow()
    {
        InitializeComponent();

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.Resize(new global::Windows.Graphics.SizeInt32(1440, 900));
        AppWindow.SetIcon("Assets\\icon.ico");

        MoviesNavItem.Icon = Controls.SvgIcon.CreateIcon("film");
        DownloadedNavItem.Icon = Controls.SvgIcon.CreateIcon("folder-closed");
        SearchNavItem.Icon = Controls.SvgIcon.CreateIcon("magnifying-glass-play");

        NavigationService.Frame = RootFrame;
        RootFrame.Navigate(typeof(MoviesShowsPage));

        AppServices.Player.PlaybackRequested += (_, request) =>
            DispatcherQueue.TryEnqueue(() => OpenPlayer(request));
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private bool _suppressNavSelection;

    private void Nav_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (_suppressNavSelection) return;
        if (args.IsSettingsSelected)
        {
            NavigateRoot(typeof(SettingsPage));
            return;
        }
        switch ((args.SelectedItem as NavigationViewItem)?.Tag as string)
        {
            case "movies": NavigateRoot(typeof(MoviesShowsPage)); break;
            case "downloaded": NavigateRoot(typeof(DownloadedPage)); break;
            case "search": NavigateRoot(typeof(SearchPage)); break;
        }
    }

    private void NavigateRoot(Type pageType)
    {
        if (RootFrame.CurrentSourcePageType != pageType)
        {
            RootFrame.Navigate(pageType);
            // Tab switches start fresh; only detail pushes stack up.
            RootFrame.BackStack.Clear();
        }
    }

    /// <summary>
    /// Shows the Search tab as a fresh root (sidebar selection included) —
    /// used by cast taps so a person's filmography never leaves a stray
    /// back-stack entry behind the detail page.
    /// </summary>
    public void ShowSearch(SearchNavArgs args)
    {
        SelectSidebar("search");
        RootFrame.Navigate(typeof(SearchPage), args);
        RootFrame.BackStack.Clear();
    }

    // ------------------------------------------------------------------
    // External routes (edendale:// — AppRouter.swift parity)
    // ------------------------------------------------------------------

    /// <summary>External entry points land here so they reuse the shell's navigation and player.</summary>
    public void OpenRoute(AppRoute route)
    {
        switch (route)
        {
            case AppRoute.Search search:
                ShowSearch(new SearchNavArgs(Query: search.Query));
                break;

            case AppRoute.Media media:
                NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(
                    Ref: new Models.MediaRef { Id = media.TmdbId, MediaType = media.MediaType }));
                break;

            case AppRoute.LocalMovie localMovie
                when AppServices.Library.Movies.FirstOrDefault(m => m.Id == localMovie.Id) is { } movie:
                NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(LocalMovieId: movie.Id));
                break;

            case AppRoute.LocalShow localShow
                when AppServices.Library.Shows.FirstOrDefault(s => s.Id == localShow.Id) is { } show:
                NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(LocalShowId: show.Id));
                break;

            case AppRoute.PlayMovie playMovie:
                if (AppServices.Library.MovieByTmdbId(playMovie.TmdbId) is { } libraryMovie)
                {
                    AppServices.Player.Play(libraryMovie);
                }
                else
                {
                    // No playable local file — open details instead of
                    // pretending playback succeeded (Apple Phase 12 rule).
                    NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(
                        Ref: new Models.MediaRef { Id = playMovie.TmdbId, MediaType = "movie" }));
                }
                break;

            case AppRoute.PlayEpisode playEpisode:
                if (AppServices.Library.EpisodeByTmdbId(playEpisode.TmdbId) is { } episode
                    && AppServices.Library.ShowForEpisode(episode) is { } episodeShow)
                {
                    AppServices.Player.Play(episodeShow, episode);
                }
                else
                {
                    ShowActivationMessage(Loc.Get("Player_NoLocalFile"));
                }
                break;

            case AppRoute.PlayLocalMovie playLocal
                when AppServices.Library.Movies.FirstOrDefault(m => m.Id == playLocal.Id) is { } localFile:
                AppServices.Player.Play(localFile);
                break;

            case AppRoute.PlayLocalEpisode playLocalEpisode:
                var match = AppServices.Library.Shows
                    .SelectMany(s => s.Episodes.Select(ep => (Show: s, Episode: ep)))
                    .FirstOrDefault(pair => pair.Episode.Id == playLocalEpisode.Id);
                if (match.Episode is not null)
                {
                    AppServices.Player.Play(match.Show, match.Episode);
                }
                else
                {
                    ShowActivationMessage(Loc.Get("Library_ItemMissing"));
                }
                break;

            default:
                ShowActivationMessage(Loc.Get("Library_ItemMissing"));
                break;
        }
    }

    private void SelectSidebar(string tag)
    {
        _suppressNavSelection = true;
        Nav.SelectedItem = Nav.MenuItems.OfType<NavigationViewItem>()
            .FirstOrDefault(item => item.Tag as string == tag);
        _suppressNavSelection = false;
    }

    public void ShowActivationMessage(string message)
    {
        ActivationBar.Message = message;
        ActivationBar.IsOpen = true;
    }

    // ------------------------------------------------------------------
    // Player lifecycle + watch-progress loop
    // ------------------------------------------------------------------

    private void OpenPlayer(PlaybackRequest request)
    {
        ClosePlayerCore();

        PlaylistPanel.Visibility = Visibility.Collapsed;

        _currentPlayback = request;

        // A MediaPlaybackItem rather than a bare MediaSource: only the item
        // exposes the audio and subtitle track lists the overlay's track menu
        // reads.
        _mediaPlayer = new MediaPlayer
        {
            Source = new MediaPlaybackItem(MediaSource.CreateFromUri(new Uri(request.FilePath))),
            AutoPlay = true,
        };
        _mediaPlayer.MediaOpened += (player, _) => DispatcherQueue.TryEnqueue(() => ResumeIfNeeded(player));
        _mediaPlayer.MediaEnded += (_, _) => DispatcherQueue.TryEnqueue(() =>
        {
            CompleteCurrent();
            ClosePlayer();
        });
        PlayerElement.SetMediaPlayer(_mediaPlayer);
        ControlsOverlay.SetMediaPlayer(
            _mediaPlayer, request.Title.ToUpperInvariant(), request.Subtitle, request);

        PlayerOverlay.Visibility = Visibility.Visible;
        ControlsOverlay.Focus(FocusState.Programmatic);

        _progressTimer = DispatcherQueue.CreateTimer();
        _progressTimer.Interval = TimeSpan.FromSeconds(5);
        _progressTimer.Tick += (_, _) => WriteProgress();
        _progressTimer.Start();
    }

    /// <summary>Resume from the stored position when half-watched (Apple parity).</summary>
    private void ResumeIfNeeded(MediaPlayer player)
    {
        if (_currentPlayback?.TmdbId is not int tmdbId) return;
        var progress = AppServices.WatchProgress.Get(tmdbId, _currentPlayback.MediaType);
        if (progress is null || progress.IsCompleted || progress.Position <= 0.005) return;

        var duration = player.PlaybackSession.NaturalDuration;
        if (duration.TotalSeconds > 0)
        {
            player.PlaybackSession.Position = TimeSpan.FromSeconds(duration.TotalSeconds * progress.Position);
        }
    }

    private void WriteProgress()
    {
        if (_mediaPlayer is null || _currentPlayback?.TmdbId is not int tmdbId) return;
        var session = _mediaPlayer.PlaybackSession;
        var duration = session.NaturalDuration.TotalSeconds;
        if (duration <= 0) return;

        AppServices.WatchProgress.Update(
            tmdbId,
            _currentPlayback.MediaType,
            session.Position.TotalSeconds / duration,
            session.Position.TotalSeconds,
            _currentPlayback.ShowTmdbId,
            _currentPlayback.SeasonNumber,
            _currentPlayback.EpisodeNumber);
    }

    private void CompleteCurrent()
    {
        if (_currentPlayback?.TmdbId is int tmdbId)
        {
            AppServices.WatchProgress.MarkCompleted(tmdbId, _currentPlayback.MediaType);
        }
    }

    private void ClosePlayer_Click(object sender, RoutedEventArgs e) => ClosePlayer();

    private void ControlsOverlay_PlaylistRequested(object sender, RoutedEventArgs e)
    {
        if (_currentPlayback == null) return;
        PlaylistPanel.Load(_currentPlayback);
        PlaylistPanel.Visibility = Visibility.Visible;
    }

    /// <summary>Fit letterboxes the frame; fill crops it to the window.</summary>
    private void ControlsOverlay_AspectFillChanged(object? sender, bool fill)
    {
        PlayerElement.Stretch = fill
            ? Microsoft.UI.Xaml.Media.Stretch.UniformToFill
            : Microsoft.UI.Xaml.Media.Stretch.Uniform;
    }

    private void PlaylistPanel_CloseRequested(object sender, RoutedEventArgs e)
    {
        PlaylistPanel.Visibility = Visibility.Collapsed;
    }

    private void PlaylistPanel_PlayRequested(object sender, PlaybackRequest e)
    {
        PlaylistPanel.Visibility = Visibility.Collapsed;
        OpenPlayer(e);
    }

    // ------------------------------------------------------------------
    // Picture in Picture (compact overlay)
    // ------------------------------------------------------------------

    private void ControlsOverlay_PictureInPictureRequested(object sender, RoutedEventArgs e)
        => SetCompactOverlay(!_isCompactOverlay);

    /// <summary>
    /// Windows' Picture in Picture: the shell window itself switches to the
    /// compact-overlay presenter — a small always-on-top window showing just
    /// the player. The auto-hiding controls stay (they are how the floating
    /// window is paused, restored, and closed, and they keep keyboard focus
    /// inside the player); the playlist panel goes, having no room. A double
    /// tap or Escape restores the full window.
    /// </summary>
    private void SetCompactOverlay(bool compact)
    {
        if (compact == _isCompactOverlay) return;
        if (compact && PlayerOverlay.Visibility != Visibility.Visible) return;

        try
        {
            if (compact)
            {
                var presenter = CompactOverlayPresenter.Create();
                presenter.InitialSize = CompactOverlaySize.Medium;
                AppWindow.SetPresenter(presenter);
            }
            else
            {
                AppWindow.SetPresenter(AppWindowPresenterKind.Default);
            }
        }
        catch (Exception)
        {
            // The compact-overlay presenter needs Windows 10 1903 or newer;
            // on anything older the player just stays full window.
            ShowActivationMessage(Loc.Get("Player_PipUnavailable"));
            return;
        }

        _isCompactOverlay = compact;
        PlaylistPanel.Visibility = Visibility.Collapsed;
        ControlsOverlay.SetPictureInPictureActive(compact);
        ControlsOverlay.Focus(FocusState.Programmatic);
    }

    private void PlayerOverlay_DoubleTapped(object sender, Microsoft.UI.Xaml.Input.DoubleTappedRoutedEventArgs e)
    {
        if (_isCompactOverlay) SetCompactOverlay(false);
    }

    private void PlayerOverlay_KeyDown(object sender, Microsoft.UI.Xaml.Input.KeyRoutedEventArgs e)
    {
        if (e.Key == global::Windows.System.VirtualKey.Escape)
        {
            // Escape leaves the floating window first, then closes the player.
            if (_isCompactOverlay)
            {
                SetCompactOverlay(false);
            }
            else
            {
                ClosePlayer();
            }
            e.Handled = true;
        }
        else if (e.Key == global::Windows.System.VirtualKey.Space)
        {
            ControlsOverlay.TogglePlayPause();
            e.Handled = true;
        }
        else if (e.Key == global::Windows.System.VirtualKey.Left)
        {
            ControlsOverlay.Skip(-10);
            e.Handled = true;
        }
        else if (e.Key == global::Windows.System.VirtualKey.Right)
        {
            ControlsOverlay.Skip(10);
            e.Handled = true;
        }
    }

    private void ClosePlayer()
    {
        WriteProgress();
        ClosePlayerCore();
        SetCompactOverlay(false);
        PlayerOverlay.Visibility = Visibility.Collapsed;
    }

    private void ClosePlayerCore()
    {
        _progressTimer?.Stop();
        _progressTimer = null;
        if (_mediaPlayer is not null)
        {
            ControlsOverlay.SetMediaPlayer(null, "", "");
            PlayerElement.SetMediaPlayer(null);
            _mediaPlayer.Dispose();
            _mediaPlayer = null;
        }
        _currentPlayback = null;
    }
}
