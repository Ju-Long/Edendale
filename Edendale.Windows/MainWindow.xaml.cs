using Edendale.Windows.Pages;
using Edendale.Windows.Services;
using LibVLCSharp.Platforms.Windows;
using LibVLCSharp.Shared;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Edendale.Windows;

/// <summary>
/// The Edendale shell: custom title bar, fixed sidebar navigation over a
/// content frame, and the full-window player overlay. Mirrors the macOS
/// RootView arrangement (sidebar tabs, Settings pinned at the bottom).
/// </summary>
public sealed partial class MainWindow : Window
{
    private LibVLC? _libVlc;
    private MediaPlayer? _mediaPlayer;
    private PlaybackRequest? _currentPlayback;
    private DispatcherQueueTimer? _progressTimer;
    private bool _isCompactOverlay;
    private bool _resumePending;
    private bool _aspectFill;

    public MainWindow()
    {
        InitializeComponent();

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.Resize(new global::Windows.Graphics.SizeInt32(1440, 900));
        AppWindow.SetIcon("Assets\\icon.ico");
        Closed += MainWindow_Closed;

        MoviesNavItem.Icon = Controls.SvgIcon.CreateIcon("film");
        WatchlistNavItem.Icon = Controls.SvgIcon.CreateIcon("film-stack");
        DownloadedNavItem.Icon = Controls.SvgIcon.CreateIcon("folder-closed");
        SearchNavItem.Icon = Controls.SvgIcon.CreateIcon("magnifying-glass-play");

        NavigationService.Frame = RootFrame;
        RootFrame.Navigate(typeof(MoviesShowsPage));

        AppServices.Player.PlaybackRequested += (_, request) =>
            DispatcherQueue.TryEnqueue(() => OpenPlayer(request));

        // The Watchlist tab appears only when a visible item is saved.
        AppServices.Watchlist.Changed += (_, _) => DispatcherQueue.TryEnqueue(UpdateWatchlistTab);
        AppServices.YoungAudience.Changed += (_, _) => DispatcherQueue.TryEnqueue(UpdateWatchlistTab);
        UpdateWatchlistTab();
    }

    // ------------------------------------------------------------------
    // Watchlist tab visibility (RootView.hasWatchlistItems parity)
    // ------------------------------------------------------------------

