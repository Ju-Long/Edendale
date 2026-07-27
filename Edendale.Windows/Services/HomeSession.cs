using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

/// <summary>
/// Session cache for the Movies &amp; Shows tab — tab switches never refetch
/// (parity with MoviesShowsModel owned by RootView on Apple).
/// </summary>
public static class HomeSession
{
    public static HomeCatalog? Catalog { get; set; }
    public static string SelectedCollectionId { get; set; } = "all";
    public static List<MediaItem> CollectionItems { get; set; } = [];
}
