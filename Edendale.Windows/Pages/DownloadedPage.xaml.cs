using System.IO;
using System.Linq;
using System.Collections.Generic;
using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;

namespace Edendale.Windows.Pages;

/// <summary>A half-watched progress record joined back to its local library item.</summary>
public sealed class ResumeEntry
{
    public required string Title { get; init; }
    public required string Subtitle { get; init; }
    public string? ImageUrl { get; init; }
    public double Progress { get; init; }
    public string PlaceholderAsset { get; init; } = "ms-appx:///Assets/Icons/film.svg";
    public LibraryMovie? Movie { get; init; }
    public LibraryShow? Show { get; init; }
    public LibraryEpisode? Episode { get; init; }

    /// <summary>
    /// The saved position behind a resumable card; null for a next-up
    /// suggestion, which has none of its own.
    /// </summary>
    public WatchProgress? Record { get; init; }

    /// <summary>Orders resumable titles and next-up suggestions together.</summary>
    public long LastWatchedEpochMillis { get; init; }
}

/// <summary>
/// The local library (DownloadedView.swift): Continue Watching shelf,
/// movie/show poster grids, linked sources, and the silent-library
/// empty state.
/// </summary>
public sealed partial class DownloadedPage : Page
{
    private bool _rescannedThisVisit;

    /// <summary>The sidebar section this visit shows, or null for the whole page (DIFF.md §3.15).</summary>
    private Core.DownloadedSection? _section;

    public DownloadedPage()
    {
        InitializeComponent();
        AppServices.Library.Changed += (_, _) => DispatcherQueue.TryEnqueue(RefreshAll);
        AppServices.WatchProgress.Changed += (_, _) => DispatcherQueue.TryEnqueue(RefreshAll);
        AppServices.YoungAudience.Changed += (_, _) => DispatcherQueue.TryEnqueue(RefreshAll);
    }

    /// <summary>Library titles (that carry a TMDB id) the audience filter checks.</summary>
    private static List<MediaRef> LibraryAudienceRefs()
    {
        var library = AppServices.Library;
        return
        [
            .. library.Movies.Where(movie => movie.TmdbId is not null)
                .Select(movie => new MediaRef { Id = movie.TmdbId!.Value, MediaType = "movie" }),
            .. library.Shows.Where(show => show.TmdbId is not null)
                .Select(show => new MediaRef { Id = show.TmdbId!.Value, MediaType = "tv" }),
        ];
    }

    /// <summary>An unmatched local file (no TMDB id) is hidden while the filter is on.</summary>
    internal static bool AudienceAllows(int? tmdbId, string mediaType)
    {
        var filter = AppServices.YoungAudience;
        if (!filter.IsEnabled) return true;
        return tmdbId is int id && filter.Allows(new MediaRef { Id = id, MediaType = mediaType });
    }

    private async System.Threading.Tasks.Task VerifyAudienceAsync()
    {
        var refs = LibraryAudienceRefs();
        if (refs.Count > 0) await AppServices.YoungAudience.VerifyAsync(refs);
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _section = e.Parameter is Core.DownloadedSection section ? section : null;
        // A new section starts at the top; Back keeps the reader's place.
        if (e.NavigationMode != NavigationMode.Back) LibraryScroll.ChangeView(null, 0, null, disableAnimation: true);
        RefreshAll();
        if (!_rescannedThisVisit)
        {
            _rescannedThisVisit = true;
            // Files added outside the app surface without a manual rescan.
            _ = AppServices.Library.RescanAllFoldersAsync();
        }
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        base.OnNavigatedFrom(e);
        _rescannedThisVisit = false;
    }

    /// <summary>x:Bind hook for the poster grid's watched check.</summary>
    public static bool MovieWatched(int? tmdbId) =>
        tmdbId is int id && AppServices.WatchProgress.IsWatched(id, "movie");

    /// <summary>
    /// x:Bind hook for a Continue Watching card's menu: Clear Progress, which
    /// deletes the saved position and takes the title off the shelf (it syncs
    /// to other devices as a removal). An Up Next card gets no menu.
    /// </summary>
    public static FlyoutBase? ResumeContextFlyout(WatchProgress? record)
    {
        if (record is null) return null;
        var clear = new MenuFlyoutItem { Text = Loc.Get("Library_ClearProgress") };
        clear.Click += (_, _) => ClearProgress(record);
        return new MenuFlyout { Items = { clear } };
    }