    private async void UpdateWatchlistTab()
    {
        var items = AppServices.Watchlist.Items;
        var filter = AppServices.YoungAudience;
        if (filter.IsEnabled && items.Count > 0)
        {
            await filter.VerifyAsync(items.Select(item => item.Ref));
        }

        var hasVisible = items.Any(item => filter.Allows(item.Ref));
        WatchlistNavItem.Visibility = hasVisible ? Visibility.Visible : Visibility.Collapsed;

        // The audience filter can empty the watchlist while it is open; fall
        // back to Movies & Shows so the reader is never stranded on a dead tab.
        if (!hasVisible && (Nav.SelectedItem as NavigationViewItem)?.Tag as string == "watchlist")
        {
            SelectSidebar("movies");
            NavigateRoot(typeof(MoviesShowsPage));
        }
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
            case "watchlist": NavigateRoot(typeof(WatchlistPage)); break;
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
                _ = GatedPlayAsync(playMovie.TmdbId, "movie", () =>
                {
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
                });
                break;

            case AppRoute.PlayEpisode playEpisode:
                if (AppServices.Library.EpisodeByTmdbId(playEpisode.TmdbId) is { } episode
                    && AppServices.Library.ShowForEpisode(episode) is { } episodeShow)
                {
                    _ = GatedPlayShowAsync(episodeShow, () => AppServices.Player.Play(episodeShow, episode));
                }
                else
                {
                    ShowActivationMessage(Loc.Get("Player_NoLocalFile"));
                }
                break;

            case AppRoute.PlayLocalMovie playLocal
                when AppServices.Library.Movies.FirstOrDefault(m => m.Id == playLocal.Id) is { } localFile:
                _ = GatedPlayMovieAsync(localFile);
                break;

            case AppRoute.PlayLocalEpisode playLocalEpisode:
                var match = AppServices.Library.Shows
                    .SelectMany(s => s.Episodes.Select(ep => (Show: s, Episode: ep)))
                    .FirstOrDefault(pair => pair.Episode.Id == playLocalEpisode.Id);
                if (match.Episode is not null)
                {
                    _ = GatedPlayShowAsync(match.Show, () => AppServices.Player.Play(match.Show, match.Episode));
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
    // Young Audience playback gate (AppRouter.isVisibleToSelectedAudience)
    // ------------------------------------------------------------------

    private async Task GatedPlayAsync(int tmdbId, string mediaType, Action play)
    {
        if (await AudiencePermitsAsync(new Models.MediaRef { Id = tmdbId, MediaType = mediaType })) play();
    }

    private Task GatedPlayMovieAsync(LibraryMovie movie) =>
        GatedPlayLocalAsync(movie.TmdbId, "movie", () => AppServices.Player.Play(movie));

    private Task GatedPlayShowAsync(LibraryShow show, Action play) =>
        GatedPlayLocalAsync(show.TmdbId, "tv", play);

    /// <summary>An unmatched local item (no TMDB id) is hidden while the filter is on.</summary>
    private async Task GatedPlayLocalAsync(int? tmdbId, string mediaType, Action play)
    {
        if (AppServices.YoungAudience.IsEnabled)
        {
            if (tmdbId is not int id) return;
            if (!await AudiencePermitsAsync(new Models.MediaRef { Id = id, MediaType = mediaType })) return;
        }
        play();
    }

    private static async Task<bool> AudiencePermitsAsync(Models.MediaRef reference)
    {
        var filter = AppServices.YoungAudience;
        if (!filter.IsEnabled) return true;
        await filter.VerifyAsync([reference]);
        return filter.Allows(reference);
    }

    // ------------------------------------------------------------------
    // Player lifecycle + watch-progress loop
    // ------------------------------------------------------------------

    private void OpenPlayer(PlaybackRequest request)
    {
        ClosePlayerCore();

        PlaylistPanel.Visibility = Visibility.Collapsed;
        _currentPlayback = request;
        _resumePending = true;

        PlayerOverlay.Visibility = Visibility.Visible;
        ControlsOverlay.SetMediaPlayer(
            null, request.Title.ToUpperInvariant(), request.Subtitle, request);
        ControlsOverlay.Focus(FocusState.Programmatic);

        // The WinUI VideoView creates its Direct3D swap chain only after it is
        // visible. The first playback request therefore waits for Initialized;
        // later requests can start immediately on the existing LibVLC engine.
        if (_libVlc is not null) StartPlayback(request);
    }

    private void PlayerElement_Initialized(object sender, InitializedEventArgs e)
    {
        if (_libVlc is not null) return;

        try
        {
            _libVlc = new LibVLC(e.SwapChainOptions);
            if (_currentPlayback is not null) StartPlayback(_currentPlayback);
        }
        catch (VLCException)
        {
            ClosePlayer();
            ShowActivationMessage(Loc.Get("Activation_UnsupportedFile"));
        }
    }

    private void StartPlayback(PlaybackRequest request)
    {
        if (_libVlc is null || !ReferenceEquals(request, _currentPlayback)) return;

        try
        {
            var player = new MediaPlayer(_libVlc);
            player.Playing += MediaPlayer_Playing;
            player.LengthChanged += MediaPlayer_LengthChanged;
            player.EndReached += MediaPlayer_EndReached;
            player.EncounteredError += MediaPlayer_EncounteredError;

            _mediaPlayer = player;
            PlayerElement.MediaPlayer = player;
            ControlsOverlay.SetMediaPlayer(
                player, request.Title.ToUpperInvariant(), request.Subtitle, request);

            using var media = new Media(_libVlc, new Uri(request.FilePath));
            if (!player.Play(media))
            {
                ClosePlayer();
                ShowActivationMessage(Loc.Get("Activation_UnsupportedFile"));
                return;
            }

            _progressTimer = DispatcherQueue.CreateTimer();
            _progressTimer.Interval = TimeSpan.FromSeconds(5);
            _progressTimer.Tick += (_, _) => WriteProgress();
            _progressTimer.Start();
        }
        catch (VLCException)
        {
            ClosePlayer();
            ShowActivationMessage(Loc.Get("Activation_UnsupportedFile"));
        }
    }

    private void MediaPlayer_Playing(object? sender, EventArgs e)
    {
        if (sender is not MediaPlayer player) return;
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(player, _mediaPlayer)) return;
            ResumeIfNeeded(player);
            ApplyAspectMode();
        });
    }

    private void MediaPlayer_LengthChanged(object? sender, EventArgs e)
    {
        if (sender is not MediaPlayer player) return;
        DispatcherQueue.TryEnqueue(() =>
        {
            if (ReferenceEquals(player, _mediaPlayer)) ResumeIfNeeded(player);
        });
    }

    private void MediaPlayer_EndReached(object? sender, EventArgs e)
    {
        if (sender is not MediaPlayer player) return;
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(player, _mediaPlayer)) return;
            CompleteCurrent();
            ClosePlayer();
        });
    }

    private void MediaPlayer_EncounteredError(object? sender, EventArgs e)
    {
        if (sender is not MediaPlayer player) return;
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(player, _mediaPlayer)) return;
            ClosePlayer();
            ShowActivationMessage(Loc.Get("Activation_UnsupportedFile"));
        });
    }

    /// <summary>Resume from the stored position when half-watched (Apple parity).</summary>
    private void ResumeIfNeeded(MediaPlayer player)
    {
        if (!_resumePending) return;
        if (_currentPlayback?.TmdbId is not int tmdbId)
        {
            _resumePending = false;
            return;
        }
        var progress = AppServices.WatchProgress.Get(tmdbId, _currentPlayback.MediaType);
        if (progress is null || progress.IsCompleted || progress.Position <= 0.005)
        {
            _resumePending = false;
            return;
        }

        if (player.Length > 0)
        {
            player.Time = (long)(player.Length * progress.Position);
            _resumePending = false;
        }
    }

    private void WriteProgress()
    {
        if (_mediaPlayer is null || _currentPlayback?.TmdbId is not int tmdbId) return;
        var durationMilliseconds = _mediaPlayer.Length;
        if (durationMilliseconds <= 0) return;
        var positionMilliseconds = Math.Max(0, _mediaPlayer.Time);

        AppServices.WatchProgress.Update(
            tmdbId,
            _currentPlayback.MediaType,
            (double)positionMilliseconds / durationMilliseconds,
            positionMilliseconds / 1000.0,
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
        _aspectFill = fill;
        ApplyAspectMode();
    }

    private void PlayerElement_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (_aspectFill) ApplyAspectMode();
    }

    private void ApplyAspectMode()
    {
        if (_mediaPlayer is null) return;

        if (!_aspectFill)
        {
            _mediaPlayer.CropGeometry = null;
            _mediaPlayer.Scale = 0;
            return;
        }

        var width = Math.Max(1, (int)Math.Round(PlayerElement.ActualWidth));
        var height = Math.Max(1, (int)Math.Round(PlayerElement.ActualHeight));
        var divisor = GreatestCommonDivisor(width, height);
        _mediaPlayer.CropGeometry = $"{width / divisor}:{height / divisor}";
    }

    private static int GreatestCommonDivisor(int left, int right)
    {
        while (right != 0)
        {
            (left, right) = (right, left % right);
        }
        return left;
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
            PlayerElement.MediaPlayer = null;
            _mediaPlayer.Playing -= MediaPlayer_Playing;
            _mediaPlayer.LengthChanged -= MediaPlayer_LengthChanged;
            _mediaPlayer.EndReached -= MediaPlayer_EndReached;
            _mediaPlayer.EncounteredError -= MediaPlayer_EncounteredError;
            _mediaPlayer.Stop();
            _mediaPlayer.Dispose();
            _mediaPlayer = null;
        }
        _resumePending = false;
        _currentPlayback = null;
    }

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        ClosePlayerCore();
        _libVlc?.Dispose();
        _libVlc = null;
    }
}
