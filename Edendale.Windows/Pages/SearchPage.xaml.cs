using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using System.Text.RegularExpressions;

namespace Edendale.Windows.Pages;

/// <summary>Navigation parameter: person-filmography mode, or a pre-filled query (edendale://search).</summary>
public sealed record SearchNavArgs(int? PersonId = null, string? PersonName = null, string? Query = null);

/// <summary>A local library match shown above the TMDB results.</summary>
public sealed class LocalSearchResult
{
    public required string Title { get; init; }
    public string? Subtitle { get; init; }
    public string? ImageUrl { get; init; }
    public string PlaceholderAsset { get; init; } = "ms-appx:///Assets/Icons/film.svg";
    public LibraryMovie? Movie { get; init; }
    public LibraryShow? Show { get; init; }
}

/// <summary>
/// TMDB search plus local library matches, actor/actress results, a
/// release-date filter (SearchModel.swift's date selection, picker form),
/// and the person-filmography view cast taps and person results open.
/// The idle state mirrors the Apple placeholder ("The Index Awaits").
/// </summary>
public sealed partial class SearchPage : Page
{
    private const int DebounceMilliseconds = 350;

    private DispatcherQueueTimer? _debounce;
    private int _searchGeneration;

    // WinUI raises TextChanged asynchronously, so a bool flag around a
    // programmatic assignment is already reset by the time the handler
    // runs. Track the expected text instead and swallow that one event.
    private string? _pendingProgrammaticText;

    // Committed release-date range; either bound may be open-ended.
    private DateTimeOffset? _releaseFrom;
    private DateTimeOffset? _releaseTo;

    // Heatmap state: the year on screen, the range being picked (not yet
    // committed), and the first tap of a two-tap range selection.
    private int _heatmapYear;
    private DateTimeOffset? _pendingFrom;
    private DateTimeOffset? _pendingTo;
    private DateTimeOffset? _heatmapAnchor;
    private int _heatmapGeneration;
    private readonly Dictionary<int, Dictionary<string, int>> _heatmapCounts = [];
    private readonly Dictionary<int, ReleaseYearGrid> _heatmapGrids = [];

    // When set, the results list is a person's filmography ("actor details").
    private int? _activePersonId;
    private string _activePersonName = "";

    // Idle grid, cached for the session so re-entering the tab never refetches.
    private List<MediaItem> _trending = [];
    private bool _loadingTrending;

    // The scope the current query's keyword prefix asks for, and the query
    // with that prefix stripped (as parsed by the Windows domain layer, so clearing
    // the chip can never eat a colon that belongs to a title).
    private string _scope = "all";
    private string _scopeTerm = "";

    public SearchPage()
    {
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (e.Parameter is SearchNavArgs { PersonId: int personId } args)
        {
            await ShowFilmographyAsync(personId, args.PersonName ?? "");
        }
        else if (e.Parameter is SearchNavArgs { Query.Length: > 0 } queryArgs)
        {
            SetQueryText(queryArgs.Query);
            ClearPersonState();
            await SearchNowAsync();
        }
        else if (ResultsScroll.Visibility != Visibility.Visible)
        {
            ShowIdle();
        }
    }

    private bool HasDateFilter => _releaseFrom is not null || _releaseTo is not null;

    // ------------------------------------------------------------------
    // Query handling
    // ------------------------------------------------------------------

    private void SetQueryText(string text)
    {
        if (QueryBox.Text == text) return;
        _pendingProgrammaticText = text;
        QueryBox.Text = text;
    }

