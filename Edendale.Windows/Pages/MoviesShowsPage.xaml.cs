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

    public MoviesShowsPage()
    {
        InitializeComponent();
        Loaded += async (_, _) => await EnsureLoadedAsync();
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
            return;
        }

        if (!WindowsCore.HasTmdbCredentials)
        {
            MissingKeyMessage.Text =
                "Run init.ps1 in the repository root to add TMDB credentials, then rebuild Edendale.";
            SwitchState(MissingKeyState);
            return;
        }

        SwitchState(LoadingState);
        try
        {
            HomeSession.Catalog = await WindowsCore.LoadHomeCatalogAsync(AppServices.WatchProgress.All);
            HomeSession.SelectedCollectionId = "all";
            HomeSession.CollectionItems = await WindowsCore.LoadCollectionAsync("all");
            ShowContent();
        }
        catch (Exception failure)
        {
            ErrorMessageText.Text = failure.Message;
            SwitchState(ErrorState);
        }
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
        var catalog = HomeSession.Catalog!;
        SwitchState(ContentScroll);

        BindShelf(TrendingShelf, TrendingRepeater, catalog.Trending);
        BindShelf(PopularMoviesShelf, PopularMoviesRepeater, catalog.PopularMovies);
        BindShelf(PopularShowsShelf, PopularShowsRepeater, catalog.PopularShows);
        BindShelf(TopRatedShelf, TopRatedRepeater, catalog.TopRated);

        BuildCollectionChips(catalog);
        CollectionRepeater.ItemsSource = HomeSession.CollectionItems.Take(12).ToList();

        _heroIndex = 0;
        BuildHeroDots();
        ApplyHeroScene();
        StartHeroTimer();
    }

    private static void BindShelf(UIElement shelf, ItemsRepeater repeater, List<MediaItem> items)
    {
        shelf.Visibility = items.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        repeater.ItemsSource = items;
    }

    // ------------------------------------------------------------------
    // Hero rotation
    // ------------------------------------------------------------------

    private List<HeroScene> HeroScenes => HomeSession.Catalog?.HeroScenes ?? [];

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
        HeroTrailerLabel.Text = "WATCH TRAILER";
        HeroTrailerButton.IsEnabled = true;

        var scene = CurrentHero;
        if (scene is null) return;
        var detail = scene.Detail;

        HeroBackdrop.Source = Uri.TryCreate(detail.BackdropUrl, UriKind.Absolute, out var uri)
            ? new BitmapImage(uri)
            : null;
        HeroTitleText.Text = detail.Title.ToUpperInvariant();

        ContinueRow.Visibility = scene.IsContinueWatching ? Visibility.Visible : Visibility.Collapsed;
        RemainingText.Text = scene.RemainingText?.ToUpperInvariant() ?? "";
        RemainingText.Visibility = string.IsNullOrEmpty(scene.RemainingText)
            ? Visibility.Collapsed
            : Visibility.Visible;

        BuildHeroMeta(detail);

        var inLibrary = detail.Ref.MediaType == "movie"
            ? AppServices.Library.MovieByTmdbId(detail.Ref.Id) is not null
            : AppServices.Library.ShowByTmdbId(detail.Ref.Id) is not null;
        HeroPlayButton.Visibility = inLibrary ? Visibility.Visible : Visibility.Collapsed;
        HeroPlayLabel.Text = scene.IsContinueWatching ? "RESUME PLAYBACK" : "PLAY";

        UpdateHeroDots();
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
            if (!ReferenceEquals(scene, CurrentHero)) return;
        }

        if (_heroTrailer is null)
        {
            _trailerUnavailable = true;
            HeroTrailerLabel.Text = "NO TRAILER";
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
            CollectionRepeater.ItemsSource = HomeSession.CollectionItems.Take(12).ToList();
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
