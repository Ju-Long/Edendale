using System;
using System.IO;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Documents;
using Edendale.Windows.Services;
using Edendale.Windows.Models;

namespace Edendale.Windows.Controls;

public sealed partial class PlayerPlaylistPanel : UserControl
{
    public event RoutedEventHandler? CloseRequested;
    public event EventHandler<PlaybackRequest>? PlayRequested;

    public PlayerPlaylistPanel()
    {
        this.InitializeComponent();
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        CloseRequested?.Invoke(this, new RoutedEventArgs());
    }

    public void Load(PlaybackRequest request)
    {
        ItemsPanel.Children.Clear();

        bool isEpisode = request.MediaType == "episode" || request.EpisodeNumber.HasValue;

        if (isEpisode)
        {
            var episode = AppServices.Library.Shows
                .SelectMany(s => s.Episodes)
                .FirstOrDefault(e => e.FilePath.Equals(request.FilePath, StringComparison.OrdinalIgnoreCase));

            var show = episode != null ? AppServices.Library.ShowForEpisode(episode) : null;

            if (show != null)
            {
                HeaderText.Text = "EPISODES";
                PopulateShow(show, request.FilePath);
                return;
            }
        }

        HeaderText.Text = "IN THIS FOLDER";
        PopulateFolder(request.FilePath);
    }

    private void PopulateShow(LibraryShow show, string currentFilePath)
    {
        foreach (var season in show.AvailableSeasons)
        {
            var seasonStack = new StackPanel { Spacing = 6 };
            seasonStack.Children.Add(new TextBlock
            {
                Text = $"SEASON {season}",
                Style = (Style)Application.Current.Resources["LabelCapsStyle"]
            });

            foreach (var episode in show.EpisodesFor(season))
            {
                var isCurrent = episode.FilePath.Equals(currentFilePath, StringComparison.OrdinalIgnoreCase);
                seasonStack.Children.Add(CreateRow(
                    title: episode.DisplayTitle,
                    detail: episode.EpisodeCode,
                    isCurrent: isCurrent,
                    action: () =>
                    {
                        if (isCurrent) return;
                        PlayRequested?.Invoke(this, new PlaybackRequest
                        {
                            FilePath = episode.FilePath,
                            Title = show.Name,
                            Subtitle = episode.DisplayTitle,
                            TmdbId = episode.TmdbId,
                            MediaType = "episode",
                            ShowTmdbId = show.TmdbId,
                            SeasonNumber = episode.Season,
                            EpisodeNumber = episode.Episode
                        });
                    }));
            }
            ItemsPanel.Children.Add(seasonStack);
        }
    }

    private void PopulateFolder(string currentFilePath)
    {
        var folder = Path.GetDirectoryName(currentFilePath);
        if (string.IsNullOrEmpty(folder) || !Directory.Exists(folder)) return;

        var files = Directory.EnumerateFiles(folder)
            .Where(LibraryService.IsSupportedVideoFile)
            .OrderBy(f => f)
            .ToList();

        var stack = new StackPanel { Spacing = 6 };
        foreach (var file in files)
        {
            var isCurrent = file.Equals(currentFilePath, StringComparison.OrdinalIgnoreCase);
            stack.Children.Add(CreateRow(
                title: Path.GetFileName(file),
                detail: null,
                isCurrent: isCurrent,
                action: () =>
                {
                    if (isCurrent) return;
                    PlayRequested?.Invoke(this, new PlaybackRequest
                    {
                        FilePath = file,
                        Title = Path.GetFileName(file),
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

    private UIElement CreateRow(string title, string? detail, bool isCurrent, Action action)
    {
        var button = new Button
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(12, 10, 12, 10),
            Background = isCurrent ? (Brush)Application.Current.Resources["EdendaleSurfaceBrush"] : new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            CornerRadius = new CornerRadius(12)
        };

        button.Style = (Style)Application.Current.Resources["ArchiveGhostButtonStyle"];

        var hStack = new Grid();
        hStack.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        hStack.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var vStack = new StackPanel { Spacing = 2 };

        var titleText = new TextBlock
        {
            Text = title,
            Style = (Style)Application.Current.Resources["BodyLGTextStyle"],
            Foreground = isCurrent ? (Brush)Application.Current.Resources["EdendaleGoldBrush"] : (Brush)Application.Current.Resources["EdendaleTextPrimaryBrush"],
            TextWrapping = TextWrapping.NoWrap,
            TextTrimming = TextTrimming.CharacterEllipsis
        };
        vStack.Children.Add(titleText);

        if (!string.IsNullOrEmpty(detail))
        {
            var detailText = new TextBlock
            {
                Text = detail,
                Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                Foreground = (Brush)Application.Current.Resources["EdendaleTextSecondaryBrush"]
            };
            vStack.Children.Add(detailText);
        }

        hStack.Children.Add(vStack);
        Grid.SetColumn(vStack, 0);

        if (isCurrent)
        {
            var icon = new SvgIcon
            {
                UriSource = new Uri("ms-appx:///Assets/Icons/play.svg"),
                Width = 11,
                Height = 11,
                Foreground = (Brush)Application.Current.Resources["EdendaleGoldBrush"],
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(10,0,0,0)
            };
            hStack.Children.Add(icon);
            Grid.SetColumn(icon, 1);
        }

        button.Content = hStack;
        button.Click += (s, e) => action();
        return button;
    }
}
