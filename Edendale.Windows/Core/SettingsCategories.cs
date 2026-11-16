namespace Edendale.Windows.Core;

/// <summary>
/// One page of Settings (SettingsView.swift's <c>SettingsCategory</c>), in
/// Apple's order.
/// </summary>
public enum SettingsCategory
{
    General,
    PlayerControls,
    Audio,
    Subtitles,
    Library,
    Sync,
    Accounts,
    ParentalControls,
    About,
}

/// <summary>
/// The Settings categories: their order, names, and icons, and the one rule
/// about what a category shows (SettingsView.swift, TMDBAccountSection.swift).
/// WinUI-free, so Edendale.Windows.Tests checks them; SettingsPage lists them
/// beside the open category.
/// </summary>
public static class SettingsCategories
{
    /// <summary>
    /// Every category, in order. Windows is a desktop, so like macOS it lists
    /// General, Parental Controls, and About as pages of their own; Apple's
    /// handheld and TV list leaves out General and shows the last two in place.
    /// </summary>
    public static IReadOnlyList<SettingsCategory> Listed { get; } =
    [
        SettingsCategory.General,
        SettingsCategory.PlayerControls,
        SettingsCategory.Audio,
        SettingsCategory.Subtitles,
        SettingsCategory.Library,
        SettingsCategory.Sync,
        SettingsCategory.Accounts,
        SettingsCategory.ParentalControls,
        SettingsCategory.About,
    ];

    /// <summary>
    /// The category Settings opens on the first time, as the Mac's Settings
    /// window does. The page then remembers the open category for the session;
    /// nothing is persisted.
    /// </summary>
    public const SettingsCategory Default = SettingsCategory.General;

    /// <summary>A stable, culture-free name, for automation ids ("player-controls").</summary>
    public static string Tag(this SettingsCategory category) => category switch
    {
        SettingsCategory.General => "general",
        SettingsCategory.PlayerControls => "player-controls",
        SettingsCategory.Audio => "audio",
        SettingsCategory.Subtitles => "subtitles",
        SettingsCategory.Library => "library",
        SettingsCategory.Sync => "sync",
        SettingsCategory.Accounts => "accounts",
        SettingsCategory.ParentalControls => "parental-controls",
        _ => "about",
    };

    /// <summary>The resource key of the category's name ("Settings_CategoryPlayerControls").</summary>
    public static string TitleKey(this SettingsCategory category) => "Settings_Category" + category;

    /// <summary>The category's name, as the list, the picker, and the page title show it.</summary>
    public static string Title(this SettingsCategory category) => AppText.Get(category.TitleKey());

    /// <summary>
    /// The category's glyph in Assets/Icons, without ".svg": Apple's
    /// <c>iconText</c>, except Parental Controls. Apple draws it with
    /// person-breastfeeding (862f7e6), but its asset is an SF Symbols template
    /// that keeps only rescaled path data, not the Font Awesome SVG the other
    /// icons are, so Windows keeps eye-slash, Apple's icon before that commit
    /// and Android's choice.
    /// </summary>
    public static string IconName(this SettingsCategory category) => category switch
    {
        SettingsCategory.General => "gear-complex",
        SettingsCategory.PlayerControls => "play",
        SettingsCategory.Audio => "file-music",
        SettingsCategory.Subtitles => "subtitles",
        SettingsCategory.Library => "folder-tree",
        SettingsCategory.Sync => "arrow-rotate-right",
        SettingsCategory.Accounts => "circle-user-fill",
        SettingsCategory.ParentalControls => "eye-slash",
        _ => "film-circle-info",
    };

    /// <summary>
    /// Whether Settings → Accounts shows the TMDB Account section
    /// (TMDBAccountSection.swift: <c>account.canSignIn || account.phase != .signedOut</c>).
    /// A build without TMDB credentials has no sign-in to offer, but a session
    /// or an approval already under way still shows, so it can be finished or
    /// signed out.
    /// </summary>
    public static bool ShowsTmdbAccount(bool canConnect, bool isConnected, bool hasPendingApproval) =>
        canConnect || isConnected || hasPendingApproval;
}
