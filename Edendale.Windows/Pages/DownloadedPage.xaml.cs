using System.IO;
using System.Linq;
using System.Collections.Generic;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
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
}

/// <summary>
/// The local library (DownloadedView.swift): Continue Watching shelf,
/// movie/show poster grids, linked sources, and the silent-library
/// empty state.
/// </summary>
public sealed partial class DownloadedPage : Page
{
    private bool _rescannedThisVisit;

    public DownloadedPage()
    {
        InitializeComponent();
        AppServices.Library.Changed += (_, _) => DispatcherQueue.TryEnqueue(RefreshAll);
        AppServices.WatchProgress.Changed += (_, _) => DispatcherQueue.TryEnqueue(RefreshAll);
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
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
            StatusText.Text = "CATALOGUING NEW FILES";
        }
        else if (library.IsEnriching)
        {
            StatusRing.IsActive = true;
            StatusText.Text = "ENRICHING METADATA";
        }
        else
        {
            StatusRing.IsActive = false;
            StatusText.Text = "";
        }
        ErrorText.Text = library.ErrorMessage ?? "";
        ErrorText.Visibility = library.ErrorMessage is null ? Visibility.Collapsed : Visibility.Visible;

        // Continue Watching: newest first, joined to local files (Apple parity).
        var movies = library.Movies;
        var resumeEntries = new List<ResumeEntry>();
        foreach (var progress in AppServices.WatchProgress.InProgress)
        {
            if (resumeEntries.Count >= 12) break;
            if (progress.MediaType == "movie")
            {
                var movie = movies.FirstOrDefault(m => m.TmdbId == progress.TmdbId);
                if (movie is null) continue;
                resumeEntries.Add(new ResumeEntry
                {
                    Title = movie.Title,
                    Subtitle = $"{(int)(progress.Position * 100)}% watched",
                    ImageUrl = movie.BackdropUrl ?? movie.PosterUrl,
                    Progress = progress.Position,
                    PlaceholderAsset = "ms-appx:///Assets/Icons/film.svg",
                    Movie = movie,
                });
            }
            else
            {
                var episode = library.EpisodeByTmdbId(progress.TmdbId);
                if (episode is null) continue;
                var show = library.ShowForEpisode(episode);
                if (show is null) continue;
                resumeEntries.Add(new ResumeEntry
                {
                    Title = show.Name,
                    Subtitle = $"{episode.EpisodeCode} · {episode.DisplayTitle}",
                    ImageUrl = episode.StillUrl ?? show.BackdropUrl,
                    Progress = progress.Position,
                    PlaceholderAsset = "ms-appx:///Assets/Icons/tv.svg",
                    Show = show,
                    Episode = episode,
                });
            }
        }
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

        var shows = library.Shows;
        ShowsSection.Visibility = shows.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        ShowsRepeater.ItemsSource = shows;

