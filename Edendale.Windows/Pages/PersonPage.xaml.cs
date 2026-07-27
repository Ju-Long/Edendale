using Edendale.Windows.Helpers;
using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Edendale.Windows.Pages;

/// <summary>Navigation parameter for the person page.</summary>
public sealed record PersonNavArgs(int PersonId, string PersonName);

/// <summary>
/// A person page: portrait on the left, biography on the right, filmography
/// underneath. Reached from the search People cards and from the "Starring …"
/// chip; "Show in Search" goes the other way, into the existing filter mode,
/// which is unchanged.
/// </summary>
public sealed partial class PersonPage : Page
{
    private const int BiographyCollapsedLines = 8;

    private int _personId;
    private string _personName = "";
    private bool _biographyExpanded;
    private int _loadGeneration;

    public PersonPage()
    {
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (e.Parameter is not PersonNavArgs args) return;

        _personId = args.PersonId;
        _personName = args.PersonName;
        NameText.Text = _personName;
        await LoadAsync();
    }

    /// <summary>
    /// Biography and filmography are separate TMDB endpoints, so they run
    /// concurrently and the header renders as soon as it lands.
    /// </summary>
    private async Task LoadAsync()
    {
        var generation = ++_loadGeneration;
        SetStatus(null);
        FilmographySection.Visibility = Visibility.Collapsed;

        if (!WindowsCore.HasTmdbCredentials)
        {
            SetStatus("TMDB is unavailable — credentials are missing.");
            return;
        }

        LoadingRing.IsActive = true;
        var detailTask = WindowsCore.LoadPersonDetailAsync(_personId);
        var creditsTask = WindowsCore.LoadPersonFilmographyAsync(_personId);

        try
        {
            var detail = await detailTask;
            if (generation != _loadGeneration) return;
            ApplyDetail(detail);
        }
        catch (Exception failure)
        {
            if (generation != _loadGeneration) return;
            SetStatus(failure.Message);
        }

        try
        {
            var credits = await creditsTask;
            if (generation != _loadGeneration) return;
            FilmographyRepeater.ItemsSource = credits;
            FilmographySection.Visibility = credits.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            // A filmography arriving after a failed biography still gives the
            // page something to show.
            if (credits.Count > 0) SetStatus(null);
        }
        catch (Exception failure)
        {
            if (generation != _loadGeneration) return;
            if (StatusMessage.Visibility != Visibility.Visible) SetStatus(failure.Message);
        }
        finally
        {
            if (generation == _loadGeneration) LoadingRing.IsActive = false;
        }
    }

    private void ApplyDetail(PersonDetail detail)
    {
        _personName = detail.Name;
        NameText.Text = detail.Name;

        DepartmentText.Text = detail.KnownForDepartment?.ToUpperInvariant() ?? "";
        DepartmentText.Visibility = detail.HasDepartment ? Visibility.Visible : Visibility.Collapsed;

        VitalsText.Text = detail.Vitals ?? "";
        VitalsText.Visibility = detail.HasVitals ? Visibility.Visible : Visibility.Collapsed;

        PortraitImage.Source = Format.Image(detail.ProfileUrl);

        BiographyText.Text = detail.HasBiography
            ? detail.Biography!
            : "TMDB has no biography for this person yet.";

        // New text starts collapsed; the toggle reappears through
        // IsTextTrimmedChanged if this biography is long enough to clip.
        _biographyExpanded = false;
        BiographyText.MaxLines = BiographyCollapsedLines;
        BiographyToggleIcon.UriSource = new Uri("ms-appx:///Assets/Icons/chevron-down.svg");
        BiographyToggle.Visibility = Visibility.Collapsed;
    }

    private void Biography_IsTextTrimmedChanged(TextBlock sender, IsTextTrimmedChangedEventArgs args)
    {
        if (!_biographyExpanded)
        {
            BiographyToggle.Visibility = sender.IsTextTrimmed ? Visibility.Visible : Visibility.Collapsed;
        }
    }

    private void BiographyToggle_Click(object sender, RoutedEventArgs e)
    {
        _biographyExpanded = !_biographyExpanded;
        BiographyText.MaxLines = _biographyExpanded ? 0 : BiographyCollapsedLines;
        BiographyToggleIcon.UriSource = new Uri(_biographyExpanded
            ? "ms-appx:///Assets/Icons/chevron-up.svg"
            : "ms-appx:///Assets/Icons/chevron-down.svg");
    }

    private void SetStatus(string? message)
    {
        StatusMessage.Text = message ?? "";
        StatusMessage.Visibility = message is null ? Visibility.Collapsed : Visibility.Visible;
    }

    private void Credit_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is MediaItem item)
        {
            NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(Ref: item.Ref));
        }
    }

    /// <summary>Opens the Search tab filtered to this person (the chip mode).</summary>
    private void ShowInSearch_Click(object sender, RoutedEventArgs e)
    {
        NavigationService.Navigate(typeof(SearchPage), new SearchNavArgs(_personId, _personName));
    }

    private void Back_Click(object sender, RoutedEventArgs e) => NavigationService.GoBack();
}
