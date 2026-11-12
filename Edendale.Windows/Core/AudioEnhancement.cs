using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>
/// The five equalizer profiles (DIFF.md §3.6). LibVLC's equalizer uses the
/// same ten bands and ±20 dB range as Apple's, so the values map one to one.
/// Night Mode is a frequency balance, not a compressor.
/// </summary>
public enum AudioEnhancementProfile
{
    Flat,
    Movies,
    Music,
    Dialogue,
    NightMode,
}

public static class AudioEnhancementProfiles
{
    public const int BandCount = 10;
    public const double MinimumAmplification = -20;
    public const double MaximumAmplification = 20;

    public static IReadOnlyList<AudioEnhancementProfile> All { get; } =
    [
        AudioEnhancementProfile.Flat,
        AudioEnhancementProfile.Movies,
        AudioEnhancementProfile.Music,
        AudioEnhancementProfile.Dialogue,
        AudioEnhancementProfile.NightMode,
    ];

    /// <summary>LibVLC's band centers: 60, 170, 310, 600 Hz; 1, 3, 6, 12, 14, 16 kHz.</summary>
    public static IReadOnlyList<string> BandFrequencyLabels { get; } =
        ["60", "170", "310", "600", "1k", "3k", "6k", "12k", "14k", "16k"];

    /// <summary>The stored value, shared with Apple ("nightMode").</summary>
    public static string RawValue(this AudioEnhancementProfile profile) => profile switch
    {
        AudioEnhancementProfile.Flat => "flat",
        AudioEnhancementProfile.Movies => "movies",
        AudioEnhancementProfile.Music => "music",
        AudioEnhancementProfile.Dialogue => "dialogue",
        AudioEnhancementProfile.NightMode => "nightMode",
        _ => "movies",
    };

    public static AudioEnhancementProfile? FromRawValue(string? raw) =>
        All.Cast<AudioEnhancementProfile?>().FirstOrDefault(profile => profile!.Value.RawValue() == raw);

    public static string DisplayName(this AudioEnhancementProfile profile) => AppText.Get(profile switch
    {
        AudioEnhancementProfile.Flat => "Audio_ProfileFlat",
        AudioEnhancementProfile.Movies => "Audio_ProfileMovies",
        AudioEnhancementProfile.Music => "Audio_ProfileMusic",
        AudioEnhancementProfile.Dialogue => "Audio_ProfileDialogue",
        _ => "Audio_ProfileNightMode",
    });

    /// <summary>Each profile's preamp offsets its peak boost, which leaves headroom.</summary>
    public static double Preamp(this AudioEnhancementProfile profile) => profile switch
    {
        AudioEnhancementProfile.Movies => -8,
        AudioEnhancementProfile.Music => -4,
        AudioEnhancementProfile.Dialogue => -6,
        AudioEnhancementProfile.NightMode => -5,
        _ => 0,
    };

    public static IReadOnlyList<double> Bands(this AudioEnhancementProfile profile) => profile switch
    {
        AudioEnhancementProfile.Movies => [8, 5, 3, 0, 0, 2, 3, 2, 1, 0],
        AudioEnhancementProfile.Music => [4, 2, 0, -1, -1, 2, 3, 3, 2, 1],
        AudioEnhancementProfile.Dialogue => [-3, -1, 0, 5, 6, 5, 3, 1, 0, -1],
        AudioEnhancementProfile.NightMode => [-5, -2, 1, 4, 5, 5, 3, 1, 0, -1],
        _ => [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    };

    public static double PeakBandBoost(this AudioEnhancementProfile profile) => profile.Bands().Max();

    /// <summary>Clamps a level to −20…+20 dB; a non-finite value becomes 0.</summary>
    public static double Clamp(double value) =>
        double.IsFinite(value) ? Math.Clamp(value, MinimumAmplification, MaximumAmplification) : 0;

    /// <summary>
    /// User adjustments are offsets from the profile, so they span the whole
    /// range in both directions: every level stays reachable from any
    /// profile (a +8 dB band can still be pulled down to −20 dB).
    /// Apple's <c>AudioEnhancementProfile.adjustmentRange</c>.
    /// </summary>
    public const double MinimumAdjustment = -40;

    /// <summary>The upper end of the offsets' range; see <see cref="MinimumAdjustment"/>.</summary>
    public const double MaximumAdjustment = 40;

    /// <summary>Clamps a stored offset to −40…+40 dB; a non-finite value becomes 0 (Apple's <c>clampAdjustment</c>).</summary>
    public static double ClampAdjustment(double value) =>
        double.IsFinite(value) ? Math.Clamp(value, MinimumAdjustment, MaximumAdjustment) : 0;
}

/// <summary>
/// Settings → Audio plus the Player Adjustments booster
/// (AudioEnhancementController.swift). Settings works in absolute levels:
/// the graph and the preamp slider show and set each level, and the level is
/// stored as the user's offset from the selected profile, so choosing another
/// profile resets the offsets and starts again from that profile's curve.
/// Levels clamp to −20…+20 dB; the stored offsets clamp to −40…+40 dB, so
/// every level is reachable from any profile. Keys are Apple's:
/// <c>audio.enhancementProfile</c>, <c>audio.enhancementPreamp</c>,
/// <c>audio.enhancementBands</c>, <c>audio.boosterEnabled</c>. Their shapes
/// and meaning are unchanged since 27.0, so a saved file loads as it was.
/// </summary>
public sealed class AudioEnhancement
{
    public const string ProfileKey = "audio.enhancementProfile";
    public const string PreampKey = "audio.enhancementPreamp";
    public const string BandsKey = "audio.enhancementBands";
    public const string BoosterKey = "audio.boosterEnabled";

