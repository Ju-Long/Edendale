using Edendale.Windows.Controls;
using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using Microsoft.UI.Xaml.Shapes;

namespace Edendale.Windows.Pages;

/// <summary>Navigation parameter: a TMDB browse item or a local library item.</summary>
public sealed record DetailNavArgs(
    MediaRef? Ref = null,
    Guid? LocalMovieId = null,
    Guid? LocalShowId = null);

/// <summary>
/// One detail page for everything (MediaDetailView.swift): TMDB browse
/// items and local library items. Local items with a TMDB match load the
/// full record; unmatched files render from their own metadata.
/// </summary>
public sealed partial class DetailPage : Page
{
    private DetailNavArgs _args = new();
    private MediaDetail? _detail;
    private LibraryMovie? _localMovie;
    private LibraryShow? _localShow;

    private bool _suppressRatingEvent;

    /// <summary>TMDB episode lists already fetched, keyed by season number.</summary>
    private readonly Dictionary<int, List<EpisodeDetail>> _tmdbEpisodes = [];
    private int? _selectedSeason;
    /// <summary>Guards a slow season response from painting over a later pick.</summary>
    private int _seasonRequestToken;

    public DetailPage()
    {
        InitializeComponent();
        AppServices.WatchProgress.Changed += (_, _) => DispatcherQueue.TryEnqueue(() =>
        {
            UpdateActions();
            RenderEpisodes();
            RenderTmdbEpisodeShelf();
        });
        AppServices.UserMedia.Changed += (_, _) =>
            DispatcherQueue.TryEnqueue(UpdateUserMediaActions);
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _args = e.Parameter as DetailNavArgs ?? new DetailNavArgs();
        ResetTrailer();
        ResolveLocalItems();
        RenderLocalFallback();
        await LoadDetailAsync();
    }

    private void Back_Click(object sender, RoutedEventArgs e) => NavigationService.GoBack();

    private void ResolveLocalItems()
    {
        var library = AppServices.Library;
        _localMovie = _args.LocalMovieId is Guid movieId
            ? library.Movies.FirstOrDefault(m => m.Id == movieId)
            : null;
        _localShow = _args.LocalShowId is Guid showId
            ? library.Shows.FirstOrDefault(s => s.Id == showId)
            : null;

        // A TMDB browse item resolves to its library match so its file is
        // playable from Movies & Shows too.
        if (_args.Ref is { } reference)
        {
            if (reference.MediaType == "movie") _localMovie ??= library.MovieByTmdbId(reference.Id);
            else _localShow ??= library.ShowByTmdbId(reference.Id);
        }
    }

