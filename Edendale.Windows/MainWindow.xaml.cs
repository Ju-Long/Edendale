using Edendale.Windows.Core;
using Edendale.Windows.Pages;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using VirtualKey = Windows.System.VirtualKey;
using VirtualKeyModifiers = Windows.System.VirtualKeyModifiers;

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

        // The Watchlist tab appears only when a visible item is saved, and
        // each section row only while its section has titles.
        AppServices.Watchlist.Changed += (_, _) => DispatcherQueue.TryEnqueue(UpdateWatchlistTab);
        AppServices.YoungAudience.Changed += (_, _) => DispatcherQueue.TryEnqueue(() =>
        {
            UpdateWatchlistTab();
            UpdateDownloadedSections();
        });
        AppServices.Library.Changed += (_, _) => DispatcherQueue.TryEnqueue(UpdateDownloadedSections);
        AppServices.WatchProgress.Changed += (_, _) => DispatcherQueue.TryEnqueue(UpdateDownloadedSections);
        UpdateWatchlistTab();
        UpdateDownloadedSections();

        AddShortcuts();
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

        var visible = items.Where(item => filter.Allows(item.Ref)).ToList();
        var hasVisible = visible.Count > 0;
        WatchlistNavItem.Visibility = hasVisible ? Visibility.Visible : Visibility.Collapsed;
        _watchlistSections = LibrarySections.AvailableWatchlist(visible.Select(item => item.MediaType));
        WatchlistMoviesNavItem.Visibility = Shown(_watchlistSections.Contains(WatchlistSection.Movies));
        WatchlistShowsNavItem.Visibility = Shown(_watchlistSections.Contains(WatchlistSection.Shows));

        // The audience filter can empty the watchlist while it is open; fall
        // back to Movies & Shows so the reader is never stranded on a dead tab.
        if (!hasVisible && SelectedSidebarItem?.Tab == "watchlist")
        {
            SelectSidebar("movies");
            NavigateRoot(typeof(MoviesShowsPage));
            return;
        }
        ResolveSelectedSection();
    }

    // ------------------------------------------------------------------
    // Section rows (DIFF.md §3.15)
    // ------------------------------------------------------------------

    private IReadOnlyList<WatchlistSection> _watchlistSections = [];
    private IReadOnlyList<DownloadedSection> _downloadedSections = [];

    private static Visibility Shown(bool visible) => visible ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>Downloaded's rows: Continue Watching, Movies, and TV Shows, for the current audience.</summary>
    private void UpdateDownloadedSections()
    {
        var library = AppServices.Library;
        var movies = library.Movies.Where(movie => DownloadedPage.AudienceAllows(movie.TmdbId, "movie")).ToList();
        var shows = library.Shows.Count(show => DownloadedPage.AudienceAllows(show.TmdbId, "tv"));
        var hasResume = DownloadedPage.ContinueWatchingEntries(library, movies, limit: 1).Count > 0;
        _downloadedSections = LibrarySections.AvailableDownloaded(hasResume, movies.Count, shows);

        DownloadedContinueNavItem.Visibility = Shown(_downloadedSections.Contains(DownloadedSection.ContinueWatching));
        DownloadedMoviesNavItem.Visibility = Shown(_downloadedSections.Contains(DownloadedSection.Movies));
        DownloadedShowsNavItem.Visibility = Shown(_downloadedSections.Contains(DownloadedSection.Shows));
        ResolveSelectedSection();
    }

    private SidebarItem? SelectedSidebarItem =>
        (Nav.SelectedItem as NavigationViewItem)?.Tag is string tag ? SidebarItem.Parse(tag) : null;

    /// <summary>If the open section emptied, the sidebar returns to its parent page.</summary>
    private void ResolveSelectedSection()
    {
        if (SelectedSidebarItem is not { Section: not null } selected) return;
        var resolved = selected.Resolved(_watchlistSections, _downloadedSections);
        if (resolved == selected) return;
        SelectSidebar(resolved.NavTag);
        NavigateTo(resolved);
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
        if ((args.SelectedItem as NavigationViewItem)?.Tag is string tag) NavigateTo(SidebarItem.Parse(tag));
    }

    /// <summary>
    /// Choosing the row that is already selected raises no SelectionChanged,
    /// so a detail page on top of it returns to the page's root here.
    /// </summary>
    private void Nav_ItemInvoked(NavigationView sender, NavigationViewItemInvokedEventArgs args)
    {
        if (args.IsSettingsInvoked || args.InvokedItemContainer?.Tag is not string tag) return;
        if (SelectedSidebarItem?.NavTag == tag) NavigateTo(SidebarItem.Parse(tag));
    }

    /// <summary>Choosing a row opens that page at its root.</summary>
    private void NavigateTo(SidebarItem item)
    {
        switch (item.Tab)
        {
            case "movies": NavigateRoot(typeof(MoviesShowsPage)); break;
            case "watchlist": NavigateRoot(typeof(WatchlistPage), item.WatchlistSection); break;
            case "downloaded": NavigateRoot(typeof(DownloadedPage), item.DownloadedSection); break;
            case "search": NavigateRoot(typeof(SearchPage)); break;
        }
    }

    private object? _rootParameter;

    private void NavigateRoot(Type pageType, object? parameter = null)
    {
        // A detail page on top, or another section of the same page, both
        // navigate; re-choosing the page already shown does nothing.
        if (RootFrame.CurrentSourcePageType != pageType || !Equals(_rootParameter, parameter))
        {
            _rootParameter = parameter;
            RootFrame.Navigate(pageType, parameter);
            // Tab switches start fresh; only detail pushes stack up.
            RootFrame.BackStack.Clear();
        }
    }

    // ------------------------------------------------------------------
    // Shortcuts (DIFF.md §3.15)
    // ------------------------------------------------------------------

    private void AddShortcuts()
    {
        Nav.KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden;
        AddShortcut(VirtualKey.B, VirtualKeyModifiers.Control, ToggleSidebar);
        AddShortcut(VirtualKey.N, VirtualKeyModifiers.Control, () => WithDownloadedPage(page => page.AddFolderAsync()));
        AddShortcut(VirtualKey.N, VirtualKeyModifiers.Control | VirtualKeyModifiers.Menu, () => WithDownloadedPage(page => page.LinkSourceAsync()));
        AddShortcut(VirtualKey.R, VirtualKeyModifiers.Control, Rescan);
        AddShortcut(VirtualKey.F5, VirtualKeyModifiers.None, Rescan);
    }

    private void AddShortcut(VirtualKey key, VirtualKeyModifiers modifiers, Func<bool> action)
    {
        var accelerator = new KeyboardAccelerator { Key = key, Modifiers = modifiers };
        accelerator.Invoked += (_, args) =>
        {
            // The player keeps its own keys; library shortcuts wait until it closes.
            if (PlayerOverlay.Visibility == Visibility.Visible) return;
            args.Handled = action();
        };
        Nav.KeyboardAccelerators.Add(accelerator);
    }

    /// <summary>Ctrl+B: the sidebar folds to its icons and back.</summary>
    private bool ToggleSidebar()
    {
        Nav.IsPaneOpen = !Nav.IsPaneOpen;
        return true;
    }

    /// <summary>Ctrl+N and Ctrl+Alt+N act on the Downloaded pages only.</summary>
    private bool WithDownloadedPage(Func<DownloadedPage, Task> action)
    {
        if (RootFrame.Content is not DownloadedPage page) return false;
        _ = action(page);
        return true;
    }

    /// <summary>Ctrl+R or F5 rescans every source, ignoring the 15-minute throttle, once one is linked.</summary>
    private bool Rescan()
    {
        if (AppServices.Library.Folders.Count == 0) return false;
        _ = AppServices.Library.RescanAllFoldersAsync(force: true);
        return true;
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
            .SelectMany(item => item.MenuItems.OfType<NavigationViewItem>().Prepend(item))
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
