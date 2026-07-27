namespace Edendale.Windows.Helpers;

/// <summary>Static x:Bind formatting helpers shared by page templates.</summary>
public static class Format
{
    public static string YearText(int? year) => year?.ToString() ?? "";

    public static string Upper(string? value) => value?.ToUpperInvariant() ?? "";

    /// <summary>SVG poster placeholder for a TMDB media type.</summary>
    public static string MediaAsset(string mediaType) => mediaType == "tv" ? "ms-appx:///Assets/Icons/tv.svg" : "ms-appx:///Assets/Icons/film.svg";

    /// <summary>x:Bind hook: URL string → ImageSource (null-safe).</summary>
    public static Microsoft.UI.Xaml.Media.ImageSource? Image(string? url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var uri)
            ? new Microsoft.UI.Xaml.Media.Imaging.BitmapImage(uri)
            : null;
}
