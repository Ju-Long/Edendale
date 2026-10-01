using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>How far one skip jumps. Each length has matching arrow-rotate glyphs.</summary>
public enum SkipInterval
{
    Ten = 10,
    Fifteen = 15,
    Thirty = 30,
}

/// <summary>Which way a skip jumps.</summary>
public enum SkipDirection
{
    Backward,
    Forward,
}

/// <summary>
/// Settings → App Controls: how far a skip jumps back and forward, and the
/// speeds a press-and-hold on the left or right of the video engages. Ports
/// PlayerControlPreferences.swift. Values are device-local and read at every
/// gesture, so a change applies without restarting playback.
/// </summary>
public sealed class PlayerControlPreferences
{
    public const string SkipBackwardKey = "player.skipBackwardSeconds";
    public const string SkipForwardKey = "player.skipForwardSeconds";
    public const string HoldLeftRateKey = "player.holdLeftRate";
    public const string HoldRightRateKey = "player.holdRightRate";

    public const SkipInterval DefaultSkipInterval = SkipInterval.Ten;

    /// <summary>Slow motion on the left and fast-forward on the right until changed.</summary>
    public const double DefaultHoldLeftRate = 0.5;
    public const double DefaultHoldRightRate = 2.0;

    /// <summary>Hold speeds move in quarter steps across the whole speed range.</summary>
    public const double HoldRateStep = 0.25;

    public static IReadOnlyList<SkipInterval> SkipIntervals { get; } =
        [SkipInterval.Ten, SkipInterval.Fifteen, SkipInterval.Thirty];

    private readonly PlayerSettingsStore _store;

    /// <summary>Raised after either skip length changes, so transport controls can relabel.</summary>
    public event EventHandler? SkipIntervalsChanged;

    public PlayerControlPreferences(PlayerSettingsStore store)
    {
        _store = store;
    }

    public SkipInterval SkipBackwardInterval
    {
        get => StoredInterval(SkipBackwardKey);
        set => SetInterval(SkipBackwardKey, value);
    }

    public SkipInterval SkipForwardInterval
    {
        get => StoredInterval(SkipForwardKey);
        set => SetInterval(SkipForwardKey, value);
    }

    public SkipInterval SkipIntervalFor(SkipDirection direction) =>
        direction == SkipDirection.Backward ? SkipBackwardInterval : SkipForwardInterval;

    /// <summary>The signed seek a skip in <paramref name="direction"/> performs: negative going back.</summary>
    public int SkipOffset(SkipDirection direction)
    {
        var seconds = (int)SkipIntervalFor(direction);
        return direction == SkipDirection.Backward ? -seconds : seconds;
    }

    public double HoldRate(HoldSide side) => side == HoldSide.Left
        ? StoredRate(HoldLeftRateKey, DefaultHoldLeftRate)
        : StoredRate(HoldRightRateKey, DefaultHoldRightRate);

    public void SetHoldRate(double rate, HoldSide side) =>
        _store.SetDouble(side == HoldSide.Left ? HoldLeftRateKey : HoldRightRateKey, NormalizedHoldRate(rate));

    /// <summary>Snaps a rate onto the quarter-step grid inside 0.25…3.00; non-finite becomes the minimum.</summary>
    public static double NormalizedHoldRate(double rate)
    {
        if (!double.IsFinite(rate)) return PlayerLogic.MinRate;
        var snapped = Math.Round(rate / HoldRateStep, MidpointRounding.AwayFromZero) * HoldRateStep;
        return Math.Clamp(snapped, PlayerLogic.MinRate, PlayerLogic.MaxRate);
    }

    /// <summary>Only 10, 15, and 30 are lengths; anything else stored reads as the default.</summary>
    public static SkipInterval NormalizedInterval(int? seconds) => seconds switch
    {
        10 => SkipInterval.Ten,
        15 => SkipInterval.Fifteen,
        30 => SkipInterval.Thirty,
        _ => DefaultSkipInterval,
    };

    private SkipInterval StoredInterval(string key) => NormalizedInterval(_store.GetInt(key));

    private void SetInterval(string key, SkipInterval value)
    {
        if (StoredInterval(key) == value && _store.Contains(key)) return;
        _store.SetInt(key, (int)value);
        SkipIntervalsChanged?.Invoke(this, EventArgs.Empty);
    }

    private double StoredRate(string key, double fallback) =>
        _store.GetDouble(key) is double stored ? NormalizedHoldRate(stored) : fallback;
}
