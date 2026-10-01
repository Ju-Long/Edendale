using System.IO;
using System.Linq;
using System.Collections.Generic;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
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
            var sectionMovies = section == Core.DownloadedSection.Movies ? movies : [];
            MoviesSection.Visibility = sectionMovies.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            MoviesRepeater.ItemsSource = sectionMovies;
            var sectionShows = section == Core.DownloadedSection.Shows ? shows : [];
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
        var gridMovies = movies
            .Where(movie => movie.TmdbId is not int id || !resumeMovieIds.Contains(id))
            .ToList();
        MoviesSection.Visibility = gridMovies.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        MoviesRepeater.ItemsSource = gridMovies;

        ShowsSection.Visibility = shows.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        ShowsRepeater.ItemsSource = shows;

        BuildSources(library);

        // Resolve certifications for anything not yet decided; the filter's
        // Changed event re-runs this and the blocked titles drop out.
        _ = VerifyAudienceAsync();
    }

    /// <summary>
    /// Half-watched titles plus, for each show with nothing in progress, the
    /// stored episode after the furthest completed one (DIFF.md §3.4). The
    /// suggestion works after the watched file was deleted, merges duplicate
    /// show records into one card, and never writes progress.
    /// </summary>
    internal static List<ResumeEntry> ContinueWatchingEntries(LibraryService library, IReadOnlyList<LibraryMovie> movies, int? limit)
    {
        var entries = new List<ResumeEntry>();
        var inProgress = AppServices.WatchProgress.InProgress;
        foreach (var progress in inProgress)
        {
            if (progress.MediaType == "movie")
            {
                var movie = movies.FirstOrDefault(m => m.TmdbId == progress.TmdbId);
                if (movie is null) continue;
                entries.Add(new ResumeEntry
                {
                    Title = movie.Title,
                    Subtitle = Loc.Format("Library_PercentWatched", (int)(progress.Position * 100)),
                    ImageUrl = movie.BackdropUrl ?? movie.PosterUrl,
                    Progress = progress.Position,
                    PlaceholderAsset = "ms-appx:///Assets/Icons/film.svg",
                    Movie = movie,
                    LastWatchedEpochMillis = progress.LastWatchedEpochMillis,
                });
            }
            else
            {
                var episode = library.EpisodeByTmdbId(progress.TmdbId);
                if (episode is null) continue;
                var show = library.ShowForEpisode(episode);
                if (show is null || !AudienceAllows(show.TmdbId, "tv")) continue;
                entries.Add(new ResumeEntry
                {
                    Title = show.Name,
                    Subtitle = $"{episode.EpisodeCode} · {episode.DisplayTitle}",
                    ImageUrl = episode.StillUrl ?? show.BackdropUrl,
                    Progress = progress.Position,
                    PlaceholderAsset = "ms-appx:///Assets/Icons/tv.svg",
                    Show = show,
                    Episode = episode,
                    LastWatchedEpochMillis = progress.LastWatchedEpochMillis,
                });
            }
        }

        var inProgressShows = inProgress
            .Where(progress => progress.MediaType == "episode" && progress.ShowTmdbId is not null)
            .Select(progress => progress.ShowTmdbId!.Value)
            .ToHashSet();
        foreach (var nextUp in Core.EpisodeProgression.NextUpEpisodes(AppServices.WatchProgress.All, inProgressShows, library.Shows))
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

    private void RemoveMovie_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is LibraryMovie movie)
        {
            AppServices.Library.RemoveMovie(movie);
        }
    }

    private void RemoveShow_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is LibraryShow show)
        {
            AppServices.Library.RemoveShow(show);
        }
    }

    private void Resume_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not ResumeEntry entry) return;
        if (entry.Movie is { } movie) AppServices.Player.Play(movie);
        else if (entry.Show is { } show && entry.Episode is { } episode) AppServices.Player.Play(show, episode);
    }
}