    /// <summary>Writes the position away as a removal (a stamped tombstone the sync files carry).</summary>
    internal static void ClearProgress(WatchProgress record) =>
        AppServices.WatchProgress.Remove(record.TmdbId, record.MediaType, record.ShowTmdbId, record.SeasonNumber, record.EpisodeNumber);

    /// <summary>
    /// x:Bind hook for a show card's subtitle: seasons and episodes counted
    /// across every record of its title, an episode two sources hold once.
    /// </summary>
    public static string ShowSubtitle(Guid showId) => AppServices.Library.ShowSubtitle(showId) ?? "";

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void RefreshAll()
    {
        var library = AppServices.Library;

        EmptyState.Visibility = library.IsEmpty ? Visibility.Visible : Visibility.Collapsed;
        LibraryScroll.Visibility = library.IsEmpty ? Visibility.Collapsed : Visibility.Visible;
        if (library.IsEmpty) return;

        // Status row
        if (library.IsImporting)
        {
            StatusRing.IsActive = true;
            StatusText.Text = Loc.Get("Library_CataloguingNewFiles");
        }
        else if (library.IsEnriching)
        {
            StatusRing.IsActive = true;
            StatusText.Text = Loc.Get("Library_EnrichingMetadata");
        }
        else if (AppServices.YoungAudience.IsVerifying(LibraryAudienceRefs()))
        {
            StatusRing.IsActive = true;
            StatusText.Text = Loc.Get("Audience_Verifying");
        }
        else
        {
            StatusRing.IsActive = false;
            StatusText.Text = "";
        }
        ErrorText.Text = library.ErrorMessage ?? "";
        ErrorText.Visibility = library.ErrorMessage is null ? Visibility.Collapsed : Visibility.Visible;

        // Continue Watching: newest first, joined to local files (Apple parity).
        // The audience filter hides blocked titles here and in every grid below.
        var movies = library.Movies.Where(m => AudienceAllows(m.TmdbId, "movie")).ToList();
        var shows = library.Shows.Where(s => AudienceAllows(s.TmdbId, "tv")).ToList();
        // One card per title, however many sources hold a copy: the newest
        // copy stands for it, and its page plays and lists every copy.
        var movieTitles = PlaybackSources.Distinct(movies, PlaybackSources.CopyKey);
        var showTitles = PlaybackSources.Distinct(shows, PlaybackSources.CopyKey);

        if (_section is { } section)
        {
            // A section page: only that section. Continue Watching lists
            // every resumable title, and Movies includes the movies that are
            // also in Continue Watching.
            ContinueSection.Visibility = Visibility.Collapsed;
            SourcesSection.Visibility = Visibility.Collapsed;
            var everyResume = section == Core.DownloadedSection.ContinueWatching
                ? ContinueWatchingEntries(library, movies, limit: null)
                : [];
            ContinueGridSection.Visibility = everyResume.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            ResumeGridRepeater.ItemsSource = everyResume;
            var sectionMovies = section == Core.DownloadedSection.Movies ? movieTitles : [];
            MoviesSection.Visibility = sectionMovies.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            MoviesRepeater.ItemsSource = sectionMovies;
            var sectionShows = section == Core.DownloadedSection.Shows ? showTitles : [];
            ShowsSection.Visibility = sectionShows.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            ShowsRepeater.ItemsSource = sectionShows;
            _ = VerifyAudienceAsync();
            return;
        }

        ContinueGridSection.Visibility = Visibility.Collapsed;
        var resumeEntries = ContinueWatchingEntries(library, movies, limit: 12);
        ContinueSection.Visibility = resumeEntries.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        ResumeRepeater.ItemsSource = resumeEntries;

        // A movie surfaced in Continue Watching keeps one card, not two.
        var resumeMovieIds = resumeEntries
            .Where(entry => entry.Movie?.TmdbId is not null)
            .Select(entry => entry.Movie!.TmdbId!.Value)
            .ToHashSet();
        var gridMovies = movieTitles
            .Where(movie => movie.TmdbId is not int id || !resumeMovieIds.Contains(id))
            .ToList();
        MoviesSection.Visibility = gridMovies.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        MoviesRepeater.ItemsSource = gridMovies;

        ShowsSection.Visibility = showTitles.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        ShowsRepeater.ItemsSource = showTitles;

        BuildSources(library);

        // Resolve certifications for anything not yet decided; the filter's
        // Changed event re-runs this and the blocked titles drop out.
        _ = VerifyAudienceAsync();
    }

