namespace Edendale.Windows.Core;

/// <summary>The Downloaded page's sections, each also a sidebar child row (DIFF.md §3.15).</summary>
public enum DownloadedSection
{
    ContinueWatching,
    Movies,
    Shows,
}

/// <summary>The Watchlist page's sections, each also a sidebar child row.</summary>
public enum WatchlistSection
{
    Movies,
    Shows,
}

public static class LibrarySections
{
    /// <summary>
    /// The Downloaded sections that have titles for the current audience
    /// setting, in sidebar order. Callers pass counts already filtered.
    /// </summary>
    public static IReadOnlyList<DownloadedSection> AvailableDownloaded(bool hasResumeItems, int movieCount, int showCount)
    {
        var sections = new List<DownloadedSection>();
        if (hasResumeItems) sections.Add(DownloadedSection.ContinueWatching);
        if (movieCount > 0) sections.Add(DownloadedSection.Movies);
        if (showCount > 0) sections.Add(DownloadedSection.Shows);
        return sections;
    }

    /// <summary>The Watchlist sections for the visible saved titles' media types ("movie", "tv").</summary>
    public static IReadOnlyList<WatchlistSection> AvailableWatchlist(IEnumerable<string> mediaTypes)
    {
        var types = mediaTypes.ToHashSet(StringComparer.Ordinal);
        var sections = new List<WatchlistSection>();
        if (types.Contains("movie")) sections.Add(WatchlistSection.Movies);
        if (types.Contains("tv")) sections.Add(WatchlistSection.Shows);
        return sections;
    }

    public static string Tag(this DownloadedSection section) => section switch
    {
        DownloadedSection.ContinueWatching => "continue",
        DownloadedSection.Movies => "movies",
        _ => "shows",
    };

    public static string Tag(this WatchlistSection section) =>
        section == WatchlistSection.Movies ? "movies" : "shows";

    // ------------------------------------------------------------------
    // The Downloaded page's own section picker (DownloadedSectionPicker.swift,
    // apple-27.1 94a8b4f). The sidebar rows choose a section too; both show
    // the same choice.
    // ------------------------------------------------------------------

    /// <summary>The picker's choices: the whole page (null), then each available section in sidebar order.</summary>
    public static IReadOnlyList<DownloadedSection?> PickerChoices(IReadOnlyList<DownloadedSection> sections) =>
        [null, .. sections.Select(section => (DownloadedSection?)section)];

    /// <summary>With one section the whole page and that section list the same titles, so there is nothing to pick.</summary>
    public static bool IsPickerOffered(IReadOnlyList<DownloadedSection> sections) => sections.Count > 1;

    /// <summary>The section still shown: <paramref name="selection"/> while it has titles, else the whole page (null).</summary>
    public static DownloadedSection? Resolved(DownloadedSection? selection, IReadOnlyList<DownloadedSection> available) =>
        selection is { } section && available.Contains(section) ? section : null;

    /// <summary>A choice's name, the same words as its sidebar row: All, Continue Watching, Movies, TV Shows.</summary>
    public static string PickerTitle(this DownloadedSection? choice) => AppText.Get(choice switch
    {
        null => "DownloadedSection_All",
        DownloadedSection.ContinueWatching => "Section_ContinueWatching",
        DownloadedSection.Movies => "Section_Movies",
        _ => "Section_TvShows",
    });
}

/// <summary>
/// A sidebar row: a root tab ("movies", "watchlist", "downloaded", "search",
/// "settings"), or one of Watchlist's or Downloaded's section rows. Rows are
/// tagged "tab" or "tab:section" in the NavigationView.
/// </summary>
public sealed record SidebarItem(string Tab, string? Section = null)
{
    public string NavTag => Section is null ? Tab : $"{Tab}:{Section}";

    public static SidebarItem Parse(string tag)
    {
        var colon = tag.IndexOf(':');
        return colon < 0 ? new SidebarItem(tag) : new SidebarItem(tag[..colon], tag[(colon + 1)..]);
    }

    public static SidebarItem For(DownloadedSection section) => new("downloaded", section.Tag());

    /// <summary>The Downloaded row for a picker choice: the page itself for the whole page (null), else the section's row.</summary>
    public static SidebarItem ForDownloaded(DownloadedSection? section) => section is { } chosen ? For(chosen) : new("downloaded");

    public static SidebarItem For(WatchlistSection section) => new("watchlist", section.Tag());

    public DownloadedSection? DownloadedSection => Tab != "downloaded" ? null : Section switch
    {
        "continue" => Core.DownloadedSection.ContinueWatching,
        "movies" => Core.DownloadedSection.Movies,
        "shows" => Core.DownloadedSection.Shows,
        _ => null,
    };

    public WatchlistSection? WatchlistSection => Tab != "watchlist" ? null : Section switch
    {
        "movies" => Core.WatchlistSection.Movies,
        "shows" => Core.WatchlistSection.Shows,
        _ => null,
    };

    /// <summary>
    /// The row to show: itself while its section still has titles, otherwise
    /// its parent page, so an emptied section never strands the reader.
    /// </summary>
    public SidebarItem Resolved(IReadOnlyList<WatchlistSection> watchlistSections, IReadOnlyList<DownloadedSection> downloadedSections)
    {
        if (Section is null) return this;
        if (DownloadedSection is { } downloaded) return ForDownloaded(LibrarySections.Resolved(downloaded, downloadedSections));
        if (WatchlistSection is { } watchlist) return watchlistSections.Contains(watchlist) ? this : new SidebarItem(Tab);
        return new SidebarItem(Tab);
    }
}

/// <summary>
/// A horizontal shelf's scroll geometry, mirrored into its heading's rule,
/// which doubles as the shelf's scroll indicator and scrubber (DIFF.md §3.15).
/// </summary>
public readonly record struct ShelfScrollMetrics(double Offset, double ContentWidth, double ViewportWidth)
{
    /// <summary>Scrollable distance: content width minus viewport width.</summary>
    public double Range => Math.Max(ContentWidth - ViewportWidth, 0);

    /// <summary>Viewport width / content width; sizes the gold thumb.</summary>
    public double VisibleFraction => ContentWidth > 0 ? Math.Min(ViewportWidth / ContentWidth, 1) : 1;

    /// <summary>Scroll position, 0…1.</summary>
    public double Progress => Range > 0 ? Math.Clamp(Offset / Range, 0, 1) : 0;

    /// <summary>The scrubber appears only when the shelf overflows.</summary>
    public bool IsScrollable => VisibleFraction < 0.999;

    /// <summary>The horizontal offset for a 0…1 scrub position.</summary>
    public double OffsetFor(double fraction) => Math.Clamp(fraction, 0, 1) * Range;

    /// <summary>
    /// The 0…1 position under the pointer on a rule <paramref name="trackWidth"/>
    /// wide whose thumb is <paramref name="thumbWidth"/> wide: the thumb's
    /// centre follows the pointer.
    /// </summary>
    public static double FractionAt(double x, double trackWidth, double thumbWidth)
    {
        var travel = trackWidth - thumbWidth;
        return travel > 0 ? Math.Clamp((x - thumbWidth / 2) / travel, 0, 1) : 0;
    }

    /// <summary>The thumb's width on a rule: the visible share, never below 28 px.</summary>
    public double ThumbWidth(double trackWidth) => Math.Min(Math.Max(trackWidth * VisibleFraction, 28), trackWidth);

    /// <summary>Keyboard and assistive steps move by a screenful, at least a tenth.</summary>
    public double Step => Math.Max(VisibleFraction, 0.1);
}
