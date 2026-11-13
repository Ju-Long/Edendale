namespace Edendale.Windows.Core;

/// <summary>
/// The mouse pointer over the video hides once it has rested there
/// (apple-27.1 a4c8561, PlayerIdlePointer on macOS). A move brings it back
/// and starts the countdown again; a held button (a timeline drag, a speed
/// hold) keeps it; leaving the video shows it and stops the countdown. A
/// report at the point it already had is not a move: WinUI raises those when
/// the controls fade under a still pointer. Times come from the caller, so
/// tests need no clock.
/// </summary>
public sealed class IdlePointer(TimeSpan? delay = null)
{
    /// <summary>Apple's delay: five seconds without a move.</summary>
    public static readonly TimeSpan DefaultDelay = TimeSpan.FromSeconds(5);

    public TimeSpan Delay { get; } = delay ?? DefaultDelay;

    public bool IsHidden { get; private set; }

    /// <summary>When the countdown ends; null while none runs (the pointer is away, or already hidden).</summary>
    public TimeSpan? Deadline { get; private set; }

    private (double X, double Y)? _location;

    /// <summary>
    /// A pointer report over the video. Returns whether it moved: a move
    /// shows the pointer and starts the countdown again.
    /// </summary>
    public bool PointerMoved(double x, double y, TimeSpan now)
    {
        if (_location == (x, y)) return false;
        _location = (x, y);
        RestartCountdown(now);
        return true;
    }

    /// <summary>The pointer left the video: it shows, and nothing counts down.</summary>
    public void PointerLeft()
    {
        _location = null;
        Deadline = null;
        IsHidden = false;
    }

    /// <summary>Shows the pointer and, while it is over the video, counts down again.</summary>
    public void RestartCountdown(TimeSpan now)
    {
        IsHidden = false;
        Deadline = _location is null ? null : now + Delay;
    }

    /// <summary>
    /// The countdown's timer fired. At or past the deadline the pointer
    /// hides, unless a button is held, which starts the countdown over.
    /// Returns whether the pointer is hidden.
    /// </summary>
    public bool Tick(TimeSpan now, bool isButtonHeld)
    {
        if (Deadline is not { } deadline || now < deadline) return IsHidden;
        if (isButtonHeld)
        {
            RestartCountdown(now);
            return false;
        }
        Deadline = null;
        IsHidden = true;
        return true;
    }
}