    public const AudioEnhancementProfile DefaultProfile = AudioEnhancementProfile.Movies;

    /// <summary>Audio Booster adds this much preamp through the equalizer, not the volume.</summary>
    public const double BoosterGain = 10;

    private readonly PlayerSettingsStore _store;

    public AudioEnhancement(PlayerSettingsStore store)
    {
        _store = store;
    }

    public AudioEnhancementProfile SelectedProfile =>
        AudioEnhancementProfiles.FromRawValue(_store.GetString(ProfileKey)) ?? DefaultProfile;

    public bool BoosterEnabled => _store.GetBool(BoosterKey, fallback: false);

    /// <summary>The user's offset from the profile's preamp, within ±40 dB.</summary>
    public double UserPreampAdjustment =>
        _store.GetDouble(PreampKey) is double stored ? AudioEnhancementProfiles.ClampAdjustment(stored) : 0;

    /// <summary>Ten offsets from the profile's bands, each within ±40 dB; a stored list of the wrong length is ignored.</summary>
    public IReadOnlyList<double> UserBandAdjustments
    {
        get
        {
            var stored = _store.GetDoubleArray(BandsKey);
            return stored is { Length: AudioEnhancementProfiles.BandCount }
                ? [.. stored.Select(AudioEnhancementProfiles.ClampAdjustment)]
                : new double[AudioEnhancementProfiles.BandCount];
        }
    }

    public bool HasUserAdjustments =>
        UserPreampAdjustment != 0 || UserBandAdjustments.Any(value => value != 0);

    public double EffectivePreamp
    {
        get
        {
            var value = SelectedProfile.Preamp() + UserPreampAdjustment;
            if (BoosterEnabled) value += BoosterGain;
            return AudioEnhancementProfiles.Clamp(value);
        }
    }

    /// <summary>Each band's level: the profile's plus the user's offset, within ±20 dB. The graph shows these.</summary>
    public IReadOnlyList<double> EffectiveBands =>
        [.. SelectedProfile.Bands().Zip(UserBandAdjustments, (profile, user) => AudioEnhancementProfiles.Clamp(profile + user))];

    /// <summary>
    /// The preamp the user sets: the profile's plus their adjustment, before
    /// the booster's gain. The Settings slider shows this; the booster adds
    /// its gain only to <see cref="EffectivePreamp"/>, which is what plays.
    /// </summary>
    public double PreampLevel => AudioEnhancementProfiles.Clamp(SelectedProfile.Preamp() + UserPreampAdjustment);

    /// <summary>When nothing would be audible, the equalizer is removed entirely.</summary>
    public bool IsEffectivelyFlat => EffectivePreamp == 0 && EffectiveBands.All(value => value == 0);

    /// <summary>Choosing another profile resets the user adjustments; the same profile keeps them.</summary>
    public void SelectProfile(AudioEnhancementProfile profile)
    {
        if (profile == SelectedProfile && _store.Contains(ProfileKey)) return;
        var changed = profile != SelectedProfile;
        _store.SetString(ProfileKey, profile.RawValue());
        if (changed) ResetUserAdjustments();
    }

    public void SetBooster(bool enabled) => _store.SetBool(BoosterKey, enabled);

    /// <summary>Stores the preamp offset, clamped to ±40 dB.</summary>
    public void SetUserPreampAdjustment(double value) =>
        _store.SetDouble(PreampKey, AudioEnhancementProfiles.ClampAdjustment(value));

    /// <summary>Stores one band's offset, clamped to ±40 dB. An index outside 0…9 is ignored.</summary>
    public void SetUserBandAdjustment(double value, int index)
    {
        if (index is < 0 or >= AudioEnhancementProfiles.BandCount) return;
        var bands = UserBandAdjustments.ToArray();
        bands[index] = AudioEnhancementProfiles.ClampAdjustment(value);
        _store.SetDoubleArray(BandsKey, bands);
    }

    /// <summary>
    /// Sets the preamp to an absolute level (before the booster), as the
    /// Settings slider does; stored as an offset from the profile. A
    /// non-finite level is ignored.
    /// </summary>
    public void SetPreampLevel(double level)
    {
        if (!double.IsFinite(level)) return;
        SetUserPreampAdjustment(AudioEnhancementProfiles.Clamp(level) - SelectedProfile.Preamp());
    }

    /// <summary>
    /// Sets one band to an absolute level, as the equalizer graph does;
    /// stored as an offset from the profile, so changing profiles still
    /// starts again from that profile's curve. A non-finite level or an index
    /// outside 0…9 is ignored.
    /// </summary>
    public void SetBandLevel(double level, int index)
    {
        if (index is < 0 or >= AudioEnhancementProfiles.BandCount || !double.IsFinite(level)) return;
        SetUserBandAdjustment(AudioEnhancementProfiles.Clamp(level) - SelectedProfile.Bands()[index], index);
    }

    public void ResetUserAdjustments()
    {
        _store.SetDouble(PreampKey, 0);
        _store.SetDoubleArray(BandsKey, new double[AudioEnhancementProfiles.BandCount]);
    }

    /// <summary>True when <paramref name="key"/> is one of this feature's settings.</summary>
    public static bool Owns(string key) => key is ProfileKey or PreampKey or BandsKey or BoosterKey;
}