        BuildSources(library);
    }

    private void BuildSources(LibraryService library)
    {
        var folders = library.Folders;
        SourcesSection.Visibility = folders.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        SourcesList.Children.Clear();

        foreach (var folder in folders)
        {
            var count = library.ItemCount(folder);
            var row = new Grid { Padding = new Thickness(0, 12, 0, 12), ColumnSpacing = 14 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var icon = new Controls.SvgIcon
            {
                UriSource = new Uri("ms-appx:///Assets/Icons/folder.svg"),
                Width = 18, Height = 18,
                Foreground = (Brush)Application.Current.Resources["EdendaleTextSecondaryBrush"],
                VerticalAlignment = VerticalAlignment.Center,
            };
            Grid.SetColumn(icon, 0);
            row.Children.Add(icon);

            var text = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(new TextBlock
            {
                Text = folder.Name,
                FontFamily = (FontFamily)Application.Current.Resources["TextFontFamily"],
                FontSize = 15,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = (Brush)Application.Current.Resources["EdendaleTextPrimaryBrush"],
            });
            text.Children.Add(new TextBlock
            {
                Text = count == 1 ? "1 item" : $"{count} items",
                Style = (Style)Application.Current.Resources["BodySMTextStyle"],
            });
            Grid.SetColumn(text, 1);
            row.Children.Add(text);

            var rescan = new Button
            {
                Style = (Style)Application.Current.Resources["ArchiveGhostButtonStyle"],
                Content = new Controls.SvgIcon { UriSource = new Uri("ms-appx:///Assets/Icons/arrow-rotate-right.svg"), Width = 14, Height = 14 },
                VerticalAlignment = VerticalAlignment.Center,
            };
            ToolTipService.SetToolTip(rescan, "Rescan");
            rescan.Click += async (_, _) => await AppServices.Library.RescanFolderAsync(folder);
            Grid.SetColumn(rescan, 2);
            row.Children.Add(rescan);

            var remove = new Button
            {
                Style = (Style)Application.Current.Resources["ArchiveGhostButtonStyle"],
                Content = new Controls.SvgIcon { UriSource = new Uri("ms-appx:///Assets/Icons/trash-can.svg"), Width = 14, Height = 14 },
                VerticalAlignment = VerticalAlignment.Center,
            };
            ToolTipService.SetToolTip(remove, "Remove");
            remove.Click += (_, _) => AppServices.Library.RemoveFolder(folder);
            Grid.SetColumn(remove, 3);
            row.Children.Add(remove);

            var container = new StackPanel();
            container.Children.Add(row);
            container.Children.Add(new Microsoft.UI.Xaml.Shapes.Rectangle
            {
                Height = 1,
                Fill = (Brush)Application.Current.Resources["EdendaleHairlineBorderBrush"],
                HorizontalAlignment = HorizontalAlignment.Stretch,
            });
            SourcesList.Children.Add(container);
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private async void AddFolder_Click(object sender, RoutedEventArgs e)
    {
        var picker = new global::Windows.Storage.Pickers.FolderPicker();
        picker.FileTypeFilter.Add("*");
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindow);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
        var folder = await picker.PickSingleFolderAsync();
        if (folder is null) return;
        await AppServices.Library.ImportFolderAsync(folder.Path);
    }

    private async void AddNetworkSource_Click(object sender, RoutedEventArgs e)
    {
        var pathBox = new TextBox { PlaceholderText = @"\\SMB-SERVER\Share\Movies" };
        var usernameBox = new TextBox { PlaceholderText = "Username (optional)" };
        var passwordBox = new PasswordBox { PlaceholderText = "Password" };

        var dialog = new ContentDialog
        {
            Title = "Add Network Source",
            Content = new StackPanel
            {
                Spacing = 12,
                MinWidth = 400,
                Children =
                {
                    new TextBlock { Text = "Enter the UNC path to your media folder:" },
                    pathBox,
                    new TextBlock
                    {
                        Text = "If the share needs a sign-in, add it here. It is stored " +
                               "encrypted on this device and reused for rescans.",
                        Style = (Style)Application.Current.Resources["BodySMTextStyle"],
                    },
                    usernameBox,
                    passwordBox,
                }
            },
            PrimaryButtonText = "Add",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot,
        };

        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        var path = pathBox.Text.Trim();
        if (string.IsNullOrEmpty(path)) return;
        var username = usernameBox.Text.Trim();
        var password = passwordBox.Password;

        string? failure = null;
        try
        {
            // Authenticate the SMB session first so the reachability check
            // below runs as the entered user, not the guest fallback.
            var share = SmbCredentialsStore.ShareFromUncPath(path);
            if (share is not null && username.Length > 0)
            {
                await Task.Run(() => NetworkShare.Connect(share, username, password));
                var host = SmbCredentialsStore.HostFromUncPath(path)!;
                AppServices.SmbCredentials.Save(host, username, password);
            }

            if (await Task.Run(() => Directory.Exists(path)))
            {
                await AppServices.Library.ImportFolderAsync(path);
                return;
            }
            failure = $"Could not access the network path: {path}";
        }
        catch (Exception connectFailure)
        {
            failure = connectFailure.Message;
        }

        var errorDialog = new ContentDialog
        {
            Title = "Network Source Error",
            Content = failure,
            CloseButtonText = "OK",
            XamlRoot = XamlRoot,
        };
        await errorDialog.ShowAsync();
    }

    private async void LearnSyncing_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            Title = "Private by Design",
            Content = "Your library index and watch progress stay on this device. " +
                      "The only network calls Edendale makes are to TMDB for artwork and metadata — " +
                      "nothing else is sent anywhere.",
            CloseButtonText = "OK",
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
