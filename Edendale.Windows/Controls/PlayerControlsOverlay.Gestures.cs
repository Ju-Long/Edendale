using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;

namespace Edendale.Windows.Controls;

/// <summary>
/// Pointer gestures on the video (3.1, W.1, W.2): a short click shows or
/// hides the controls, resting 0.4 s on the left or right half plays at that
/// side's hold speed until release (a drag does neither), a mouse double
/// click toggles full screen, a touch double tap skips or plays and pauses
/// by third, and the wheel changes the volume.
/// </summary>
public sealed partial class PlayerControlsOverlay
{
    private DispatcherQueueTimer _holdTimer = null!;
    private uint? _pressPointerId;
    private Point _pressPoint;
    private bool _pressDragged;
    private HoldSide? _holdSide;

    private void InitializeGestures()
    {
        _holdTimer = DispatcherQueue.CreateTimer();
        _holdTimer.Interval = PlayerLogic.HoldDelay;
        _holdTimer.IsRepeating = false;
        _holdTimer.Tick += (_, _) => HoldTimer_Tick();
    }

    /// <summary>
    /// True when the press landed on something with its own behavior: a
    /// control, or one of the panels and cards that float over the video.
    /// </summary>
    private bool IsInteractive(object? source)
    {
        for (var element = source as DependencyObject; element is not null && !ReferenceEquals(element, RootGrid); element = VisualTreeHelper.GetParent(element))
        {
            if (element is ButtonBase or RangeBase or ToggleSwitch or Selector or TextBox) return true;
            if (ReferenceEquals(element, SubtitleBrowser) || ReferenceEquals(element, UpNextCard) || ReferenceEquals(element, SkipSegmentButton))
            {
                return true;
            }
        }
        return false;
    }

    private void Surface_PointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (_mediaPlayer is null || IsInteractive(e.OriginalSource)) return;
        var point = e.GetCurrentPoint(RootGrid);
        if (point.PointerDeviceType == PointerDeviceType.Mouse && !point.Properties.IsLeftButtonPressed) return;

        _pressPointerId = e.Pointer.PointerId;
        _pressPoint = point.Position;
        _pressDragged = false;
        RootGrid.CapturePointer(e.Pointer);
        _holdTimer.Stop();
        _holdTimer.Start();
    }

    /// <summary>Movement past a few pixels makes the press a drag: no hold, no click.</summary>
    private void TrackPointerDrag(PointerRoutedEventArgs e)
    {
        if (_pressPointerId != e.Pointer.PointerId || _pressDragged) return;
        var position = e.GetCurrentPoint(RootGrid).Position;
        var dx = position.X - _pressPoint.X;
        var dy = position.Y - _pressPoint.Y;
        if (dx * dx + dy * dy < PlayerLogic.DragThreshold * PlayerLogic.DragThreshold) return;
        _pressDragged = true;
        if (_holdSide is null) _holdTimer.Stop();
    }

    private void HoldTimer_Tick()
    {
        _holdTimer.Stop();
        if (_pressPointerId is null || _pressDragged || _mediaPlayer is null) return;
        BeginHold(PlayerLogic.PointerHoldSide(_pressPoint.X, RootGrid.ActualWidth));
    }

    private void Surface_PointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (_pressPointerId != e.Pointer.PointerId) return;
        _holdTimer.Stop();
        RootGrid.ReleasePointerCapture(e.Pointer);
        _pressPointerId = null;

        if (_holdSide is not null)
        {
            EndHold();
        }
        else if (!_pressDragged)
        {
            // A short click shows or hides the controls.
            if (AreControlsShown) VisualStateManager.GoToState(this, "ControlsHidden", true);
            else ShowControls();
        }
    }

    private void Surface_PointerCanceled(object sender, PointerRoutedEventArgs e)
    {
        if (_pressPointerId != e.Pointer.PointerId) return;
        _holdTimer.Stop();
        _pressPointerId = null;
        if (_holdSide is not null) EndHold();
    }

    /// <summary>Plays at the side's hold speed (read now, so Settings changes apply at once).</summary>
    private void BeginHold(HoldSide side)
    {
        _holdSide = side;
        var rate = AppServices.Controls.HoldRate(side);
        _mediaPlayer?.SetRate((float)rate);
        ShowHud(PlayerLogic.ShortRateLabel(rate), null, persistent: true);
    }

    /// <summary>Releasing restores the base rate.</summary>
    private void EndHold()
    {
        if (_holdSide is null) return;
        _holdSide = null;
        _mediaPlayer?.SetRate((float)_baseRate);
        HideHud();
    }

    private void CancelHold()
    {
        _holdTimer?.Stop();
        _pressPointerId = null;
        if (_holdSide is not null)
        {
            _holdSide = null;
            HideHud();
        }
    }

    /// <summary>
    /// Mouse (and pen): full screen. Touch: the left and right thirds skip,
    /// the center plays or pauses. In the floating window any double tap
    /// restores the full window, as before.
    /// </summary>
    private void Surface_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (IsInteractive(e.OriginalSource)) return;
        e.Handled = true;

        if (e.PointerDeviceType == PointerDeviceType.Touch && !_isPictureInPicture)
        {
            switch (PlayerLogic.DoubleTapZone(e.GetPosition(RootGrid).X, RootGrid.ActualWidth))
            {
                case PlayerLogic.TapZone.Left:
                    Skip(SkipDirection.Backward);
                    break;
                case PlayerLogic.TapZone.Right:
                    Skip(SkipDirection.Forward);
                    break;
                default:
                    TogglePlayPause();
                    break;
            }
            return;
        }

        FullScreenRequested?.Invoke(this, new RoutedEventArgs());
    }

    private void Surface_PointerWheelChanged(object sender, PointerRoutedEventArgs e)
    {
        if (_mediaPlayer is null || IsInteractive(e.OriginalSource)) return;
        var delta = e.GetCurrentPoint(RootGrid).Properties.MouseWheelDelta;
        if (delta == 0) return;
        ChangeVolume(delta > 0 ? 1 : -1);
        e.Handled = true;
    }
}
