// X.4: in full screen the player's monitor switches to a refresh rate that is
// a whole multiple of the video's frame rate (RefreshRateMatching), at the
// same resolution and color depth, and switches back when full screen ends.
// CDS_FULLSCREEN makes the change temporary: Windows also restores the mode
// if the app exits without asking.

using System.Runtime.InteropServices;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

internal static class DisplayRefreshRate
{
    private const int EnumCurrentSettings = -1;
    private const uint CdsFullScreen = 0x00000004;
    private const int DispChangeSuccessful = 0;
    private const uint MonitorDefaultToNearest = 2;
    private const uint DmDisplayFrequency = 0x00400000;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DevMode
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public ushort dmSpecVersion;
        public ushort dmDriverVersion;
        public ushort dmSize;
        public ushort dmDriverExtra;
        public uint dmFields;
        public int dmPositionX;
        public int dmPositionY;
        public uint dmDisplayOrientation;
        public uint dmDisplayFixedOutput;
        public short dmColor;
        public short dmDuplex;
        public short dmYResolution;
        public short dmTTOption;
        public short dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public ushort dmLogPixels;
        public uint dmBitsPerPel;
        public uint dmPelsWidth;
        public uint dmPelsHeight;
        public uint dmDisplayFlags;
        public uint dmDisplayFrequency;
        public uint dmICMMethod;
        public uint dmICMIntent;
        public uint dmMediaType;
        public uint dmDitherType;
        public uint dmReserved1;
        public uint dmReserved2;
        public uint dmPanningWidth;
        public uint dmPanningHeight;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct MonitorInfoEx
    {
        public int cbSize;
        public Rect rcMonitor;
        public Rect rcWork;
        public uint dwFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szDevice;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect
    {
        public int Left, Top, Right, Bottom;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool EnumDisplaySettingsEx(string deviceName, int modeNumber, ref DevMode mode, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int ChangeDisplaySettingsEx(string deviceName, ref DevMode mode, nint window, uint flags, nint parameter);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "ChangeDisplaySettingsExW")]
    private static extern int ResetDisplaySettings(string deviceName, nint mode, nint window, uint flags, nint parameter);

    [DllImport("user32.dll")]
    private static extern nint MonitorFromWindow(nint window, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool GetMonitorInfo(nint monitor, ref MonitorInfoEx info);

    /// <summary>The monitor whose mode this app changed, to restore it.</summary>
    private static string? _changedDevice;

    /// <summary>Switches the window's monitor to a rate that fits <paramref name="frameRate"/>, if one does.</summary>
    public static void Match(nint window, double? frameRate)
    {
        try
        {
            var device = DeviceFor(window);
            if (device is null) return;
            var current = NewMode();
            if (!EnumDisplaySettingsEx(device, EnumCurrentSettings, ref current, 0)) return;

            var modes = new List<DevMode>();
            for (var index = 0; ; index++)
            {
                var mode = NewMode();
                if (!EnumDisplaySettingsEx(device, index, ref mode, 0)) break;
                if (mode.dmPelsWidth == current.dmPelsWidth && mode.dmPelsHeight == current.dmPelsHeight
                    && mode.dmBitsPerPel == current.dmBitsPerPel && mode.dmDisplayFrequency > 1)
                {
                    modes.Add(mode);
                }
            }

            var choice = RefreshRateMatching.Choose(frameRate, (int)current.dmDisplayFrequency, modes.Select(mode => (int)mode.dmDisplayFrequency));
            if (choice is not int hz) return;
            var target = modes.First(mode => mode.dmDisplayFrequency == hz);
            target.dmFields = DmDisplayFrequency;
            if (ChangeDisplaySettingsEx(device, ref target, 0, CdsFullScreen, 0) == DispChangeSuccessful)
            {
                _changedDevice = device;
            }
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException)
        {
            // Not on Windows; nothing to match.
        }
    }

    /// <summary>Puts the monitor back to its own mode.</summary>
    public static void Restore()
    {
        if (_changedDevice is not { } device) return;
        _changedDevice = null;
        try
        {
            ResetDisplaySettings(device, 0, 0, 0, 0);
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException)
        {
            // Not on Windows.
        }
    }

    private static DevMode NewMode() => new() { dmSize = (ushort)Marshal.SizeOf<DevMode>(), dmDeviceName = "", dmFormName = "" };

    private static string? DeviceFor(nint window)
    {
        var monitor = MonitorFromWindow(window, MonitorDefaultToNearest);
        if (monitor == 0) return null;
        var info = new MonitorInfoEx { cbSize = Marshal.SizeOf<MonitorInfoEx>(), szDevice = "" };
        return GetMonitorInfo(monitor, ref info) ? info.szDevice : null;
    }
}