    /// <summary>
    /// Half-watched titles plus, for each show with nothing in progress, the
    /// stored episode after the furthest completed one (DIFF.md §3.4). The
    /// suggestion works after the watched file was deleted, merges duplicate
    /// show records into one card, and never writes progress. Each card
    /// starts the reachable copy played last, else the first reachable one
    /// (ResumeItem.items at apple-27.1 94a8b4f); so do the jump-list entries
    /// built from these.
    /// </summary>
    internal static List<ResumeEntry> ContinueWatchingEntries(LibraryService library, IReadOnlyList<LibraryMovie> movies, int? limit)
    {
        var entries = new List<ResumeEntry>();
        var inProgress = AppServices.WatchProgress.InProgress;
        foreach (var progress in inProgress)
        {
            if (progress.MediaType == "movie")
            {
                var movie = movies.Any(m => m.TmdbId == progress.TmdbId) ? library.PreferredMovie(progress.TmdbId) : null;
                if (movie is null) continue;
                entries.Add(new ResumeEntry
                {
                    Title = movie.Title,
                    Subtitle = Loc.Format("Library_PercentWatched", (int)(progress.Position * 100)),
                    ImageUrl = movie.BackdropUrl ?? movie.PosterUrl,
                    Progress = progress.Position,
                    PlaceholderAsset = "ms-appx:///Assets/Icons/film.svg",
                    Movie = movie,
                    Record = progress,
                    LastWatchedEpochMillis = progress.LastWatchedEpochMillis,
                });
            }
            else
            {
                if (library.PreferredEpisode(progress.TmdbId) is not var (show, episode)) continue;
                if (!AudienceAllows(show.TmdbId, "tv")) continue;
                entries.Add(new ResumeEntry
                {
                    Title = show.Name,
                    Subtitle = $"{episode.EpisodeCode} · {episode.DisplayTitle}",
                    ImageUrl = episode.StillUrl ?? show.BackdropUrl,
                    Progress = progress.Position,
                    PlaceholderAsset = "ms-appx:///Assets/Icons/tv.svg",
                    Show = show,
                    Episode = episode,
                    Record = progress,
                    LastWatchedEpochMillis = progress.LastWatchedEpochMillis,
                });
            }
        }

        var inProgressShows = inProgress
            .Where(progress => progress.MediaType == "episode" && progress.ShowTmdbId is not null)
            .Select(progress => progress.ShowTmdbId!.Value)
            .ToHashSet();
        // The next episode comes from the show's reachable source played last, else the first reachable one.
        var nextUps = EpisodeProgression.NextUpEpisodes(
            AppServices.WatchProgress.All,
            inProgressShows,
            library.Shows,
            copies => PlaybackSources.SourcePreference(copies, library.FolderById, library.IsUnavailable));
        foreach (var nextUp in nextUps)
        {
            if (!AudienceAllows(nextUp.Show.TmdbId, "tv")) continue;
            entries.Add(new ResumeEntry
            {
                Title = nextUp.Show.Name,
                Subtitle = Loc.Format("Library_UpNext", $"{nextUp.Episode.EpisodeCode} · {nextUp.Episode.DisplayTitle}"),
                ImageUrl = nextUp.Episode.StillUrl ?? nextUp.Show.BackdropUrl,
                Progress = 0,
                PlaceholderAsset = "ms-appx:///Assets/Icons/tv.svg",
                Show = nextUp.Show,
                Episode = nextUp.Episode,
                LastWatchedEpochMillis = nextUp.LastWatchedEpochMillis,
            });
        }

        var ordered = entries.OrderByDescending(entry => entry.LastWatchedEpochMillis);
        return limit is int count ? [.. ordered.Take(count)] : [.. ordered];
    }

