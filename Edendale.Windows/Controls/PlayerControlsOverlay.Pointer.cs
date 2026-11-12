using System.Runtime.InteropServices;
using Edendale.Windows.Core;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Input;
using VirtualKey = Windows.System.VirtualKey;
using Windows.UI.Core;

namespace Edendale.Windows.Controls;

/// <summary>
/// apple-27.1 a4c8561: the mouse pointer hides once it has rested over the
/// video for five seconds (<see cref="IdlePointer"/>), and the next move
/// brings it back. Mouse only: pen and touch leave no resting arrow. Windows
/// hides it through user32's cursor display count, changed only when the
/// state flips so the count always returns; closing the player, leaving the
/// video, and another window taking focus all show it again.
/// </summary>
public sealed partial class PlayerControlsOverlay
{
    private readonly IdlePointer _idlePointer = new();
    private DispatcherQueueTimer _pointerTimer = null!;
    private bool _cursorHidden;

    [DllImport("user32.dll")]
    private static extern int ShowCursor([MarshalAs(UnmanagedType.Bool)] bool show);

    private void InitializePointer()
    {
        _pointerTimer = DispatcherQueue.CreateTimer();
        _pointerTimer.IsRepeating = false;
        _pointerTimer.Tick += (_, _) =>
        {
            _pointerTimer.Stop();
            _idlePointer.Tick(_clock.Elapsed, KeepsPointer());
            ApplyIdlePointer();
        };
    }

    /// <summary>
    /// A pointer report over the video. False for a mouse report at the point
    /// it already had: the controls fading under a still pointer raise those,
    /// and they must neither show the pointer nor bring the controls back.
    /// </summary>
    private bool TrackIdlePointer(PointerRoutedEventArgs e)
    {
        if (e.Pointer.PointerDeviceType != PointerDeviceType.Mouse) return true;
        var position = e.GetCurrentPoint(this).Position;
        if (!_idlePointer.PointerMoved(position.X, position.Y, _clock.Elapsed)) return false;
        ApplyIdlePointer();
        return true;
    }

    /// <summary>A mouse press shows the pointer and counts down again.</summary>
    private void RestartIdlePointer(PointerRoutedEventArgs e)
    {
        if (e.Pointer.PointerDeviceType != PointerDeviceType.Mouse) return;
        _idlePointer.RestartCountdown(_clock.Elapsed);
        ApplyIdlePointer();
    }

    private void IdlePointerLeft(PointerRoutedEventArgs e)
    {
        if (e.Pointer.PointerDeviceType == PointerDeviceType.Mouse) ReleaseIdlePointer();
    }

    /// <summary>
    /// Shows the pointer and stops the countdown until the next move over
    /// the video: the player closed, or another window took focus.
    /// </summary>
    public void ReleaseIdlePointer()
    {
        _idlePointer.PointerLeft();
        ApplyIdlePointer();
    }

    /// <summary>
    /// Brings the cursor in line with the model (shown whenever no player is
    /// bound, as between two files) and arms the timer for the deadline.
    /// </summary>
    private void ApplyIdlePointer()
    {
        var hide = _idlePointer.IsHidden && _mediaPlayer is not null;
        if (hide != _cursorHidden)
        {
            _cursorHidden = hide;
            ShowCursor(!hide);
        }
        _pointerTimer.Stop();
        if (_idlePointer.Deadline is not { } deadline) return;
        var wait = deadline - _clock.Elapsed;
        _pointerTimer.Interval = wait > TimeSpan.FromMilliseconds(1) ? wait : TimeSpan.FromMilliseconds(1);
        _pointerTimer.Start();
    }

    /// <summary>
    /// A held button (a timeline drag, a speed hold, any mouse button) keeps
    /// the pointer, and so does the open subtitle browser, which also keeps
    /// the controls up.
    /// </summary>
    private bool KeepsPointer()
    {
        if (_pressPointerId is not null || _holdSide is not null || _isSliderManipulating) return true;
        if (SubtitleBrowser.Visibility == Visibility.Visible) return true;
        foreach (var button in (ReadOnlySpan<VirtualKey>)[VirtualKey.LeftButton, VirtualKey.RightButton, VirtualKey.MiddleButton])
        {
            if (InputKeyboardSource.GetKeyStateForCurrentThread(button).HasFlag(CoreVirtualKeyStates.Down)) return true;
        }
        return false;
    }
}
