using System.Diagnostics;
using Edendale.Windows.Core;
using LibVLCSharp.Shared;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation.Peers;

namespace Edendale.Windows.Controls;

/// <summary>
/// The buffering indicator (apple-27.1 5020ec3): a gold ring in the play
/// button's place while the file opens, while LibVLC fills its buffer after
/// an open or a seek, and while a stream has stalled
/// (<see cref="PlaybackStallWatch"/>), also with the controls hidden. It
/// waits a quarter second, so quick local opens and seeks never flash it,
/// and goes at once.
/// </summary>
public sealed partial class PlayerControlsOverlay
{
    private static readonly TimeSpan BufferingDelay = TimeSpan.FromMilliseconds(250);

    /// <summary>Monotonic time for the stall watch and the idle pointer.</summary>
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    private readonly PlaybackStallWatch _stallWatch = new();
    private DispatcherQueueTimer _bufferingTimer = null!;

    /// <summary>LibVLC is opening the file: from Opening until it plays, pauses, or stops.</summary>
    private bool _opening;

    /// <summary>LibVLC's buffer is below 100 %.</summary>
    private bool _filling;

    private bool _stalled;
    private bool _bufferingShown;

    /// <summary>F.3's Applying layer shows its own progress while the engine reopens.</summary>
    private bool _bufferingSuppressed;

    private void InitializeBuffering()
    {
        _bufferingTimer = DispatcherQueue.CreateTimer();
        _bufferingTimer.Interval = BufferingDelay;
        _bufferingTimer.IsRepeating = false;
        _bufferingTimer.Tick += (_, _) =>
        {
            _bufferingTimer.Stop();
            if (IsBufferingNow()) ShowBuffering();
        };
    }

    /// <summary>The window's Applying layer is up (true) or gone (false).</summary>
    public void SuppressBuffering(bool suppressed)
    {
        _bufferingSuppressed = suppressed;
        UpdateBuffering();
    }

    /// <summary>
    /// Opening and LibVLC's buffer level, for the player <see cref="Attach"/>
    /// binds. LibVLC raises Buffering once per percent on its own thread, so
    /// only a change between filling and full reaches the UI thread.
    /// </summary>
    private Action AttachBuffering(MediaPlayer player)
    {
        var filling = false;
        EventHandler<EventArgs> opening = (_, _) => DispatcherQueue.TryEnqueue(() =>
        {
            if (!ReferenceEquals(player, _mediaPlayer)) return;
            _opening = true;
            _stallWatch.Reset();
            UpdateBuffering();
        });
        EventHandler<MediaPlayerBufferingEventArgs> buffering = (_, e) =>
        {
            var nowFilling = e.Cache < 100f;
            if (nowFilling == filling) return;
            filling = nowFilling;
            DispatcherQueue.TryEnqueue(() =>
            {
                if (!ReferenceEquals(player, _mediaPlayer)) return;
                _filling = nowFilling;
                UpdateBuffering();
            });
        };
        player.Opening += opening;
        player.Buffering += buffering;
        return () =>
        {
            player.Opening -= opening;
            player.Buffering -= buffering;
        };
    }

    /// <summary>Playing, pausing, or stopping ends an open; stopping ends a fill (UI thread).</summary>
    private void BufferingStateChanged(MediaPlayer player)
    {
        _opening = false;
        if (player.State is VLCState.Stopped or VLCState.Ended or VLCState.Error) _filling = false;
        UpdateBuffering();
    }

    /// <summary>The 250 ms progress tick: a clock that stands still while playing is a stall.</summary>
    private void WatchForStall(MediaPlayer player, long duration)
    {
        var stalled = _stallWatch.Observe(player.Time, duration, player.State == VLCState.Playing, _clock.Elapsed);
        if (stalled == _stalled) return;
        _stalled = stalled;
        UpdateBuffering();
    }

    /// <summary>A new player (or none) starts with nothing buffering.</summary>
    private void ResetBuffering()
    {
        _opening = false;
        _filling = false;
        _stalled = false;
        _stallWatch.Reset();
        _bufferingTimer.Stop();
        if (_bufferingShown) HideBuffering();
    }

    private bool IsBufferingNow() =>
        _mediaPlayer is { } player
        && !_bufferingSuppressed
        && PlayerLogic.IsBuffering(
            active: player.State is VLCState.Opening or VLCState.Buffering or VLCState.Playing,
            _opening, _filling, _stalled);

    private void UpdateBuffering()
    {
        if (IsBufferingNow())
        {
            if (!_bufferingShown && !_bufferingTimer.IsRunning) _bufferingTimer.Start();
            return;
        }
        _bufferingTimer.Stop();
        if (_bufferingShown) HideBuffering();
    }

    /// <summary>
    /// The ring takes the play button's place. The button only fades, so it
    /// keeps keyboard focus. Narrator hears "Buffering" when a playing stream
    /// stalls or refills; an open is expected, so it stays quiet then.
    /// </summary>
    private void ShowBuffering()
    {
        _bufferingShown = true;
        BufferingRing.IsActive = true;
        BufferingRing.Visibility = Visibility.Visible;
        CenterPlayPauseButton.Opacity = 0;
        CenterPlayPauseButton.IsHitTestVisible = false;
        if (_opening) return;
        var peer = FrameworkElementAutomationPeer.FromElement(BufferingRing)
            ?? FrameworkElementAutomationPeer.CreatePeerForElement(BufferingRing);
        peer?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
    }

    private void HideBuffering()
    {
        _bufferingShown = false;
        BufferingRing.IsActive = false;
        BufferingRing.Visibility = Visibility.Collapsed;
        CenterPlayPauseButton.Opacity = 1;
        CenterPlayPauseButton.IsHitTestVisible = true;
    }
}
