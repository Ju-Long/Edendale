using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using Microsoft.UI.Xaml.Shapes;

namespace Edendale.Windows.Pages;

/// <summary>
/// The TMDB-driven front page (MoviesShowsView.swift): rotating hero,
/// poster shelves, and chip-filtered curated collections.
/// </summary>
public sealed partial class MoviesShowsPage : Page
{
    private const int HeroSceneSeconds = 10;

    private DispatcherQueueTimer? _heroTimer;
    private int _heroIndex;
    private bool _heroHovered;
    private bool _trailerUnavailable;
    private TrailerVideo? _heroTrailer;

    private DispatcherQueueTimer? _continueTimer;

    /// <summary>Watch progress changed since Home last read it.</summary>
    private bool _continueStale;

    /// <summary>Guards a slow detail lookup from painting over a later refresh.</summary>
    private int _continueRequest;

    public MoviesShowsPage()
    {
        InitializeComponent();
        Loaded += async (_, _) => await EnsureLoadedAsync();
        // Re-filter shelves/hero/collection when the audience preference or a
        // certification decision changes.
        AppServices.YoungAudience.Changed += (_, _) =>
            DispatcherQueue.TryEnqueue(() => ApplyAudienceContent(resetHero: false));
        // Playback here and merges from other devices move watch progress;
        // Continue Watching follows once a burst of changes settles.
        AppServices.WatchProgress.Changed += (_, _) => DispatcherQueue.TryEnqueue(ScheduleContinueRefresh);
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        StartHeroTimer();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        base.OnNavigatedFrom(e);
        _heroTimer?.Stop();
        _heroTimer = null;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private async Task EnsureLoadedAsync()
    {
        if (HomeSession.Catalog is not null)
        {
            ShowContent();
            // A refresh that failed while Home was away tries again.
            if (_continueStale) await RefreshContinueWatchingAsync();
            return;
        }

        if (!WindowsCore.HasTmdbCredentials)
        {
            MissingKeyMessage.Text =
                Loc.Get("Tmdb_MissingCredentials");
            SwitchState(MissingKeyState);
            return;
        }

        SwitchState(LoadingState);
        try
        {
            // The catalog reads this snapshot; a change after it refreshes below.
            _continueStale = false;
            HomeSession.Catalog = await WindowsCore.LoadHomeCatalogAsync(AppServices.WatchProgress.All);
            HomeSession.SelectedCollectionId = "all";
            HomeSession.CollectionItems = await WindowsCore.LoadCollectionAsync("all");
            ShowContent();
        }
        catch (Exception failure)
        {
            ErrorMessageText.Text = failure.Message;
            SwitchState(ErrorState);
            return;
        }
        if (_continueStale) await RefreshContinueWatchingAsync();
    }

    private async void Retry_Click(object sender, RoutedEventArgs e)
    {
        HomeSession.Catalog = null;
        await EnsureLoadedAsync();
    }

    private void SwitchState(UIElement visible)
    {
        LoadingState.Visibility = visible == LoadingState ? Visibility.Visible : Visibility.Collapsed;
        MissingKeyState.Visibility = visible == MissingKeyState ? Visibility.Visible : Visibility.Collapsed;
        ErrorState.Visibility = visible == ErrorState ? Visibility.Visible : Visibility.Collapsed;
        ContentScroll.Visibility = visible == ContentScroll ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ShowContent()
    {
        SwitchState(ContentScroll);
        BuildCollectionChips(HomeSession.Catalog!);
        ApplyAudienceContent(resetHero: true);
    }

    /// <summary>
    /// Binds every shelf, the collection grid, and the hero pager through the
    /// Young Audience filter. Re-run when the preference or a certification
    /// decision changes; a changed visible-hero set resets the pager.
    /// </summary>
    private void ApplyAudienceContent(bool resetHero)
    {
        if (HomeSession.Catalog is not { } catalog || ContentScroll.Visibility != Visibility.Visible) return;
        var filter = AppServices.YoungAudience;

        BindShelf(TrendingShelf, TrendingRepeater, filter.Visible(catalog.Trending));
        BindShelf(PopularMoviesShelf, PopularMoviesRepeater, filter.Visible(catalog.PopularMovies));
        BindShelf(PopularShowsShelf, PopularShowsRepeater, filter.Visible(catalog.PopularShows));
        BindShelf(TopRatedShelf, TopRatedRepeater, filter.Visible(catalog.TopRated));
        CollectionRepeater.ItemsSource = filter.Visible(HomeSession.CollectionItems).Take(12).ToList();

        var newHero = VisibleHeroScenes(catalog);
        var heroChanged = resetHero
            || !newHero.Select(scene => scene.Detail.Ref.Id).SequenceEqual(_visibleHeroScenes.Select(scene => scene.Detail.Ref.Id));
        _visibleHeroScenes = newHero;
        if (heroChanged)
        {
            _heroIndex = 0;
            BuildHeroDots();
            ApplyHeroScene();
            StartHeroTimer();
        }

        UpdateAudienceVerifying();
        _ = VerifyAudienceAsync();
    }

    private void UpdateAudienceVerifying()
    {
        var verifying = AppServices.YoungAudience.IsVerifying(AudienceRefs());
        AudienceVerifyingText.Text = Loc.Get("Audience_Verifying");
        AudienceVerifyingRow.Visibility = verifying ? Visibility.Visible : Visibility.Collapsed;
    }

    private static IEnumerable<MediaRef> AudienceRefs()
    {
        if (HomeSession.Catalog is not { } catalog) return [];
        return catalog.HeroScenes.Select(scene => scene.Detail.Ref)
            .Concat(catalog.Trending.Select(item => item.Ref))
            .Concat(catalog.PopularMovies.Select(item => item.Ref))
            .Concat(catalog.PopularShows.Select(item => item.Ref))
            .Concat(catalog.TopRated.Select(item => item.Ref))
            .Concat(HomeSession.CollectionItems.Select(item => item.Ref));
    }

    private async Task VerifyAudienceAsync()
    {
        var filter = AppServices.YoungAudience;
        if (!filter.IsEnabled) return;
        var refs = AudienceRefs().ToList();
        if (refs.Count > 0) await filter.VerifyAsync(refs);
    }

    private static void BindShelf(UIElement shelf, ItemsRepeater repeater, List<MediaItem> items)
    {
        shelf.Visibility = items.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        repeater.ItemsSource = items;
    }

    // ------------------------------------------------------------------
    // Hero rotation
    // ------------------------------------------------------------------

    /// <summary>Hero scenes the audience filter permits — the pager's source.</summary>
    private List<HeroScene> _visibleHeroScenes = [];

    private List<HeroScene> HeroScenes => _visibleHeroScenes;

    private static List<HeroScene> VisibleHeroScenes(HomeCatalog catalog) =>
        catalog.HeroScenes.Where(scene => AppServices.YoungAudience.Allows(scene.Detail.Ref)).ToList();

    private void StartHeroTimer()
    {
        if (_heroTimer is not null || HeroScenes.Count <= 1) return;
        _heroTimer = DispatcherQueue.CreateTimer();
        _heroTimer.Interval = TimeSpan.FromSeconds(HeroSceneSeconds);
        _heroTimer.Tick += (_, _) =>
        {
            if (_heroHovered || HeroScenes.Count <= 1) return;
            _heroIndex = (_heroIndex + 1) % HeroScenes.Count;
            ApplyHeroScene();
        };
        _heroTimer.Start();
    }

    private void Hero_PointerEntered(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e) =>
        _heroHovered = true;

    private void Hero_PointerExited(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e) =>
        _heroHovered = false;

    private HeroScene? CurrentHero =>
        HeroScenes.Count > 0 ? HeroScenes[Math.Clamp(_heroIndex, 0, HeroScenes.Count - 1)] : null;

    private void ApplyHeroScene()
    {
        _heroTrailer = null;
        _trailerUnavailable = false;
        HeroTrailerLabel.Text = Loc.Get("Detail_WatchTrailer");
        HeroTrailerButton.IsEnabled = true;

        var scene = CurrentHero;
        if (scene is null) return;
        var detail = scene.Detail;

        HeroBackdrop.Source = Uri.TryCreate(detail.BackdropUrl, UriKind.Absolute, out var uri)
            ? new BitmapImage(uri)
            : null;
        HeroTitleText.Text = detail.Title.ToUpperInvariant();

        BuildHeroMeta(detail);

        var inLibrary = detail.Ref.MediaType == "movie"
            ? AppServices.Library.MovieByTmdbId(detail.Ref.Id) is not null
            : AppServices.Library.ShowByTmdbId(detail.Ref.Id) is not null;
        HeroPlayButton.Visibility = inLibrary ? Visibility.Visible : Visibility.Collapsed;
        ApplyContinueState(scene);

        UpdateHeroDots();
    }

    /// <summary>The kicker, the time left, and Play or Resume: what follows watch progress.</summary>
    private void ApplyContinueState(HeroScene scene)
    {
        ContinueRow.Visibility = scene.IsContinueWatching ? Visibility.Visible : Visibility.Collapsed;
        RemainingText.Text = scene.RemainingText?.ToUpperInvariant() ?? "";
        RemainingText.Visibility = string.IsNullOrEmpty(scene.RemainingText)
            ? Visibility.Collapsed
            : Visibility.Visible;
        HeroPlayLabel.Text = Loc.Get(scene.IsContinueWatching ? "Detail_ResumePlayback" : "Detail_Play");
    }

    private void BuildHeroMeta(MediaDetail detail)
    {
        HeroMetaRow.Children.Clear();
        void AddDivider()
        {
            if (HeroMetaRow.Children.Count == 0) return;
            HeroMetaRow.Children.Add(new Rectangle
            {
                Width = 1,
                Height = 14,
                Fill = (Brush)Application.Current.Resources["EdendaleOutlineBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            });
        }
        void AddText(string text)
        {
            AddDivider();
            HeroMetaRow.Children.Add(new TextBlock
            {
                Text = text,
                FontFamily = (FontFamily)Application.Current.Resources["TextFontFamily"],
                FontSize = 16,
                Foreground = (Brush)Application.Current.Resources["EdendaleTextSecondaryBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            });
        }

        if (detail.Year is int year) AddText(year.ToString());
        if (!string.IsNullOrEmpty(detail.Attribution)) AddText(detail.Attribution!);
        if (detail.Genres.Count > 0) AddText(string.Join(" / ", detail.Genres.Take(2)));
    }

    private void BuildHeroDots()
    {
        HeroDots.Children.Clear();
        for (var index = 0; index < HeroScenes.Count; index++)
        {
            var sceneIndex = index;
            var dot = new Button
            {
                Style = (Style)Application.Current.Resources["CardButtonStyle"],
                Padding = new Thickness(4),
                Content = new Ellipse
                {
                    Width = 8,
                    Height = 8,
                    Fill = (Brush)Application.Current.Resources["EdendaleOutlineBrush"],
                },
            };
            dot.Click += (_, _) =>
            {
                _heroIndex = sceneIndex;
                ApplyHeroScene();
            };
            HeroDots.Children.Add(dot);
        }
        HeroDots.Visibility = HeroScenes.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        UpdateHeroDots();
    }

    private void UpdateHeroDots()
    {
        for (var index = 0; index < HeroDots.Children.Count; index++)
        {
            if ((HeroDots.Children[index] as Button)?.Content is Ellipse ellipse)
            {
                ellipse.Fill = (Brush)Application.Current.Resources[
                    index == _heroIndex ? "EdendaleGoldBrush" : "EdendaleOutlineBrush"];
            }
        }
    }

    // ------------------------------------------------------------------
    // Continue Watching
    // ------------------------------------------------------------------

    /// <summary>
    /// Watch progress changed: playback here, or a merge from another device
    /// (sync runs at launch, on return to the foreground, every 5 minutes,
    /// and after playback). Refreshes once the changes settle.
    /// </summary>
    private void ScheduleContinueRefresh()
    {
        _continueStale = true;
        if (_continueTimer is null)
        {
            _continueTimer = DispatcherQueue.CreateTimer();
            _continueTimer.Interval = TimeSpan.FromSeconds(1);
            _continueTimer.IsRepeating = false;
            _continueTimer.Tick += async (_, _) => await RefreshContinueWatchingAsync();
        }
        _continueTimer.Stop();
        _continueTimer.Start();
    }

    /// <summary>
    /// Points Continue Watching at the newest unfinished title and its latest
    /// position without reloading the shelves: an unchanged title reuses its
    /// details, so a new position costs no request. The pager stays on the
    /// title it shows.
    /// </summary>
    private async Task RefreshContinueWatchingAsync()
    {
        if (HomeSession.Catalog is not { } catalog) return;
        _continueStale = false;
        var request = ++_continueRequest;
        HeroScene? scene;
        try
        {
            var known = catalog.HeroScenes.FirstOrDefault(item => item.IsContinueWatching)?.Detail;
            scene = await WindowsCore.LoadContinueSceneAsync(AppServices.WatchProgress.All, known);
        }
        catch
        {
            // The scenes on screen stay; the next change or visit tries again.
            _continueStale = true;
            return;
        }
        if (request != _continueRequest || !ReferenceEquals(catalog, HomeSession.Catalog)) return;

        var scenes = HomeHero.WithContinueScene(catalog.HeroScenes, scene);
        var titlesChanged = !HomeHero.SameTitles(catalog.HeroScenes, scenes);
        catalog.HeroScenes = scenes;
        // Until the content shows, ShowContent binds the new scenes itself.
        if (ContentScroll.Visibility != Visibility.Visible) return;

        var shown = CurrentHero;
        var previous = _visibleHeroScenes;
        _visibleHeroScenes = VisibleHeroScenes(catalog);
        _heroIndex = HomeHero.StableIndex(previous, _heroIndex, _visibleHeroScenes);
        if (!HomeHero.SameTitles(previous, _visibleHeroScenes)) BuildHeroDots();
        if (CurrentHero is { } current && shown is not null && HomeHero.SameTitle(current.Detail.Ref, shown.Detail.Ref))
        {
            // The same title: its progress changes, its backdrop and trailer stay.
            ApplyContinueState(current);
            UpdateHeroDots();
        }
        else
        {
            ApplyHeroScene();
        }
        if (IsLoaded) StartHeroTimer();

        // A new title waits for the audience filter like the rest of Home.
        if (titlesChanged)
        {
            UpdateAudienceVerifying();
            _ = VerifyAudienceAsync();
        }
    }

    // ------------------------------------------------------------------
    // Hero actions
    // ------------------------------------------------------------------

    private void HeroPlay_Click(object sender, RoutedEventArgs e)
    {
        var scene = CurrentHero;
        if (scene is null) return;
        if (scene.Detail.Ref.MediaType == "movie"
            && AppServices.Library.MovieByTmdbId(scene.Detail.Ref.Id) is { } movie)
        {
            AppServices.Player.Play(movie);
        }
        else
        {
            OpenDetails(scene.Detail.Ref);
        }
    }

    private void HeroDetails_Click(object sender, RoutedEventArgs e)
    {
        if (CurrentHero is { } scene) OpenDetails(scene.Detail.Ref);
    }

    private async void HeroTrailer_Click(object sender, RoutedEventArgs e)
    {
        var scene = CurrentHero;
        if (scene is null || _trailerUnavailable) return;

        if (_heroTrailer is null)
        {
            try
            {
                _heroTrailer = await WindowsCore.LoadBestTrailerAsync(
                    scene.Detail.Ref.Id, scene.Detail.Ref.MediaType);
            }
            catch
            {
                _heroTrailer = null;
            }
            // A progress refresh replaces the scene but keeps the title.
            if (CurrentHero is not { } shown || !HomeHero.SameTitle(shown.Detail.Ref, scene.Detail.Ref)) return;
        }

        if (_heroTrailer is null)
        {
            _trailerUnavailable = true;
            HeroTrailerLabel.Text = Loc.Get("Detail_NoTrailer");
            HeroTrailerButton.IsEnabled = false;
            return;
        }

        // User-initiated only: hand the trailer to the system browser rather
        // than embedding it, so the app itself makes no call to YouTube.
        await global::Windows.System.Launcher.LaunchUriAsync(
            new Uri($"https://www.youtube.com/watch?v={_heroTrailer.Key}"));
    }

    // ------------------------------------------------------------------
    // Shelves & collections
    // ------------------------------------------------------------------

    private void ShelfItem_Click(object sender, RoutedEventArgs e)
    {
        // Tag, not DataContext: ItemsRepeater leaves DataContext null on
        // x:Bind templates, so the templates bind the item into Tag.
        if ((sender as FrameworkElement)?.Tag is MediaItem item)
        {
            OpenDetails(item.Ref);
        }
    }

    private static void OpenDetails(MediaRef reference) =>
        NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(Ref: reference));

    private void BuildCollectionChips(HomeCatalog catalog)
    {
        CollectionChips.Children.Clear();
        foreach (var collection in catalog.Collections)
        {
            var chip = new ToggleButton
            {
                Style = (Style)Application.Current.Resources["ArchiveChipStyle"],
                Content = collection.Title,
                IsChecked = collection.Id == HomeSession.SelectedCollectionId,
                Tag = collection.Id,
            };
            chip.Click += async (_, _) => await SelectCollectionAsync(collection.Id);
            CollectionChips.Children.Add(chip);
        }
    }

    private async Task SelectCollectionAsync(string collectionId)
    {
        HomeSession.SelectedCollectionId = collectionId;
        foreach (var child in CollectionChips.Children.OfType<ToggleButton>())
        {
            child.IsChecked = (child.Tag as string) == collectionId;
        }

        CollectionRepeater.Opacity = 0.4;
        try
        {
            HomeSession.CollectionItems = await WindowsCore.LoadCollectionAsync(collectionId);
            CollectionRepeater.ItemsSource =
                AppServices.YoungAudience.Visible(HomeSession.CollectionItems).Take(12).ToList();
            UpdateAudienceVerifying();
            _ = VerifyAudienceAsync();
        }
        catch
        {
            // Keep the previous grid on a failed filter switch.
        }
        finally
        {
            CollectionRepeater.Opacity = 1;
        }
    }
}
