using Edendale.Windows.Pages;
using Edendale.Windows.Services;
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

        InitializePlayer();
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
}
