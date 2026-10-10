// X.2: previous, play/pause, and next buttons on the taskbar thumbnail while
// the player is open (ITaskbarList3). Windows accepts the buttons only after
// it announces the taskbar button ("TaskbarButtonCreated"), and reports
// clicks as WM_COMMAND, so the window is subclassed to hear both. Buttons
// can't be removed once added; outside the player they are hidden.

using System.Runtime.InteropServices;

namespace Edendale.Windows.Services;

internal enum TaskbarCommand
{
    Previous = 1,
    PlayPause = 2,
    Next = 3,
}

internal sealed class TaskbarButtons
{
    private const uint WmCommand = 0x0111;
    private const int ThbnClicked = 0x1800;
    private const uint ThbIcon = 0x2;
    private const uint ThbTooltip = 0x4;
    private const uint ThbFlags = 0x8;
    private const uint ThbfEnabled = 0x0;
    private const uint ThbfDisabled = 0x1;
    private const uint ThbfHidden = 0x8;
    private const uint ImageIcon = 1;
    private const uint LoadFromFile = 0x10;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ThumbButton
    {
        public uint dwMask;
        public uint iId;
        public uint iBitmap;
        public nint hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szTip;
        public uint dwFlags;
    }

    [ComImport]
    [Guid("ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ITaskbarList3
    {
        // ITaskbarList
        void HrInit();
        void AddTab(nint hwnd);
        void DeleteTab(nint hwnd);
        void ActivateTab(nint hwnd);
        void SetActiveAlt(nint hwnd);
        // ITaskbarList2
        void MarkFullscreenWindow(nint hwnd, [MarshalAs(UnmanagedType.Bool)] bool fullscreen);
        // ITaskbarList3
        void SetProgressValue(nint hwnd, ulong completed, ulong total);
        void SetProgressState(nint hwnd, int flags);
        void RegisterTab(nint tab, nint mdi);
        void UnregisterTab(nint tab);
        void SetTabOrder(nint tab, nint insertBefore);
        void SetTabActive(nint tab, nint mdi, uint reserved);
        void ThumbBarAddButtons(nint hwnd, uint count, [MarshalAs(UnmanagedType.LPArray)] ThumbButton[] buttons);
        void ThumbBarUpdateButtons(nint hwnd, uint count, [MarshalAs(UnmanagedType.LPArray)] ThumbButton[] buttons);
        void ThumbBarSetImageList(nint hwnd, nint imageList);
        void SetOverlayIcon(nint hwnd, nint icon, [MarshalAs(UnmanagedType.LPWStr)] string description);
        void SetThumbnailTooltip(nint hwnd, [MarshalAs(UnmanagedType.LPWStr)] string tip);
        void SetThumbnailClip(nint hwnd, nint clip);
    }

    [ComImport]
    [Guid("56fdf344-fd6d-11d0-958a-006097c9a090")]
    private class TaskbarListClass;

    private delegate nint SubclassProc(nint hwnd, uint message, nint wParam, nint lParam, nuint id, nuint data);

    [DllImport("comctl32.dll")]
    private static extern bool SetWindowSubclass(nint hwnd, SubclassProc callback, nuint id, nuint data);

    [DllImport("comctl32.dll")]
    private static extern nint DefSubclassProc(nint hwnd, uint message, nint wParam, nint lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint RegisterWindowMessage(string name);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern nint LoadImage(nint instance, string name, uint type, int width, int height, uint flags);

    private readonly nint _window;
    private readonly Action<TaskbarCommand> _dispatch;
    private readonly SubclassProc _procedure;
    private readonly uint _buttonCreated;
    private readonly nint _previousIcon;
    private readonly nint _playIcon;
    private readonly nint _pauseIcon;
    private readonly nint _nextIcon;
    private ITaskbarList3? _taskbar;
    private bool _added;
    private ButtonState _state = new(false, false, false, false);

    private sealed record ButtonState(bool Visible, bool Playing, bool HasPrevious, bool HasNext);

    private TaskbarButtons(nint window, Action<TaskbarCommand> dispatch)
    {
        _window = window;
        _dispatch = dispatch;
        // Kept in a field: the delegate must outlive the subclass.
        _procedure = WindowProcedure;
        _buttonCreated = RegisterWindowMessage("TaskbarButtonCreated");
        var folder = Path.Combine(AppContext.BaseDirectory, "Assets", "Taskbar");
        nint Icon(string name) => LoadImage(0, Path.Combine(folder, name + ".ico"), ImageIcon, 16, 16, LoadFromFile);
        _previousIcon = Icon("previous");
        _playIcon = Icon("play");
        _pauseIcon = Icon("pause");
        _nextIcon = Icon("next");
        SetWindowSubclass(window, _procedure, 0x45444E44, 0);
    }

    /// <summary>Null where the taskbar interfaces aren't available.</summary>
    public static TaskbarButtons? TryCreate(nint window, Action<TaskbarCommand> dispatch)
    {
        try
        {
            return new TaskbarButtons(window, dispatch);
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException or COMException)
        {
            return null;
        }
    }

    /// <summary>Shows the buttons for the item playing, or hides them when the player closes.</summary>
    public void Update(bool visible, bool playing, bool hasPrevious, bool hasNext)
    {
        _state = new ButtonState(visible, playing, hasPrevious, hasNext);
        Apply();
    }

    private void Apply()
    {
        try
        {
            if (_taskbar is null)
            {
                _taskbar = (ITaskbarList3)new TaskbarListClass();
                _taskbar.HrInit();
            }
            var buttons = Buttons();
            if (!_added)
            {
                _taskbar.ThumbBarAddButtons(_window, (uint)buttons.Length, buttons);
                _added = true;
            }
            else
            {
                _taskbar.ThumbBarUpdateButtons(_window, (uint)buttons.Length, buttons);
            }
        }
        catch (Exception error) when (error is COMException or InvalidCastException)
        {
            // Explorer isn't running or restarted: the next TaskbarButtonCreated retries.
            _taskbar = null;
            _added = false;
        }
    }

    private ThumbButton[] Buttons()
    {
        ThumbButton Button(TaskbarCommand id, nint icon, string tipKey, bool enabled) => new()
        {
            dwMask = ThbIcon | ThbTooltip | ThbFlags,
            iId = (uint)id,
            hIcon = icon,
            szTip = Loc.Get(tipKey),
            dwFlags = !_state.Visible ? ThbfHidden : enabled ? ThbfEnabled : ThbfDisabled,
        };
        return
        [
            Button(TaskbarCommand.Previous, _previousIcon, "Taskbar_Previous", _state.HasPrevious),
            Button(TaskbarCommand.PlayPause, _state.Playing ? _pauseIcon : _playIcon, _state.Playing ? "Taskbar_Pause" : "Taskbar_Play", true),
            Button(TaskbarCommand.Next, _nextIcon, "Taskbar_Next", _state.HasNext),
        ];
    }

    private nint WindowProcedure(nint hwnd, uint message, nint wParam, nint lParam, nuint id, nuint data)
    {
        if (message == _buttonCreated)
        {
            // A new taskbar (first launch, or Explorer restarted) takes the buttons again.
            _taskbar = null;
            _added = false;
            if (_state.Visible) Apply();
        }
        else if (message == WmCommand && ((int)wParam >> 16 & 0xFFFF) == ThbnClicked)
        {
            var command = (TaskbarCommand)((int)wParam & 0xFFFF);
            if (Enum.IsDefined(command)) _dispatch(command);
        }
        return DefSubclassProc(hwnd, message, wParam, lParam);
    }
}