    private MediaRef? ResolvedRef
    {
        get
        {
            if (_detail is not null) return _detail.Ref;
            if (_args.Ref is not null) return _args.Ref;
            if (_localMovie?.TmdbId is int movieId) return new MediaRef { Id = movieId, MediaType = "movie" };
            if (_localShow?.TmdbId is int showId) return new MediaRef { Id = showId, MediaType = "tv" };
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private void RenderLocalFallback()
    {
        if (_localMovie is { } movie)
        {
            TitleText.Text = movie.Title.ToUpperInvariant();
            SetBackdrop(movie.BackdropUrl ?? movie.PosterUrl);
            SetOverview(movie.Overview);
            BuildMetaRow(movie.Year, movie.RuntimeMinutes, null);
        }
        else if (_localShow is { } show)
        {
            TitleText.Text = show.Name.ToUpperInvariant();
            SetBackdrop(show.BackdropUrl ?? show.PosterUrl);
            SetOverview(show.Overview);
            BuildMetaRow(show.FirstAirYear, null, null);
        }
        UpdateActions();
        RenderEpisodes();
    }

    private async Task LoadDetailAsync()
    {
        var reference = ResolvedRef;
        if (reference is null || !WindowsCore.HasTmdbCredentials) return;
        try
        {
            _detail = await WindowsCore.LoadMediaDetailAsync(reference.Id, reference.MediaType);
        }
        catch
        {
            return; // The local fallback already rendered.
        }
        RenderDetail();
    }

    private void RenderDetail()
    {
        if (_detail is not { } detail) return;

        TitleText.Text = detail.Title.ToUpperInvariant();
        SetBackdrop(detail.BackdropUrl ?? _localMovie?.BackdropUrl ?? _localShow?.BackdropUrl);
        SetOverview(detail.Overview ?? _localMovie?.Overview ?? _localShow?.Overview);

        TaglineText.Text = detail.Tagline?.ToUpperInvariant() ?? "";
        TaglineText.Visibility = string.IsNullOrEmpty(detail.Tagline) ? Visibility.Collapsed : Visibility.Visible;

        if (detail.Genres.Count > 0)
        {
            GenreTagText.Text = detail.Genres[0].ToUpperInvariant();
            GenreTag.Visibility = Visibility.Visible;
        }

        BuildMetaRow(detail.Year, detail.RuntimeMinutes, detail.Attribution);
        BuildCast(detail.Cast);
        BuildConsensus(detail);
        UpdateActions();
        RenderEpisodes();
        RenderTmdbSeasons();
    }

    private void SetBackdrop(string? url) =>
        HeroBackdrop.Source = Uri.TryCreate(url, UriKind.Absolute, out var uri) ? new BitmapImage(uri) : null;

    private const int OverviewCollapsedLines = 6;
    private bool _overviewExpanded;

    private void SetOverview(string? overview)
    {
        OverviewText.Text = overview ?? "";
        ArchiveRecordCard.Visibility = string.IsNullOrWhiteSpace(overview) ? Visibility.Collapsed : Visibility.Visible;

        // New text starts collapsed; the toggle reappears through
        // IsTextTrimmedChanged if this overview is long enough to clip.
        _overviewExpanded = false;
        OverviewText.MaxLines = OverviewCollapsedLines;
        OverviewToggleIcon.UriSource = new Uri("ms-appx:///Assets/Icons/chevron-down.svg");
        OverviewToggle.Visibility = Visibility.Collapsed;
    }

    private void Overview_IsTextTrimmedChanged(TextBlock sender, IsTextTrimmedChangedEventArgs args)
    {
        if (!_overviewExpanded)
        {
            OverviewToggle.Visibility = sender.IsTextTrimmed ? Visibility.Visible : Visibility.Collapsed;
        }
    }

    private void OverviewToggle_Click(object sender, RoutedEventArgs e)
    {
        _overviewExpanded = !_overviewExpanded;
        OverviewText.MaxLines = _overviewExpanded ? 0 : OverviewCollapsedLines;
        OverviewToggleIcon.UriSource = new Uri(_overviewExpanded
            ? "ms-appx:///Assets/Icons/chevron-up.svg"
            : "ms-appx:///Assets/Icons/chevron-down.svg");
    }

    // ------------------------------------------------------------------
    // Header pieces
    // ------------------------------------------------------------------

    private void BuildMetaRow(int? year, int? runtimeMinutes, string? attribution)
    {
        MetaRow.Children.Clear();
        void AddDot()
        {
            if (MetaRow.Children.Count == 0) return;
            MetaRow.Children.Add(new Ellipse
            {
                Width = 3,
                Height = 3,
                Fill = (Brush)Application.Current.Resources["EdendaleOutlineBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            });
        }
        void AddText(string text, bool caps = false)
        {
            AddDot();
            var block = new TextBlock
            {
                Text = text,
                FontFamily = (FontFamily)Application.Current.Resources["TextFontFamily"],
                FontSize = caps ? 12 : 14,
                Foreground = (Brush)Application.Current.Resources["EdendaleTextSecondaryBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            };
            if (caps)
            {
                block.FontWeight = Microsoft.UI.Text.FontWeights.Bold;
                block.CharacterSpacing = 100;
            }
            MetaRow.Children.Add(block);
        }

        if (year is int y) AddText(y.ToString());
        if (runtimeMinutes is int minutes && minutes > 0) AddText($"{minutes} min");
        if (!string.IsNullOrEmpty(attribution)) AddText(attribution!.ToUpperInvariant(), caps: true);
    }

    private void BuildCast(List<CastMember> cast)
    {
        CastList.Children.Clear();
        CastSection.Visibility = cast.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        foreach (var member in cast)
        {
            var portrait = new Border
            {
                Width = 84,
                Height = 84,
                CornerRadius = new CornerRadius(8),
                Background = (Brush)Application.Current.Resources["EdendaleSurfaceBrush"],
            };
            if (Uri.TryCreate(member.ProfileUrl, UriKind.Absolute, out var uri))
            {
                portrait.Child = new Image
                {
                    Source = new BitmapImage(uri),
                    Stretch = Stretch.UniformToFill,
                };
            }
            else
            {
                portrait.Child = new SvgIcon
                {
                    UriSource = new Uri("ms-appx:///Assets/Icons/image-broken.svg"),
                    Width = 20, Height = 20,
                    Foreground = (Brush)Application.Current.Resources["EdendaleSurfaceHighBrush"],
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                };
            }

            var stack = new StackPanel { Spacing = 8, Width = 90 };
            stack.Children.Add(portrait);
            stack.Children.Add(new TextBlock
            {
                Text = member.Name,
                Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                TextAlignment = TextAlignment.Center,
                TextWrapping = TextWrapping.Wrap,
                MaxLines = 2,
            });

            var button = new Button
            {
                Style = (Style)Application.Current.Resources["CardButtonStyle"],
                Content = stack,
            };
            var personId = member.Id;
            var personName = member.Name;
            button.Click += (_, _) => (App.MainWindow as MainWindow)?.ShowSearch(
                new SearchNavArgs(PersonId: personId, PersonName: personName));
            CastList.Children.Add(button);
        }
    }

    private void BuildConsensus(MediaDetail detail)
    {
        ScoreTiles.Children.Clear();
        var hasScore = detail.Score is double score && score > 0;
        var hasCatalogue = detail.SeasonCount is not null && detail.EpisodeCount is not null;
        ConsensusSection.Visibility = hasScore || hasCatalogue || !string.IsNullOrEmpty(detail.Tagline)
            ? Visibility.Visible
            : Visibility.Collapsed;

        if (hasScore)
        {
            ScoreTiles.Children.Add(ScoreTile(
                Loc.Get("Detail_TmdbScore"),
                detail.Score!.Value.ToString("0.0"),
                detail.VoteCount is int votes ? Loc.Format("Detail_ScoreOutOfTen", votes) : "/ 10"));
        }
        if (hasCatalogue)
        {
            var seasons = detail.SeasonCount!.Value;
            var episodes = detail.EpisodeCount!.Value;
            ScoreTiles.Children.Add(ScoreTile(
                Loc.Get("Detail_Catalogue"),
                seasons.ToString(),
                $"{Loc.Plural("Plural_SeasonOne", "Plural_SeasonOther", seasons)} · {Loc.Plural("Plural_EpisodeOne", "Plural_EpisodeOther", episodes)}"));
        }

        TaglineQuoteText.Text = $"“{detail.Tagline}”";
        TaglineQuote.Visibility = string.IsNullOrEmpty(detail.Tagline) ? Visibility.Collapsed : Visibility.Visible;
    }

    private static StackPanel ScoreTile(string source, string value, string? suffix)
    {
        var tile = new StackPanel { Spacing = 6 };
        tile.Children.Add(new TextBlock
        {
            Text = source,
            Style = (Style)Application.Current.Resources["LabelCapsTextStyle"],
        });
        var valueRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 3 };
        valueRow.Children.Add(new TextBlock
        {
            Text = value,
            Style = (Style)Application.Current.Resources["HeadlineMDTextStyle"],
        });
        if (!string.IsNullOrEmpty(suffix))
        {
            valueRow.Children.Add(new TextBlock
            {
                Text = suffix,
                Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                VerticalAlignment = VerticalAlignment.Bottom,
                Margin = new Thickness(0, 0, 0, 4),
            });
        }
        tile.Children.Add(valueRow);
        return tile;
    }

    // ------------------------------------------------------------------
    // Actions & watch state
    // ------------------------------------------------------------------

    private (int Id, string Type)? WatchKey =>
        _localMovie?.TmdbId is int movieId ? (movieId, "movie")
        : _args.Ref is { MediaType: "movie" } reference ? (reference.Id, "movie")
        : null;

    private void UpdateActions()
    {
        // Shows play per-episode below; only a local movie file is playable here.
        PlayButton.Visibility = _localMovie is null ? Visibility.Collapsed : Visibility.Visible;
        if (_localMovie?.TmdbId is int tmdbId
            && AppServices.WatchProgress.Get(tmdbId, "movie") is { IsCompleted: false, Position: > 0.005 })
        {
            PlayLabel.Text = Loc.Get("Detail_ResumePlayback");
        }
        else
        {
            PlayLabel.Text = Loc.Get("Detail_Play");
        }

        if (WatchKey is { } key)
        {
            WatchedButton.Visibility = Visibility.Visible;
            var watched = AppServices.WatchProgress.IsWatched(key.Id, key.Type);
            WatchedLabel.Text = Loc.Get(watched ? "Detail_Watched" : "Detail_MarkWatched");
            WatchedIcon.UriSource = new Uri(watched ? "ms-appx:///Assets/Icons/eye-slash.svg" : "ms-appx:///Assets/Icons/eye.svg");
        }
        else
        {
            WatchedButton.Visibility = Visibility.Collapsed;
        }

        TrailerButton.Visibility =
            ResolvedRef is not null && WindowsCore.HasTmdbCredentials
                ? Visibility.Visible
                : Visibility.Collapsed;

        UpdateUserMediaActions();
    }

    // ------------------------------------------------------------------
    // Trailer (MoviesShowsPage hero pattern)
    // ------------------------------------------------------------------

    private TrailerVideo? _trailer;
    private bool _trailerUnavailable;

    private void ResetTrailer()
    {
        _trailer = null;
        _trailerUnavailable = false;
        TrailerLabel.Text = Loc.Get("Detail_WatchTrailer");
        TrailerButton.IsEnabled = true;
    }

    private async void Trailer_Click(object sender, RoutedEventArgs e)
    {
        if (ResolvedRef is not { } reference || _trailerUnavailable) return;

        if (_trailer is null)
        {
            try
            {
                _trailer = await WindowsCore.LoadBestTrailerAsync(reference.Id, reference.MediaType);
            }
            catch
            {
                _trailer = null;
            }
            if (ResolvedRef is not { } current
                || current.Id != reference.Id
                || current.MediaType != reference.MediaType)
            {
                return;
            }
        }

        if (_trailer is null)
        {
            _trailerUnavailable = true;
            TrailerLabel.Text = Loc.Get("Detail_NoTrailer");
            TrailerButton.IsEnabled = false;
            return;
        }

        // User-initiated only: hand the trailer to the system browser rather
        // than embedding it, so the app itself makes no call to YouTube.
        await global::Windows.System.Launcher.LaunchUriAsync(
            new Uri($"https://www.youtube.com/watch?v={_trailer.Key}"));
    }

    // ------------------------------------------------------------------
    // Favourite / watchlist / rating
    // ------------------------------------------------------------------

    /// <summary>Best display metadata to stamp into new user-media records.</summary>
    private (string? Title, string? PosterPath) DisplaySnapshot => (
        _detail?.Title ?? _localMovie?.Title ?? _localShow?.Name,
        _detail?.PosterPath);

    private void UpdateUserMediaActions()
    {
        // User-media state is keyed by TMDB id, so unmatched local files
        // cannot carry it (mirrors the watch-progress rule).
        if (ResolvedRef is not { } reference)
        {
            UserMediaRow.Visibility = Visibility.Collapsed;
            return;
        }
        UserMediaRow.Visibility = Visibility.Visible;

        var favourite = AppServices.UserMedia.IsFavourite(reference.Id, reference.MediaType);
        FavouriteIcon.UriSource = new Uri(favourite ? "ms-appx:///Assets/Icons/heart-fill.svg" : "ms-appx:///Assets/Icons/heart.svg");
        FavouriteLabel.Text = Loc.Get(favourite ? "Detail_Favourited" : "Detail_Favourite");

        var listed = AppServices.UserMedia.IsWatchlisted(reference.Id, reference.MediaType);
        WatchlistIcon.UriSource = new Uri(listed ? "ms-appx:///Assets/Icons/bookmark-slash.svg" : "ms-appx:///Assets/Icons/bookmark-plus.svg");
        WatchlistLabel.Text = Loc.Get(listed ? "Detail_InWatchlist" : "Detail_Watchlist");

        var rating = AppServices.UserMedia.RatingFor(reference.Id, reference.MediaType);
        _suppressRatingEvent = true;
        RatingInput.Value = rating is double value ? value / 2 : -1;
        _suppressRatingEvent = false;
        RatingLabel.Text = rating is double stored ? $"{stored:0.#} / 10" : Loc.Get("Detail_Rate");
    }

    private void ToggleFavourite_Click(object sender, RoutedEventArgs e)
    {
        if (ResolvedRef is not { } reference) return;
        var (title, posterPath) = DisplaySnapshot;
        AppServices.UserMedia.SetFavourite(
            reference.Id,
            reference.MediaType,
            !AppServices.UserMedia.IsFavourite(reference.Id, reference.MediaType),
            title,
            posterPath);
    }

    private void ToggleWatchlist_Click(object sender, RoutedEventArgs e)
    {
        if (ResolvedRef is not { } reference) return;
        var (title, posterPath) = DisplaySnapshot;
        AppServices.UserMedia.SetWatchlist(
            reference.Id,
            reference.MediaType,
            !AppServices.UserMedia.IsWatchlisted(reference.Id, reference.MediaType),
            title,
            posterPath);
    }

    private void Rating_ValueChanged(RatingControl sender, object args)
    {
        if (_suppressRatingEvent || ResolvedRef is not { } reference) return;
        var (title, posterPath) = DisplaySnapshot;
        // The control shows 5 stars; TMDB stores 0.5–10, so stars double.
        double? rating = sender.Value <= 0 ? null : sender.Value * 2;
        AppServices.UserMedia.SetRating(reference.Id, reference.MediaType, rating, title, posterPath);
    }

    private void Play_Click(object sender, RoutedEventArgs e)
    {
        if (_localMovie is { } movie) AppServices.Player.Play(movie);
    }

    private void ToggleWatched_Click(object sender, RoutedEventArgs e)
    {
        if (WatchKey is not { } key) return;
        if (AppServices.WatchProgress.IsWatched(key.Id, key.Type))
        {
            AppServices.WatchProgress.Remove(key.Id, key.Type);
        }
        else
        {
            AppServices.WatchProgress.MarkCompleted(key.Id, key.Type);
        }
    }

    // ------------------------------------------------------------------
    // Episodes (local shows)
    // ------------------------------------------------------------------

    private void RenderEpisodes()
    {
        if (_localShow is not { } show || show.Episodes.Count == 0)
        {
            EpisodesSection.Visibility = Visibility.Collapsed;
            return;
        }
        EpisodesSection.Visibility = Visibility.Visible;
        SeasonsList.Children.Clear();

        foreach (var season in show.AvailableSeasons)
        {
            SeasonsList.Children.Add(new TextBlock
            {
                Text = Loc.Format("Season_Number", season),
                Style = (Style)Application.Current.Resources["TitleLGTextStyle"],
                Margin = new Thickness(0, 12, 0, 0),
            });

            var shelf = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 20 };
            foreach (var episode in show.EpisodesFor(season))
            {
                shelf.Children.Add(EpisodeCard(show, episode));
            }
            SeasonsList.Children.Add(new ScrollViewer
            {
                HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden,
                HorizontalScrollMode = ScrollMode.Enabled,
                VerticalScrollMode = ScrollMode.Disabled,
                Content = shelf,
                Padding = new Thickness(0, 0, 0, 14),
            });
        }
    }

    private Button EpisodeCard(LibraryShow show, LibraryEpisode episode)
    {
        double progress = 0;
        var watched = false;
        if (episode.TmdbId is int tmdbId)
        {
            var record = AppServices.WatchProgress.Get(tmdbId, "episode");
            watched = record?.IsCompleted == true;
            if (record is { IsCompleted: false }) progress = record.Position;
        }

        var card = new LandscapeCard
        {
            Title = episode.DisplayTitle,
            Subtitle = episode.RuntimeMinutes is int minutes && minutes > 0
                ? $"{episode.EpisodeCode} · {minutes} min"
                : episode.EpisodeCode,
            ImageUrl = episode.StillUrl ?? show.BackdropUrl,
            CardWidth = 300,
            Progress = progress,
            IsWatched = watched,
            PlaceholderAsset = "ms-appx:///Assets/Icons/image-broken.svg",
        };

        var button = new Button
        {
            Style = (Style)Application.Current.Resources["CardButtonStyle"],
            Content = card,
        };
        button.Click += (_, _) => AppServices.Player.Play(show, episode);

        if (episode.TmdbId is int menuTmdbId)
        {
            var flyout = new MenuFlyout();
            var toggle = new MenuFlyoutItem
            {
                Text = Loc.Get(watched ? "Detail_MarkUnwatchedTooltip" : "Detail_MarkWatchedTooltip"),
            };
            toggle.Click += (_, _) =>
            {
                if (AppServices.WatchProgress.IsWatched(menuTmdbId, "episode"))
                {
                    AppServices.WatchProgress.Remove(menuTmdbId, "episode");
                }
                else
                {
                    AppServices.WatchProgress.MarkCompleted(menuTmdbId, "episode");
                }
            };
            flyout.Items.Add(toggle);
            button.ContextFlyout = flyout;
        }
        return button;
    }

    // ------------------------------------------------------------------
    // Episodes (TMDB-only shows)
    // ------------------------------------------------------------------

    /// <summary>
    /// Season picker for a show with no imported copy — the Windows
    /// counterpart of Apple's TMDBSeasonBrowser. Seasons come free with the
    /// detail response; each season's episode list is fetched on demand and
    /// cached, so opening a show never pays for seasons nobody looks at.
    /// </summary>
    private void RenderTmdbSeasons()
    {
        _tmdbEpisodes.Clear();
        _selectedSeason = null;

        var seasons = _detail?.Seasons ?? [];
        // An imported show renders its own playable shelves instead.
        if (_localShow is not null || seasons.Count == 0)
        {
            TmdbSeasonsSection.Visibility = Visibility.Collapsed;
            return;
        }

        TmdbSeasonsSection.Visibility = Visibility.Visible;
        SeasonChips.Children.Clear();
        foreach (var season in seasons)
        {
            var chip = new ToggleButton
            {
                Style = (Style)Application.Current.Resources["ArchiveChipStyle"],
                Content = season.DisplayTitle,
                Tag = season.SeasonNumber,
            };
            chip.Click += async (_, _) => await SelectSeasonAsync(season.SeasonNumber);
            SeasonChips.Children.Add(chip);
        }

        _ = SelectSeasonAsync(seasons[0].SeasonNumber);
    }

    private async Task SelectSeasonAsync(int seasonNumber)
    {
        if (_detail is not { } detail) return;

        _selectedSeason = seasonNumber;
        foreach (var child in SeasonChips.Children)
        {
            if (child is ToggleButton chip)
            {
                chip.IsChecked = chip.Tag is int number && number == seasonNumber;
            }
        }

        if (_tmdbEpisodes.ContainsKey(seasonNumber))
        {
            RenderTmdbEpisodeShelf();
            return;
        }

        var token = ++_seasonRequestToken;
        SetSeasonBusy(true);
        try
        {
            var season = await WindowsCore.LoadSeasonDetailAsync(detail.Ref.Id, seasonNumber);
            if (token != _seasonRequestToken) return;
            _tmdbEpisodes[seasonNumber] = season.Episodes;
            SetSeasonBusy(false);
            RenderTmdbEpisodeShelf();
        }
        catch (Exception error)
        {
            if (token != _seasonRequestToken) return;
            SetSeasonBusy(false);
            ShowSeasonMessage(error.Message);
        }
    }

    private void SetSeasonBusy(bool busy)
    {
        SeasonProgress.IsActive = busy;
        SeasonProgress.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        if (busy)
        {
            SeasonMessage.Visibility = Visibility.Collapsed;
            TmdbEpisodeShelf.Children.Clear();
        }
    }

    private void ShowSeasonMessage(string text)
    {
        TmdbEpisodeShelf.Children.Clear();
        SeasonMessage.Text = text;
        SeasonMessage.Visibility = Visibility.Visible;
    }

    private void RenderTmdbEpisodeShelf()
    {
        if (TmdbSeasonsSection.Visibility != Visibility.Visible) return;
        if (_selectedSeason is not int season) return;
        if (!_tmdbEpisodes.TryGetValue(season, out var episodes)) return;

        if (episodes.Count == 0)
        {
            ShowSeasonMessage(Loc.Get("Season_NoEpisodes"));
            return;
        }

        SeasonMessage.Visibility = Visibility.Collapsed;
        TmdbEpisodeShelf.Children.Clear();
        foreach (var episode in episodes)
        {
            TmdbEpisodeShelf.Children.Add(TmdbEpisodeCard(episode));
        }
    }

    /// <summary>
    /// TMDB episodes have no local file behind them, so the card toggles
    /// watch state rather than starting playback.
    /// </summary>
    private Button TmdbEpisodeCard(EpisodeDetail episode)
    {
        var watched = AppServices.WatchProgress.IsWatched(episode.Id, "episode");
        var card = new LandscapeCard
        {
            Title = episode.Name,
            Subtitle = TmdbEpisodeSubtitle(episode),
            ImageUrl = episode.StillUrl,
            CardWidth = 300,
            IsWatched = watched,
            PlaceholderAsset = "ms-appx:///Assets/Icons/image-broken.svg",
        };

        var button = new Button
        {
            Style = (Style)Application.Current.Resources["CardButtonStyle"],
            Content = card,
        };
        button.Click += (_, _) => ToggleTmdbEpisodeWatched(episode.Id);

        var flyout = new MenuFlyout();
        var toggle = new MenuFlyoutItem { Text = Loc.Get(watched ? "Detail_MarkUnwatchedTooltip" : "Detail_MarkWatchedTooltip") };
        toggle.Click += (_, _) => ToggleTmdbEpisodeWatched(episode.Id);
        flyout.Items.Add(toggle);
        button.ContextFlyout = flyout;
        return button;
    }

    private static void ToggleTmdbEpisodeWatched(int tmdbId)
    {
        if (AppServices.WatchProgress.IsWatched(tmdbId, "episode"))
        {
            AppServices.WatchProgress.Remove(tmdbId, "episode");
        }
        else
        {
            AppServices.WatchProgress.MarkCompleted(tmdbId, "episode");
        }
    }

    private static string TmdbEpisodeSubtitle(EpisodeDetail episode)
    {
        var code = episode is { SeasonNumber: int season, EpisodeNumber: int number }
            ? $"S{season:D2}E{number:D2}"
            : episode.Name;
        return episode.RuntimeMinutes is int minutes && minutes > 0
            ? $"{code} · {minutes} min"
            : code;
    }
}