    private void BuildSources(LibraryService library)
    {
        var folders = library.Folders;
        SourcesSection.Visibility = folders.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        SourcesList.Children.Clear();
        var resources = Application.Current.Resources;

        foreach (var folder in folders)
        {
            var count = library.ItemCount(folder);
            var row = new Grid { Padding = new Thickness(0, 12, 0, 12), ColumnSpacing = 14 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var icon = new Controls.SvgIcon
            {
                UriSource = new Uri(Controls.SourceActions.IconUri(folder)),
                Width = 18, Height = 18,
                Foreground = (Brush)resources["EdendaleTextSecondaryBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            };
            Grid.SetColumn(icon, 0);
            row.Children.Add(icon);

            var text = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(new TextBlock
            {
                Text = folder.Name,
                FontFamily = (FontFamily)resources["TextFontFamily"],
                FontSize = 15,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = (Brush)resources["EdendaleTextPrimaryBrush"],
                TextTrimming = TextTrimming.CharacterEllipsis,
            });
            text.Children.Add(new TextBlock
            {
                Text = Controls.SourceActions.Subtitle(folder, count),
                Style = (Style)resources["BodySMTextStyle"],
                TextTrimming = TextTrimming.CharacterEllipsis,
            });
            // The last scan's failure stays on its own row, not library-wide.
            if (Controls.SourceActions.StateText(folder) is { } state)
            {
                text.Children.Add(new TextBlock
                {
                    Text = state,
                    Style = (Style)resources["BodySMTextStyle"],
                    Foreground = (Brush)resources["EdendaleGoldBrush"],
                    TextWrapping = TextWrapping.Wrap,
                });
            }
            Grid.SetColumn(text, 1);
            row.Children.Add(text);

            var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
            if (Controls.SourceActions.NeedsSignIn(folder))
            {
                var signIn = new Button
                {
                    Style = (Style)resources["ArchiveGhostButtonStyle"],
                    Content = Loc.Get("Source_SignIn"),
                };
                signIn.Click += async (_, _) => await Controls.SourceActions.SignInAsync(XamlRoot, folder);
                actions.Children.Add(signIn);
            }

            // Google Drive: reopen Google's picker to add videos (3.20 R7).
            if (Controls.SourceActions.CanChooseMoreVideos(folder))
            {
                var chooseMore = new Button
                {
                    Style = (Style)resources["ArchiveGhostButtonStyle"],
                    Content = new Controls.SvgIcon { UriSource = new Uri("ms-appx:///Assets/Icons/plus.svg"), Width = 14, Height = 14 },
                };
                ToolTipService.SetToolTip(chooseMore, Loc.Get("GoogleDrive_ChooseMoreTitle"));
                AutomationProperties.SetName(chooseMore, $"{Loc.Get("GoogleDrive_ChooseMoreTitle")}, {folder.Name}");
                chooseMore.Click += async (_, _) => await Controls.SourceActions.ChooseMoreVideosAsync(XamlRoot, folder);
                actions.Children.Add(chooseMore);
            }

            var rescan = new Button
            {
                Style = (Style)resources["ArchiveGhostButtonStyle"],
                Content = new Controls.SvgIcon { UriSource = new Uri("ms-appx:///Assets/Icons/arrow-rotate-right.svg"), Width = 14, Height = 14 },
            };
            ToolTipService.SetToolTip(rescan, Loc.Get("Source_Rescan"));
            AutomationProperties.SetName(rescan, $"{Loc.Get("Source_Rescan")}, {folder.Name}");
            rescan.Click += async (_, _) => await AppServices.Library.RescanFolderAsync(folder);
            actions.Children.Add(rescan);

            var remove = new Button
            {
                Style = (Style)resources["ArchiveGhostButtonStyle"],
                Content = new Controls.SvgIcon { UriSource = new Uri("ms-appx:///Assets/Icons/trash-can.svg"), Width = 14, Height = 14 },
            };
            ToolTipService.SetToolTip(remove, Loc.Get("Source_Remove"));
            AutomationProperties.SetName(remove, $"{Loc.Get("Source_Remove")}, {folder.Name}");
            remove.Click += async (_, _) => await Controls.SourceActions.RemoveAsync(XamlRoot, folder);
            actions.Children.Add(remove);
            Grid.SetColumn(actions, 2);
            row.Children.Add(actions);

            var container = new StackPanel();
            container.Children.Add(row);
            container.Children.Add(new Microsoft.UI.Xaml.Shapes.Rectangle
            {
                Height = 1,
                Fill = (Brush)resources["EdendaleHairlineBorderBrush"],
                HorizontalAlignment = HorizontalAlignment.Stretch,
            });
            SourcesList.Children.Add(container);
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /// <summary>Ctrl+N and the Add Local Folder buttons.</summary>
    public Task AddFolderAsync() => Controls.SourceActions.AddLocalFolderAsync();

    /// <summary>Ctrl+Alt+N and the Add Network Source buttons.</summary>
    public Task LinkSourceAsync() => Controls.LinkSourceDialog.ShowAsync(XamlRoot);

    private async void AddFolder_Click(object sender, RoutedEventArgs e) => await AddFolderAsync();

    private async void AddNetworkSource_Click(object sender, RoutedEventArgs e) => await LinkSourceAsync();

    private async void LearnSyncing_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            Title = Loc.Get("Privacy_Title"),
            Content = Loc.Get("Privacy_Body"),
            CloseButtonText = Loc.Get("Common_OK"),
            XamlRoot = XamlRoot,
        };
        await dialog.ShowAsync();
    }

    private void Movie_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is LibraryMovie movie)
        {
            NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(LocalMovieId: movie.Id));
        }
    }

