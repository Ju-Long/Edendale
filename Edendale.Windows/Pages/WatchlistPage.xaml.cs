using System.Linq;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Edendale.Windows.Pages;

/// <summary>
/// Saved-for-later movies and shows (WatchlistView.swift). The store is the
/// source of truth; the page re-renders on its changes and on the audience
/// filter's, and verifies its titles so hidden ones drop out live.
/// </summary>
public sealed partial class WatchlistPage : Page
{
    public WatchlistPage()
    {
        InitializeComponent();
        AppServices.Watchlist.Changed += OnDataChanged;
        AppServices.YoungAudience.Changed += OnDataChanged;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        Render();
        _ = VerifyAsync();
    }

    /// <summary>x:Bind hook: the poster placeholder for a saved title's type.</summary>
    public static string PlaceholderFor(string mediaType) =>
        mediaType == "tv" ? "ms-appx:///Assets/Icons/tv.svg" : "ms-appx:///Assets/Icons/film.svg";

    private void OnDataChanged(object? sender, System.EventArgs e) =>
        DispatcherQueue.TryEnqueue(() =>
        {
            Render();
            _ = VerifyAsync();
        });

    private async System.Threading.Tasks.Task VerifyAsync()
    {
        var refs = AppServices.Watchlist.Items.Select(item => item.Ref).ToList();
        if (refs.Count > 0) await AppServices.YoungAudience.VerifyAsync(refs);
    }

    private void Render()
    {
        var filter = AppServices.YoungAudience;
        var items = AppServices.Watchlist.Items;
        var visible = items.Where(item => filter.Allows(item.Ref)).ToList();

        var movies = visible.Where(item => item.MediaType == "movie").ToList();
        var shows = visible.Where(item => item.MediaType == "tv").ToList();

        MoviesSection.Visibility = movies.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        MoviesRepeater.ItemsSource = movies;
        ShowsSection.Visibility = shows.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        ShowsRepeater.ItemsSource = shows;

        // The tab hides once nothing is visible, but the filter can be
        // mid-verification (or catch the page briefly) — say what is happening.
        if (visible.Count == 0 && filter.IsVerifying(items.Select(item => item.Ref)))
        {
            VerifyRing.IsActive = true;
            AudienceStateText.Text = Loc.Get("Audience_Verifying");
            AudienceState.Visibility = Visibility.Visible;
        }
        else if (visible.Count == 0 && filter.IsEnabled && items.Count > 0)
        {
            VerifyRing.IsActive = false;
            AudienceStateText.Text = Loc.Get("Watchlist_NoYoungAudienceTitles");
            AudienceState.Visibility = Visibility.Visible;
        }
        else
        {
            VerifyRing.IsActive = false;
            AudienceState.Visibility = Visibility.Collapsed;
        }
    }

    private void Card_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is WatchlistRecord record)
        {
            NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(Ref: record.Ref));
        }
    }

    private void Remove_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is WatchlistRecord record)
        {
            AppServices.Watchlist.Remove(record);
        }
    }
}
