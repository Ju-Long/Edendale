namespace Edendale.Windows.Core;

/// <summary>The controller buttons the player reads (a subset of Windows.Gaming.Input).</summary>
[Flags]
public enum PadButtons
{
    None = 0,
    A = 1 << 0,
    B = 1 << 1,
    X = 1 << 2,
    Y = 1 << 3,
    LeftBumper = 1 << 4,
    RightBumper = 1 << 5,
    DPadLeft = 1 << 6,
    DPadRight = 1 << 7,
    DPadUp = 1 << 8,
    DPadDown = 1 << 9,
    Menu = 1 << 10,
    View = 1 << 11,
}

public enum PadAction
{
    PlayPause,
    /// <summary>Backs out like Esc: a panel, then full screen, then the player.</summary>
    Back,
    SkipBackward,
    SkipForward,
    SeekBackward,
    SeekForward,
    VolumeUp,
    VolumeDown,
    ToggleFullScreen,
    /// <summary>Opens Player Adjustments.</summary>
    Adjustments,
    HoldLeftStart,
    HoldRightStart,
    HoldEnd,
}

/// <summary>
/// X.1: turns controller readings into player actions. A plays or pauses,
/// the bumpers skip by the App Controls lengths, the D-pad seeks (repeating
/// while held) and changes the volume, B backs out like Esc, View toggles
/// full screen, and Menu opens Player Adjustments. The triggers drive the
/// hold speeds with the Siri Remote's hysteresis: past 0.55 starts that
/// side's speed, easing below 0.30 releases it.
/// </summary>
public sealed class GamepadInterpreter
{
    /// <summary>The D-pad repeats after this long held, then every <see cref="RepeatInterval"/>.</summary>
    public static readonly TimeSpan RepeatDelay = TimeSpan.FromSeconds(0.4);
    public static readonly TimeSpan RepeatInterval = TimeSpan.FromSeconds(0.15);

    /// <summary>The D-pad's fine seek, in seconds.</summary>
    public const int SeekSeconds = 5;

    private PadButtons _previous;
    private HoldSide? _hold;
    private readonly Dictionary<PadButtons, TimeSpan> _repeatAt = [];

    public HoldSide? Hold => _hold;

    public IReadOnlyList<PadAction> Update(PadButtons buttons, double leftTrigger, double rightTrigger, TimeSpan now)
    {
        var actions = new List<PadAction>();
        var pressed = buttons & ~_previous;

        void OnPress(PadButtons button, PadAction action)
        {
            if (pressed.HasFlag(button)) actions.Add(action);
        }

        OnPress(PadButtons.A, PadAction.PlayPause);
        OnPress(PadButtons.B, PadAction.Back);
        OnPress(PadButtons.LeftBumper, PadAction.SkipBackward);
        OnPress(PadButtons.RightBumper, PadAction.SkipForward);
        OnPress(PadButtons.View, PadAction.ToggleFullScreen);
        OnPress(PadButtons.Menu, PadAction.Adjustments);

        // The D-pad acts on press and repeats while held.
        foreach (var (button, action) in new[]
        {
            (PadButtons.DPadLeft, PadAction.SeekBackward),
            (PadButtons.DPadRight, PadAction.SeekForward),
            (PadButtons.DPadUp, PadAction.VolumeUp),
            (PadButtons.DPadDown, PadAction.VolumeDown),
        })
        {
            if (pressed.HasFlag(button))
            {
                actions.Add(action);
                _repeatAt[button] = now + RepeatDelay;
            }
            else if (buttons.HasFlag(button))
            {
                if (_repeatAt.TryGetValue(button, out var due) && now >= due)
                {
                    actions.Add(action);
                    _repeatAt[button] = now + RepeatInterval;
                }
            }
            else
            {
                _repeatAt.Remove(button);
            }
        }

        // Triggers: the side pressed further wins; hysteresis keeps a hold
        // from flickering as a trigger eases off.
        var active = _hold is not null;
        var threshold = active ? PlayerLogic.HoldReleaseMagnitude : PlayerLogic.HoldArmMagnitude;
        HoldSide? side = null;
        var left = Math.Clamp(leftTrigger, 0, 1);
        var right = Math.Clamp(rightTrigger, 0, 1);
        if (Math.Max(left, right) >= threshold)
        {
            side = _hold is { } held && (held == HoldSide.Left ? left : right) >= PlayerLogic.HoldReleaseMagnitude
                ? held
                : left > right ? HoldSide.Left : HoldSide.Right;
        }
        if (side != _hold)
        {
            if (_hold is not null && side is null) actions.Add(PadAction.HoldEnd);
            if (side is { } start) actions.Add(start == HoldSide.Left ? PadAction.HoldLeftStart : PadAction.HoldRightStart);
            _hold = side;
        }

        _previous = buttons;
        return actions;
    }

    /// <summary>Forgets held buttons, as when the player loses the controller or closes.</summary>
    public IReadOnlyList<PadAction> Reset()
    {
        _previous = PadButtons.None;
        _repeatAt.Clear();
        if (_hold is null) return [];
        _hold = null;
        return [PadAction.HoldEnd];
    }
}
