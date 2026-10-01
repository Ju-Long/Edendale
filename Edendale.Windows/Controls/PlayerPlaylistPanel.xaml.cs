using System;
using System.IO;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Edendale.Windows.Services;

namespace Edendale.Windows.Controls;

/// <summary>
/// The playlist beside the video (PlayerPlaylistPanel.swift, DIFF.md §3.10):
/// a show's episodes or the files in the same folder. The current or focused
/// row takes the white PlaylistActive fill with black text and a larger
/// title; a playing indicator marks the current file. Identified episodes
/// (and the current identified movie) show landscape artwork with the title
/// and play time stacked; unidentified files keep their file name.
/// </summary>
public sealed partial class PlayerPlaylistPanel : UserControl
{
    private const double TitleSize = 15;
    private const double ActiveTitleSize = 18;

    public event RoutedEventHandler? CloseRequested;
    public event EventHandler<PlaybackRequest>? PlayRequested;

    private Button? _currentRow;

    public PlayerPlaylistPanel()
    {
        this.InitializeComponent();
    }

    /// <summary>Moves keyboard focus to the current file's row when the panel opens.</summary>
    public void FocusCurrent()
    {
        _currentRow?.Focus(FocusState.Programmatic);
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        CloseRequested?.Invoke(this, new RoutedEventArgs());
    }

    public void Load(PlaybackRequest request)
    {
        ItemsPanel.Children.Clear();
        _currentRow = null;

        bool isEpisode = request.MediaType == "episode" || request.EpisodeNumber.HasValue;

        if (isEpisode)
        {
            var episode = AppServices.Library.Shows
                .SelectMany(s => s.Episodes)
                .FirstOrDefault(e => e.FilePath.Equals(request.FilePath, StringComparison.OrdinalIgnoreCase));

            var show = episode != null ? AppServices.Library.ShowForEpisode(episode) : null;

            if (show != null)
            {
                HeaderText.Text = Loc.Get("Playlist_Episodes");
                PopulateShow(show, request.FilePath);
                ScrollToCurrent();
                return;
            }
        }

        HeaderText.Text = Loc.Get("Playlist_InThisFolder");
        PopulateFolder(request);
        ScrollToCurrent();
    }

    /// <summary>The panel opens scrolled to the file that is playing.</summary>
    private void ScrollToCurrent()
    {
        if (_currentRow is not { } row) return;
        DispatcherQueue.TryEnqueue(() => row.StartBringIntoView(new BringIntoViewOptions
        {
            VerticalAlignmentRatio = 0.3,
            AnimationDesired = false,
        }));
    }

    private void PopulateShow(LibraryShow show, string currentFilePath)
    {
        foreach (var season in show.AvailableSeasons)
        {
            var seasonStack = new StackPanel { Spacing = 6 };
            seasonStack.Children.Add(new TextBlock
            {
                Text = Loc.Format("Playlist_Season", season),
                Style = (Style)Application.Current.Resources["LabelCapsTextStyle"]
            });

            foreach (var episode in show.EpisodesFor(season))
            {
                var isCurrent = episode.FilePath.Equals(currentFilePath, StringComparison.OrdinalIgnoreCase);
                var identified = episode.TmdbId is not null;
                var detail = episode.RuntimeMinutes is int minutes && minutes > 0
                    ? $"{episode.EpisodeCode} · {Loc.Format("Playlist_Runtime", minutes)}"
                    : episode.EpisodeCode;
                seasonStack.Children.Add(CreateRow(
                    title: episode.DisplayTitle,
                    detail: detail,
                    artwork: identified ? episode.StillUrl ?? show.BackdropUrl : null,
                    showArtwork: identified,
                    isCurrent: isCurrent,
                    action: () =>
                    {
                        if (isCurrent) return;
                        PlayRequested?.Invoke(this, PlayerSession.RequestFor(show, episode));
                    }));
            }
            ItemsPanel.Children.Add(seasonStack);
        }
    }

    /// <summary>
    /// The videos beside the current file. A remote item's folder isn't
    /// listed again over the network: its siblings come from the library.
    /// </summary>
    private static List<string> FolderFiles(string path)
    {
        if (Core.SourceUrl.IsUrl(path))
        {
            var parent = Core.SourceUrl.Parent(path);
            var library = AppServices.Library;
            return library.Movies.Select(movie => movie.FilePath)
                .Concat(library.Shows.SelectMany(show => show.Episodes).Select(episode => episode.FilePath))
                .Where(file => Core.SourceUrl.Parent(file) == parent)
                .Distinct(StringComparer.Ordinal)
                .OrderBy(Core.SourceUrl.FileName, Core.NaturalStringComparer.Instance)
                .ToList();
        }
        var folder = Path.GetDirectoryName(path);
        if (string.IsNullOrEmpty(folder) || !Directory.Exists(folder)) return [];
        return Directory.EnumerateFiles(folder)
            .Where(LibraryService.IsSupportedVideoFile)
            .OrderBy(f => f)
            .ToList();
    }

