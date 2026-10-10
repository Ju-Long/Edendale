// X.1: an Xbox (or any XInput) controller drives the player while it is open
// and Edendale's window is in front. Windows.Gaming.Input is polled at 60 Hz
// on the UI thread; GamepadInterpreter turns readings into actions.

using System.Diagnostics;
using Edendale.Windows.Core;
using Microsoft.UI.Dispatching;
using Windows.Gaming.Input;

namespace Edendale.Windows.Services;

internal sealed class GamepadInput
{
    private readonly DispatcherQueueTimer _timer;
    private readonly Action<PadAction> _dispatch;
    private readonly GamepadInterpreter _interpreter = new();
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private bool _running;
    private bool _windowActive = true;

    public GamepadInput(DispatcherQueue queue, Action<PadAction> dispatch)
    {
        _dispatch = dispatch;
        _timer = queue.CreateTimer();
        _timer.Interval = TimeSpan.FromMilliseconds(16);
        _timer.Tick += (_, _) => Poll();
    }

    /// <summary>Polls while the player is open.</summary>
    public void SetRunning(bool running)
    {
        _running = running;
        UpdateTimer();
    }

    /// <summary>A controller stays with the window in front; another app gets it otherwise.</summary>
    public void SetWindowActive(bool active)
    {
        _windowActive = active;
        UpdateTimer();
    }

    private void UpdateTimer()
    {
        if (_running && _windowActive)
        {
            if (!_timer.IsRunning) _timer.Start();
            return;
        }
        _timer.Stop();
        foreach (var action in _interpreter.Reset()) _dispatch(action);
    }

    private void Poll()
    {
        Gamepad? pad;
        try
        {
            pad = Gamepad.Gamepads.FirstOrDefault();
        }
        catch (Exception)
        {
            return;
        }
        if (pad is null)
        {
            foreach (var action in _interpreter.Reset()) _dispatch(action);
            return;
        }

        var reading = pad.GetCurrentReading();
        var buttons = PadButtons.None;
        void Map(GamepadButtons source, PadButtons target)
        {
            if (reading.Buttons.HasFlag(source)) buttons |= target;
        }
        Map(GamepadButtons.A, PadButtons.A);
        Map(GamepadButtons.B, PadButtons.B);
        Map(GamepadButtons.X, PadButtons.X);
        Map(GamepadButtons.Y, PadButtons.Y);
        Map(GamepadButtons.LeftShoulder, PadButtons.LeftBumper);
        Map(GamepadButtons.RightShoulder, PadButtons.RightBumper);
        Map(GamepadButtons.DPadLeft, PadButtons.DPadLeft);
        Map(GamepadButtons.DPadRight, PadButtons.DPadRight);
        Map(GamepadButtons.DPadUp, PadButtons.DPadUp);
        Map(GamepadButtons.DPadDown, PadButtons.DPadDown);
        Map(GamepadButtons.Menu, PadButtons.Menu);
        Map(GamepadButtons.View, PadButtons.View);

        foreach (var action in _interpreter.Update(buttons, reading.LeftTrigger, reading.RightTrigger, _clock.Elapsed))
        {
            _dispatch(action);
        }
    }
}
