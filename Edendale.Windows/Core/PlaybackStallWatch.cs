namespace Edendale.Windows.Core;

/// <summary>
/// Notices a playback clock that stands still while the player is playing,
/// Windows' signal for 5020ec3's buffering indicator. LibVLC moves the time
/// about four times a second and stops when its input waits on a read that a
/// slow or dropped link hasn't answered, a moment before the picture freezes;
/// its own Buffering event only comes once that read returns. The last two
/// seconds don't count: the time rests there while the decoders drain before
/// the end. Times come from the caller, so tests need no clock.
/// </summary>
public sealed class PlaybackStallWatch
{
    /// <summary>How long the time must stand still: four of LibVLC's updates.</summary>
    public static readonly TimeSpan Threshold = TimeSpan.FromSeconds(1);

    private static readonly TimeSpan EndMargin = TimeSpan.FromSeconds(2);

    private long? _time;
    private TimeSpan _since;

    /// <summary>
    /// One look at the clock: the player's <paramref name="time"/> and
    /// <paramref name="duration"/> in milliseconds (0 or less when unknown),
    /// whether it is <paramref name="playing"/>, and the caller's monotonic
    /// <paramref name="now"/>. Returns whether playback has stalled.
    /// </summary>
    public bool Observe(long time, long duration, bool playing, TimeSpan now)
    {
        if (!playing)
        {
            _time = null;
            return false;
        }
        if (_time != time)
        {
            _time = time;
            _since = now;
            return false;
        }
        if (duration > 0 && time >= duration - (long)EndMargin.TotalMilliseconds) return false;
        return now - _since >= Threshold;
    }

    /// <summary>Forgets the clock, so a new file or a seek starts over.</summary>
    public void Reset() => _time = null;
}
