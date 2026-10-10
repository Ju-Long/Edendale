// W.3: keeps the display and the system awake while video plays. LibVLC's
// own screen-saver inhibitor attaches to a video window it creates, and the
// WinUI swap-chain path never creates one, so the player asks Windows
// directly. SetThreadExecutionState is per thread: every call comes from the
// UI thread, which is also the thread that clears it.

using System.Runtime.InteropServices;

namespace Edendale.Windows.Services;

internal static class DisplayAwake
{
    private const uint EsSystemRequired = 0x00000001;
    private const uint EsDisplayRequired = 0x00000002;
    private const uint EsContinuous = 0x80000000;

    private static bool _held;

    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint flags);

    /// <summary>
    /// True while playing; false on pause, at the end, and when the player
    /// closes, so the display can sleep again on its normal timeout.
    /// </summary>
    public static void Hold(bool playing)
    {
        if (playing == _held) return;
        _held = playing;
        try
        {
            SetThreadExecutionState(playing
                ? EsContinuous | EsDisplayRequired | EsSystemRequired
                : EsContinuous);
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException)
        {
            // Not on Windows (never in the app); nothing to hold.
        }
    }
}