    private void QueryBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_pendingProgrammaticText is { } expected)
        {
            _pendingProgrammaticText = null;
            if (QueryBox.Text == expected) return;
        }

        if (string.IsNullOrWhiteSpace(QueryBox.Text))
        {
            // An emptied box keeps an active filmography (its chip clears it).
            if (_activePersonId is not null) return;
            if (!HasDateFilter)
            {
                _searchGeneration++;
                _debounce?.Stop();
                SearchRing.IsActive = false;
                _scope = "all";
                _scopeTerm = "";
                UpdateFilterChips();
                ShowIdle();
                return;
            }
        }
        else
        {
            // Typing a query means the user wants a text search, not the
            // filmography they arrived with — drop the active person.
            ClearPersonState();
        }

        if (_debounce is null)
        {
            _debounce = DispatcherQueue.CreateTimer();
            _debounce.Interval = TimeSpan.FromMilliseconds(DebounceMilliseconds);
            _debounce.IsRepeating = false;
            _debounce.Tick += async (_, _) => await SearchNowAsync();
        }
        _debounce.Stop();
        _debounce.Start();
    }

    private async void QueryBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != global::Windows.System.VirtualKey.Enter) return;
        e.Handled = true;
        _debounce?.Stop();
        await SearchNowAsync();
    }

    /// <summary>
    /// Nothing searched for: browse today's trending titles. The "index
    /// awaits" placeholder only shows when trending is unavailable (no
    /// credentials or a failed fetch).
    /// </summary>
    private void ShowIdle()
    {
        ResultsScroll.Visibility = Visibility.Collapsed;
        var hasTrending = _trending.Count > 0;
        TrendingScroll.Visibility = hasTrending ? Visibility.Visible : Visibility.Collapsed;
        IdleState.Visibility = hasTrending ? Visibility.Collapsed : Visibility.Visible;
        _ = LoadTrendingIfNeededAsync();
    }

    private void ShowResults()
    {
        IdleState.Visibility = Visibility.Collapsed;
        TrendingScroll.Visibility = Visibility.Collapsed;
        ResultsScroll.Visibility = Visibility.Visible;
    }

    /// <summary>Fills the idle grid, once per session (like HomeSession).</summary>
    private async Task LoadTrendingIfNeededAsync()
    {
        if (_trending.Count > 0 || _loadingTrending) return;
        if (!WindowsCore.HasTmdbCredentials) return;

        _loadingTrending = true;
        try
        {
            _trending = await WindowsCore.LoadTrendingAsync();
            TrendingRepeater.ItemsSource = _trending;
            // Only take the screen if the user has not started searching.
            if (_trending.Count > 0 && ResultsScroll.Visibility != Visibility.Visible)
            {
                IdleState.Visibility = Visibility.Collapsed;
                TrendingScroll.Visibility = Visibility.Visible;
            }
        }
        catch
        {
            // The idle grid is a nicety; a failure falls back to the
            // placeholder rather than shouting at the user.
        }
        finally
        {
            _loadingTrending = false;
        }
    }

    // ------------------------------------------------------------------
    // Release-date filter
    // ------------------------------------------------------------------

    /// <summary>The earliest year TMDB has releases for — Apple's `earliestYear`.</summary>
    private const int EarliestReleaseYear = 1874;

    private async void ApplyDateFilter_Click(object sender, RoutedEventArgs e)
    {
        DateFilterButton.Flyout.Hide();
        if (_pendingFrom is null || _pendingTo is null)
        {
            await ClearDateFilterAsync();
            return;
        }

        _releaseFrom = _pendingFrom;
        _releaseTo = _pendingTo;
        // A date search replaces any filmography that was showing.
        ClearPersonState();
        UpdateFilterChips();
        await SearchNowAsync();
    }

    private async void ClearDateFilter_Click(object sender, RoutedEventArgs e)
    {
        DateFilterButton.Flyout.Hide();
        await ClearDateFilterAsync();
    }

    private async Task ClearDateFilterAsync()
    {
        _pendingFrom = null;
        _pendingTo = null;
        _heatmapAnchor = null;
        if (!HasDateFilter) return;
        _releaseFrom = null;
        _releaseTo = null;
        UpdateFilterChips();

        if (_activePersonId is not null) return;
        if (QueryBox.Text.Trim().Length > 0) await SearchNowAsync();
        else ShowIdle();
    }

    // ------------------------------------------------------------------
    // Release heatmap (ReleaseHeatmapView.swift)
    // ------------------------------------------------------------------

    private void DateFlyout_Opening(object? sender, object e)
    {
        // Re-open on the committed range's year, so a range stays visible.
        if (_heatmapYear == 0)
        {
            _heatmapYear = _releaseFrom?.Year ?? DateTimeOffset.Now.Year;
        }
        _pendingFrom = _releaseFrom;
        _pendingTo = _releaseTo;
        _heatmapAnchor = null;
        _ = ShowHeatmapYearAsync(_heatmapYear);
    }

    private void PreviousYear_Click(object sender, RoutedEventArgs e)
    {
        if (_heatmapYear <= EarliestReleaseYear) return;
        _ = ShowHeatmapYearAsync(_heatmapYear - 1);
    }

    private void NextYear_Click(object sender, RoutedEventArgs e)
    {
        if (_heatmapYear >= DateTimeOffset.Now.Year) return;
        _ = ShowHeatmapYearAsync(_heatmapYear + 1);
    }

    /// <summary>
    /// Draws the grid immediately from the Windows calendar model, then fills in
    /// density once TMDB answers. Counts are cached per year for the session.
    /// </summary>
    private async Task ShowHeatmapYearAsync(int year)
    {
        _heatmapYear = year;
        HeatmapYearText.Text = year.ToString();
        PreviousYearButton.IsEnabled = year > EarliestReleaseYear;
        NextYearButton.IsEnabled = year < DateTimeOffset.Now.Year;
        UpdateHeatmapSummary();

        _heatmapCounts.TryGetValue(year, out var counts);
        BuildHeatmap(year, counts);

        if (counts is not null || !WindowsCore.HasTmdbCredentials) return;

        var generation = ++_heatmapGeneration;
        HeatmapRing.IsActive = true;
        try
        {
            var loaded = await WindowsCore.ReleaseCountsAsync(year);
            if (generation != _heatmapGeneration) return;
            _heatmapCounts[year] = loaded;
            BuildHeatmap(year, loaded);
        }
        catch (WindowsCoreException)
        {
            // Density is a nicety; the grid stays selectable without it.
        }
        finally
        {
            if (generation == _heatmapGeneration) HeatmapRing.IsActive = false;
        }
    }

    private void BuildHeatmap(int year, IReadOnlyDictionary<string, int>? counts)
    {
        if (!_heatmapGrids.TryGetValue(year, out var grid))
        {
            grid = WindowsCore.ReleaseYearGrid(year);
            _heatmapGrids[year] = grid;
        }

        var busiest = counts is { Count: > 0 } ? counts.Values.Max() : 0;
        HeatmapColumns.Children.Clear();

        foreach (var column in grid.Columns)
        {
            var stack = new StackPanel { Spacing = 3 };
            stack.Children.Add(new TextBlock
            {
                Text = column.MonthLabel ?? string.Empty,
                FontSize = 10,
                Height = 14,
                Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EdendaleTextSecondaryBrush"],
            });

            foreach (var slot in column.Slots)
            {
                if (slot is null)
                {
                    stack.Children.Add(new Border { Width = 11, Height = 11 });
                    continue;
                }

                var releases = counts is null ? 0 : counts.GetValueOrDefault(slot.DateKey);
                var cell = new Border
                {
                    Width = 11,
                    Height = 11,
                    CornerRadius = new CornerRadius(2),
                    Background = HeatBrush(releases, busiest),
                    Tag = slot.DateKey,
                };
                ToolTipService.SetToolTip(
                    cell,
                    $"{slot.Day} {MonthName(slot.Month)} {slot.Year} · " +
                    (releases == 1 ? "1 release" : $"{releases} releases"));
                cell.Tapped += HeatmapCell_Tapped;
                ApplySelectionOutline(cell, slot.DateKey);
                stack.Children.Add(cell);
            }

            HeatmapColumns.Children.Add(stack);
        }
    }

    /// <summary>
    /// Two taps commit a range: the first sets the anchor, the second closes
    /// it (in either direction). A third tap starts over.
    /// </summary>
    private void HeatmapCell_Tapped(object sender, TappedRoutedEventArgs e)
    {
        if (sender is not Border { Tag: string dateKey }) return;
        if (!DateTimeOffset.TryParse(dateKey, out var day)) return;

        if (_heatmapAnchor is null)
        {
            _heatmapAnchor = day;
            _pendingFrom = day;
            _pendingTo = day;
        }
        else
        {
            var anchor = _heatmapAnchor.Value;
            (_pendingFrom, _pendingTo) = day < anchor ? (day, anchor) : (anchor, day);
            _heatmapAnchor = null;
        }

        RefreshSelectionOutlines();
        UpdateHeatmapSummary();
    }

    private void RefreshSelectionOutlines()
    {
        foreach (var child in HeatmapColumns.Children)
        {
            if (child is not StackPanel column) continue;
            foreach (var cellChild in column.Children)
            {
                if (cellChild is Border { Tag: string key } cell) ApplySelectionOutline(cell, key);
            }
        }
    }

    private void ApplySelectionOutline(Border cell, string dateKey)
    {
        var selected = _pendingFrom is not null
            && _pendingTo is not null
            && string.CompareOrdinal(dateKey, _pendingFrom.Value.ToString("yyyy-MM-dd")) >= 0
            && string.CompareOrdinal(dateKey, _pendingTo.Value.ToString("yyyy-MM-dd")) <= 0;
        cell.BorderThickness = new Thickness(selected ? 1.5 : 0);
        cell.BorderBrush = selected
            ? (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EdendaleTextPrimaryBrush"]
            : null;
    }

    private void UpdateHeatmapSummary()
    {
        if (_pendingFrom is null || _pendingTo is null)
        {
            HeatmapSummary.Text = _heatmapAnchor is null
                ? "Pick a day, then a second day to close the range."
                : "Pick a second day to close the range.";
            return;
        }
        HeatmapSummary.Text = WindowsCore.SelectionSummary(
            _pendingFrom.Value.ToString("yyyy-MM-dd"),
            _pendingTo.Value.ToString("yyyy-MM-dd"));
    }

    /// <summary>Four buckets from the busiest day, matching the legend swatches.</summary>
    private static Microsoft.UI.Xaml.Media.Brush HeatBrush(int releases, int busiest)
    {
        var resources = Application.Current.Resources;
        if (releases <= 0 || busiest <= 0)
        {
            return (Microsoft.UI.Xaml.Media.Brush)resources["EdendaleSurfaceHighBrush"];
        }
        var share = (double)releases / busiest;
        var key = share switch
        {
            > 0.66 => "EdendaleGoldBrush",
            > 0.33 => "EdendaleHeatMidBrush",
            _ => "EdendaleHeatLowBrush",
        };
        return (Microsoft.UI.Xaml.Media.Brush)resources[key];
    }

    private static string MonthName(int month) => month switch
    {
        1 => "Jan", 2 => "Feb", 3 => "Mar", 4 => "Apr", 5 => "May", 6 => "Jun",
        7 => "Jul", 8 => "Aug", 9 => "Sep", 10 => "Oct", 11 => "Nov", _ => "Dec",
    };

    private bool HasScope => _scope != "all";

    private void UpdateFilterChips()
    {
        DateChip.Visibility = HasDateFilter ? Visibility.Visible : Visibility.Collapsed;
        if (HasDateFilter) DateChipText.Text = DateRangeLabel().ToUpperInvariant();

        ScopeChip.Visibility = HasScope ? Visibility.Visible : Visibility.Collapsed;
        if (HasScope) ScopeChipText.Text = ScopeLabel(_scope).ToUpperInvariant();

        PersonChip.Visibility = _activePersonId is null ? Visibility.Collapsed : Visibility.Visible;
        PersonChipText.Text = $"STARRING {_activePersonName.ToUpperInvariant()}";

        FilterChips.Visibility = HasDateFilter || HasScope || _activePersonId is not null
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private static string ScopeLabel(string scope) => scope switch
    {
        "people" => "People",
        "movies" => "Films",
        "shows" => "Series",
        _ => "All",
    };

    private static string ScopePromptMessage(string scope) => scope switch
    {
        "people" => "Type a name to find actors and actresses.",
        "movies" => "Type a title to search films only.",
        "shows" => "Type a title to search series only.",
        _ => "Type a title to search the archive.",
    };

    /// <summary>Strips the keyword prefix, keeping whatever was typed after it.</summary>
    private async void ClearScope_Click(object sender, RoutedEventArgs e)
    {
        if (!HasScope) return;
        _scope = "all";
        UpdateFilterChips();
        SetQueryText(_scopeTerm);
        _debounce?.Stop();
        if (_scopeTerm.Length > 0 || HasDateFilter) await SearchNowAsync();
        else ShowIdle();
    }

    /// <summary>
    /// A people-scoped query leads with People; everything else keeps titles
    /// first. The library section always stays on top.
    /// </summary>
    private void ApplySectionOrder(bool leadsWithPeople)
    {
        var sections = ResultSections.Children;
        var wantedIndex = sections.IndexOf(LibrarySection) + 1;
        var target = leadsWithPeople ? PeopleSection : TitlesSection;
        if (sections.IndexOf(target) == wantedIndex) return;

        sections.Remove(target);
        sections.Insert(wantedIndex, target);
    }

    private string DateRangeLabel()
    {
        var from = _releaseFrom?.ToString("d MMM yyyy");
        var to = _releaseTo?.ToString("d MMM yyyy");
        return (from, to) switch
        {
            (not null, not null) => from == to ? from : $"{from} – {to}",
            (not null, null) => $"From {from}",
            _ => $"Until {to}",
        };
    }

    /// <summary>"yyyy-MM-dd" bounds — TMDB's release-date format, compared lexicographically.</summary>
    private (string From, string To) DayBounds() => (
        _releaseFrom?.ToString("yyyy-MM-dd") ?? "0000-01-01",
        _releaseTo?.ToString("yyyy-MM-dd") ?? "9999-12-31");

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    private async Task SearchNowAsync()
    {
        var query = QueryBox.Text.Trim();
        if (query.Length == 0 && !HasDateFilter)
        {
            ShowIdle();
            return;
        }

        // A trailing year still narrows a text search when no explicit
        // range is committed ("alien 1979").
        int? yearFilter = null;
        if (!HasDateFilter)
        {
            var match = Regex.Match(query, @"^(.*?)\s+\b(19\d{2}|20\d{2})\b$");
            if (match.Success)
            {
                query = match.Groups[1].Value.Trim();
                yearFilter = int.Parse(match.Groups[2].Value);
            }
        }

        var generation = ++_searchGeneration;
        IndexHeader.Title = "The Index";
        BuildLocalResults(query, yearFilter);

        if (!WindowsCore.HasTmdbCredentials)
        {
            PeopleSection.Visibility = Visibility.Collapsed;
            SetIndexMessage(
                "Run init.ps1 in the repository root to add TMDB credentials, then rebuild " +
                "Edendale to search the full index.");
            ShowResults();
            return;
        }

        SearchRing.IsActive = true;
        ShowResults();
        try
        {
            List<MediaItem> items;
            List<PersonItem> people = [];
            if (query.Length == 0)
            {
                // Date-only search: browse the range itself (Apple's
                // discover-by-release-date results).
                var (from, to) = DayBounds();
                items = await WindowsCore.DiscoverReleasedAsync(from, to);
                ApplySectionOrder(leadsWithPeople: false);
            }
            else
            {
                // The Windows domain layer parses any "actors:"/"movies:"/"shows:"
                // prefix and dispatches the right endpoints.
                var scoped = await WindowsCore.SearchScopedAsync(query);
                if (generation != _searchGeneration) return;
                items = scoped.Titles;
                people = scoped.People;
                _scope = scoped.Scope;
                _scopeTerm = scoped.Term;
                UpdateFilterChips();
                ApplySectionOrder(scoped.LeadsWithPeople);
                IndexHeader.Title = scoped.LeadsWithPeople ? "Also in Titles" : "The Index";

                // "actors:" with nothing after it: say what the scope does
                // rather than reporting no matches for an empty term.
                if (scoped.Term.Length == 0 && scoped.Scope != "all")
                {
                    ResultsRepeater.ItemsSource = new List<MediaItem>();
                    PeopleRepeater.ItemsSource = new List<PersonItem>();
                    PeopleSection.Visibility = Visibility.Collapsed;
                    SetIndexMessage(ScopePromptMessage(scoped.Scope));
                    return;
                }
            }
            if (generation != _searchGeneration) return;

            if (HasDateFilter && query.Length > 0)
            {
                // Release dates are "yyyy-MM-dd", so day strings compare
                // lexicographically — no timezone juggling.
                var (from, to) = DayBounds();
                items = items
                    .Where(i => i.ReleaseDate is { Length: >= 10 } date
                        && string.Compare(date, from, StringComparison.Ordinal) >= 0
                        && string.Compare(date, to, StringComparison.Ordinal) <= 0)
                    .ToList();
            }
            if (yearFilter.HasValue)
            {
                items = items.Where(i => i.Year == yearFilter.Value).ToList();
            }

            ResultsRepeater.ItemsSource = items;
            PeopleRepeater.ItemsSource = people;
            PeopleSection.Visibility = people.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            SetIndexMessage(items.Count == 0 ? "Nothing in the index matches this search." : null);
        }
        catch (Exception failure)
        {
            if (generation != _searchGeneration) return;
            ResultsRepeater.ItemsSource = new List<MediaItem>();
            PeopleSection.Visibility = Visibility.Collapsed;
            SetIndexMessage(failure.Message);
        }
        finally
        {
            if (generation == _searchGeneration) SearchRing.IsActive = false;
        }
    }

    // ------------------------------------------------------------------
    // Person filmography ("actor details")
    // ------------------------------------------------------------------

    private async Task ShowFilmographyAsync(int personId, string personName)
    {
        _activePersonId = personId;
        _activePersonName = personName;
        _releaseFrom = null;
        _releaseTo = null;
        _scope = "all";
        _scopeTerm = "";
        _pendingFrom = null;
        _pendingTo = null;
        _heatmapAnchor = null;
        SetQueryText("");
        UpdateFilterChips();
        ApplySectionOrder(leadsWithPeople: false);

        var generation = ++_searchGeneration;
        IndexHeader.Title = string.IsNullOrEmpty(personName)
            ? "Filmography"
            : $"Filmography — {personName}";
        LibrarySection.Visibility = Visibility.Collapsed;
        PeopleSection.Visibility = Visibility.Collapsed;
        ResultsRepeater.ItemsSource = new List<MediaItem>();

        if (!WindowsCore.HasTmdbCredentials)
        {
            SetIndexMessage("TMDB is unavailable — credentials are missing.");
            ShowResults();
            return;
        }

        SearchRing.IsActive = true;
        ShowResults();
        try
        {
            var items = await WindowsCore.LoadPersonFilmographyAsync(personId);
            if (generation != _searchGeneration) return;
            ResultsRepeater.ItemsSource = items;
            SetIndexMessage(items.Count == 0 ? "No credited titles were found." : null);
        }
        catch (Exception failure)
        {
            if (generation != _searchGeneration) return;
            SetIndexMessage(failure.Message);
        }
        finally
        {
            if (generation == _searchGeneration) SearchRing.IsActive = false;
        }
    }

    private void ClearPersonState()
    {
        if (_activePersonId is null) return;
        _activePersonId = null;
        _activePersonName = "";
        UpdateFilterChips();
    }

    private void ClearPerson_Click(object sender, RoutedEventArgs e)
    {
        ClearPersonState();
        _searchGeneration++;
        SearchRing.IsActive = false;
        ShowIdle();
    }

    private void SetIndexMessage(string? message)
    {
        IndexMessage.Text = message ?? "";
        IndexMessage.Visibility = message is null ? Visibility.Collapsed : Visibility.Visible;
    }

    /// <summary>Local matches never wait on the network (classify-first spirit).</summary>
    private void BuildLocalResults(string query, int? yearFilter)
    {
        if (query.Length == 0)
        {
            LocalRepeater.ItemsSource = new List<LocalSearchResult>();
            LibrarySection.Visibility = Visibility.Collapsed;
            return;
        }

        var fromYear = _releaseFrom?.Year ?? yearFilter ?? int.MinValue;
        var toYear = _releaseTo?.Year ?? yearFilter ?? int.MaxValue;

        var library = AppServices.Library;
        var results = new List<LocalSearchResult>();

        foreach (var movie in library.Movies)
        {
            if (!movie.Title.Contains(query, StringComparison.OrdinalIgnoreCase)) continue;
            if (movie.Year is int movieYear && (movieYear < fromYear || movieYear > toYear)) continue;
            if (movie.Year is null && (yearFilter.HasValue || HasDateFilter)) continue;
            results.Add(new LocalSearchResult
            {
                Title = movie.Title,
                Subtitle = movie.DisplaySubtitle,
                ImageUrl = movie.PosterUrl,
                PlaceholderAsset = "ms-appx:///Assets/Icons/film.svg",
                Movie = movie,
            });
        }
        foreach (var show in library.Shows)
        {
            if (!show.Name.Contains(query, StringComparison.OrdinalIgnoreCase)) continue;
            if (show.FirstAirYear is int showYear && (showYear < fromYear || showYear > toYear)) continue;
            if (show.FirstAirYear is null && (yearFilter.HasValue || HasDateFilter)) continue;
            results.Add(new LocalSearchResult
            {
                Title = show.Name,
                Subtitle = show.DisplaySubtitle,
                ImageUrl = show.PosterUrl,
                PlaceholderAsset = "ms-appx:///Assets/Icons/tv.svg",
                Show = show,
            });
        }

        LocalRepeater.ItemsSource = results;
        LibrarySection.Visibility = results.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private void Result_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is MediaItem item)
        {
            NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(Ref: item.Ref));
        }
    }

    /// <summary>A people card opens that person's page (biography + filmography).</summary>
    private void PersonResult_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is PersonItem person)
        {
            NavigationService.Navigate(typeof(PersonPage), new PersonNavArgs(person.Id, person.Name));
        }
    }

    /// <summary>The filter chip's name opens the page, so the filter is not a dead end.</summary>
    private void PersonChip_Click(object sender, RoutedEventArgs e)
    {
        if (_activePersonId is int personId)
        {
            NavigationService.Navigate(typeof(PersonPage), new PersonNavArgs(personId, _activePersonName));
        }
    }

    private void LocalResult_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not LocalSearchResult result) return;
        if (result.Movie is { } movie)
        {
            NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(LocalMovieId: movie.Id));
        }
        else if (result.Show is { } show)
        {
            NavigationService.Navigate(typeof(DetailPage), new DetailNavArgs(LocalShowId: show.Id));
        }
    }
}
