namespace Edendale.Windows.Core;

/// <summary>
/// The video extensions the library imports, shared by local scans, remote
/// connector listings, and the Open With registration. Extension filtering,
/// not MIME type: providers disagree on video MIME types.
/// </summary>
internal static class VideoFiles
{
    public static readonly string[] SupportedExtensions =
    [
        ".mkv", ".mp4", ".m4v", ".mov", ".avi", ".wmv", ".webm",
        ".ts", ".m2ts", ".mpg", ".mpeg", ".flv", ".3gp",
    ];

    private static readonly HashSet<string> Extensions =
        new(SupportedExtensions, StringComparer.OrdinalIgnoreCase);

    /// <summary>Whether a file name (or path) ends in a supported video extension.</summary>
    public static bool IsVideoName(string name) =>
        Extensions.Contains(Path.GetExtension(name));
}