    private void PopulateFolder(PlaybackRequest current)
    {
        var files = FolderFiles(current.FilePath);
        if (files.Count == 0) return;

        var stack = new StackPanel { Spacing = 6 };
        foreach (var file in files)
        {
            var isCurrent = file.Equals(current.FilePath, StringComparison.OrdinalIgnoreCase);

            // The current identified movie shows its artwork and play time;
            // sibling files keep the file-name fallback.
            var movie = isCurrent && current.MediaType == "movie" && current.TmdbId is int id
                ? AppServices.Library.MovieByTmdbId(id)
                : null;
            string? detail = null;
            if (movie is not null)
            {
                var parts = new List<string>();
                if (movie.Year is int year) parts.Add(year.ToString(System.Globalization.CultureInfo.CurrentCulture));
                if (movie.RuntimeMinutes is int minutes && minutes > 0) parts.Add(Loc.Format("Playlist_Runtime", minutes));
                detail = parts.Count > 0 ? string.Join(" · ", parts) : null;
            }

            stack.Children.Add(CreateRow(
                title: movie?.Title ?? Core.SourceUrl.FileName(file),
                detail: detail,
                artwork: movie?.BackdropUrl ?? movie?.PosterUrl,
                showArtwork: movie is not null,
                isCurrent: isCurrent,
                action: () =>
                {
                    if (isCurrent) return;
                    PlayRequested?.Invoke(this, new PlaybackRequest
                    {
                        FilePath = file,
                        Title = Core.SourceUrl.FileName(file),
                        Subtitle = null,
                        TmdbId = null,
                        MediaType = "movie",
                        ShowTmdbId = null,
                        SeasonNumber = null,
                        EpisodeNumber = null
                    });
                }));
        }
        ItemsPanel.Children.Add(stack);
    }

    private Button CreateRow(string title, string? detail, string? artwork, bool showArtwork, bool isCurrent, Action action)
    {
        var resources = Application.Current.Resources;
        var surface = new Border
        {
            Padding = new Thickness(10),
            CornerRadius = new CornerRadius(8),
        };

        var grid = new Grid { ColumnSpacing = 12 };
        if (showArtwork)
        {
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        }
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var column = 0;
        if (showArtwork)
        {
            var frame = new Grid
            {
                Width = 112,
                Height = 63,
                CornerRadius = new CornerRadius(4),
                Background = (Brush)resources["EdendaleSurfaceHighBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            };
            frame.Children.Add(new SvgIcon
            {
                UriSource = new Uri("ms-appx:///Assets/Icons/tv.svg"),
                Width = 22,
                Height = 22,
                Foreground = (Brush)resources["EdendaleOutlineBrush"],
            });
            if (Uri.TryCreate(artwork, UriKind.Absolute, out var uri))
            {
                frame.Children.Add(new Image { Source = new BitmapImage(uri), Stretch = Stretch.UniformToFill });
            }
            grid.Children.Add(frame);
            Grid.SetColumn(frame, column++);
        }

        var text = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        var titleText = new TextBlock
        {
            Text = title,
            FontFamily = (FontFamily)resources["TextFontFamily"],
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextWrapping = TextWrapping.NoWrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
        };
        text.Children.Add(titleText);
        TextBlock? detailText = null;
        if (!string.IsNullOrEmpty(detail))
        {
            detailText = new TextBlock
            {
                Text = detail,
                Style = (Style)resources["BodySMTextStyle"],
                TextWrapping = TextWrapping.NoWrap,
                TextTrimming = TextTrimming.CharacterEllipsis,
            };
            text.Children.Add(detailText);
        }
        grid.Children.Add(text);
        Grid.SetColumn(text, column++);

        SvgIcon? indicator = null;
        if (isCurrent)
        {
            indicator = new SvgIcon
            {
                UriSource = new Uri("ms-appx:///Assets/Icons/play.svg"),
                Width = 12,
                Height = 12,
                VerticalAlignment = VerticalAlignment.Center,
            };
            grid.Children.Add(indicator);
            Grid.SetColumn(indicator, column);
        }

        surface.Child = grid;
        var button = new Button
        {
            Style = (Style)resources["CardButtonStyle"],
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Content = surface,
        };
        AutomationProperties.SetName(button, string.Join(", ", new[] { title, detail, isCurrent ? Loc.Get("Playlist_NowPlaying") : null }
            .Where(part => !string.IsNullOrEmpty(part))));

        void SetActive(bool active)
        {
            surface.Background = active
                ? (Brush)resources["EdendalePlaylistActiveBackgroundBrush"]
                : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
            var primary = active ? (Brush)resources["EdendalePlaylistActiveTextBrush"] : (Brush)resources["EdendaleTextPrimaryBrush"];
            titleText.Foreground = primary;
            titleText.FontSize = active ? ActiveTitleSize : TitleSize;
            if (detailText is not null)
            {
                detailText.Foreground = active ? primary : (Brush)resources["EdendaleTextSecondaryBrush"];
            }
            if (indicator is not null)
            {
                indicator.Foreground = active ? primary : (Brush)resources["EdendaleGoldBrush"];
            }
        }

        SetActive(isCurrent);
        button.GotFocus += (_, _) => SetActive(true);
        button.LostFocus += (_, _) => SetActive(isCurrent);
        button.Click += (s, e) => action();
        if (isCurrent) _currentRow = button;
        return button;
    }
}
