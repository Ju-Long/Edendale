using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>
/// The player's global, device-local switches (DIFF.md §3.3 and §3.5): Loop,
/// Fit/Fill, and the opt-in skip prompts. Per-title memory lives in
/// <see cref="TitlePlaybackMemory"/>.
/// </summary>
public sealed class PlayerPreferences
{
    public const string LoopEnabledKey = "player.loopEnabled";
    public const string AspectFillKey = "player.aspectFill";
    public const string SegmentPromptsEnabledKey = "player.segmentPromptsEnabled";

    private readonly PlayerSettingsStore _store;

    public PlayerPreferences(PlayerSettingsStore store)
    {
        _store = store;
    }

    /// <summary>At the natural end the file restarts from the beginning instead of finishing.</summary>
    public bool LoopEnabled
    {
        get => _store.GetBool(LoopEnabledKey, fallback: false);
        set => _store.SetBool(LoopEnabledKey, value);
    }

    /// <summary>Crop the picture to fill the window rather than letterbox it.</summary>
    public bool AspectFill
    {
        get => _store.GetBool(AspectFillKey, fallback: false);
        set => _store.SetBool(AspectFillKey, value);
    }

    /// <summary>
    /// TheIntroDB skip prompts. Off by default, including on upgraded
    /// installs; Windows never had the old timed auto-skip, so nothing migrates.
    /// </summary>
    public bool SegmentPromptsEnabled
    {
        get => _store.GetBool(SegmentPromptsEnabledKey, fallback: false);
        set => _store.SetBool(SegmentPromptsEnabledKey, value);
    }

    /// <summary>The remembered speed and tracks for one title, or null.</summary>
    public ContentPlayerPreferences? ContentPreferences(string? contentKey) =>
        contentKey is null ? null : _store.GetObject<ContentPlayerPreferences>(contentKey);

    public void SaveContentPreferences(string? contentKey, ContentPlayerPreferences preferences)
    {
        if (contentKey is null) return;
        _store.SetObject(contentKey, preferences);
    }
}