    private void Show_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is LibraryShow show)
        {
            NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(LocalShowId: show.Id));
        }
    }

    /// <summary>
    /// A movie card's menu, built as it opens so it is current: Clear
    /// Progress while the movie is under way, then Remove for a title one
    /// source holds, or one "Remove from Media (nas.local)" per copy, its kind
    /// and file name beside it, which tells apart two files in one source
    /// (DownloadedView at apple-27.1 94a8b4f). Removing one copy leaves the
    /// others.
    /// </summary>
    private void MovieMenu_Opening(object? sender, object e)
    {
        if (sender is not MenuFlyout menu || menu.Target is not FrameworkElement { Tag: LibraryMovie movie }) return;
        var library = AppServices.Library;
        menu.Items.Clear();
        if (movie.TmdbId is int id && AppServices.WatchProgress.Get(id, "movie") is { IsCompleted: false, Position: > 0 } progress)
        {
            menu.Items.Add(MenuItem(Loc.Get("Library_ClearProgress"), null, () => ClearProgress(progress)));
        }
        var copies = library.MovieCopies(movie);
        if (copies.Count <= 1)
        {
            menu.Items.Add(MenuItem(Loc.Get("Common_Remove"), null, () => library.RemoveMovie(movie)));
            return;
        }
        foreach (var copy in copies)
        {
            var folder = library.FolderById(copy.FolderId);
            menu.Items.Add(MenuItem(
                Loc.Format("Library_RemoveFrom", PlaybackSources.Title(folder, copy.FilePath)),
                PlaybackSources.Detail(folder, copy.FilePath),
                () => library.RemoveMovie(copy)));
        }
    }

    /// <summary>
    /// A show card's menu: Remove for a title one source holds, or one
    /// "Remove from …" per source, which takes that source's episodes of the
    /// show and leaves the others. One record can span sources on Windows, so
    /// a source is what Apple calls a copy of the show.
    /// </summary>
    private void ShowMenu_Opening(object? sender, object e)
    {
        if (sender is not MenuFlyout menu || menu.Target is not FrameworkElement { Tag: LibraryShow show }) return;
        var library = AppServices.Library;
        menu.Items.Clear();
        var copies = library.ShowCopies(show);
        var sources = library.ShowSources(copies);
        if (sources.Count <= 1)
        {
            menu.Items.Add(MenuItem(Loc.Get("Common_Remove"), null, () => library.RemoveShows(copies)));
            return;
        }
        foreach (var id in sources)
        {
            var folder = library.FolderById(id);
            var name = folder is null ? Loc.Get("Source_Unavailable") : PlaybackSources.Title(folder, folder.Path);
            menu.Items.Add(MenuItem(Loc.Format("Library_RemoveFrom", name), null, () => library.RemoveShowSource(copies, id)));
        }
    }

    /// <summary>A menu row; <paramref name="detail"/> sits on the right and is read with the row's name.</summary>
    private static MenuFlyoutItem MenuItem(string text, string? detail, Action action)
    {
        var item = new MenuFlyoutItem { Text = text };
        if (detail is not null)
        {
            item.KeyboardAcceleratorTextOverride = detail;
            AutomationProperties.SetName(item, $"{text}, {detail}");
        }
        item.Click += (_, _) => action();
        return item;
    }

    private void Resume_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not ResumeEntry entry) return;
        if (entry.Movie is { } movie) AppServices.Player.Play(movie);
        else if (entry.Show is { } show && entry.Episode is { } episode) AppServices.Player.Play(show, episode);
    }
}
