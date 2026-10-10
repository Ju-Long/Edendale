// E.1: reads the default DXGI adapter — the one LibVLCSharp's VideoView
// creates its Direct3D 11 device on — and AMD's AMF runtime, then hands the
// facts to the pure GpuCapabilities model. SharpDX.DXGI already ships with
// LibVLCSharp.WinUI, so no dependency is added. Probed once per launch, off
// the UI thread, and cached.

using System.Runtime.InteropServices;
using Edendale.Windows.Core;
using SharpDX.DXGI;

namespace Edendale.Windows.Services;

internal static class GpuProbe
{
    private static readonly Lazy<Task<GpuCapabilities>> Probe = new(() => Task.Run(Evaluate));

    /// <summary>Starts the probe (if needed) and returns its result.</summary>
    public static Task<GpuCapabilities> CapabilitiesAsync() => Probe.Value;

    /// <summary>The result once known; <see cref="GpuCapabilities.None"/> until then.</summary>
    public static GpuCapabilities Current =>
        Probe.IsValueCreated && Probe.Value.IsCompletedSuccessfully ? Probe.Value.Result : GpuCapabilities.None;

    private static GpuCapabilities Evaluate()
    {
        try
        {
            return GpuCapabilities.Evaluate(ReadDefaultAdapter());
        }
        catch (Exception error) when (error is SharpDX.SharpDXException or COMException or DllNotFoundException or InvalidOperationException)
        {
            // No DXGI (or no adapter): enhancement stays off and playback is unchanged.
            return GpuCapabilities.None;
        }
    }

    private static GpuAdapterInfo ReadDefaultAdapter()
    {
        using var factory = new Factory1();
        using var adapter = factory.GetAdapter1(0);
        var description = adapter.Description1;

        IReadOnlyList<int>? driver = null;
        if (adapter.IsInterfaceSupported<SharpDX.DXGI.Device>(out long umdVersion))
        {
            driver =
            [
                (int)((umdVersion >> 48) & 0xFFFF),
                (int)((umdVersion >> 32) & 0xFFFF),
                (int)((umdVersion >> 16) & 0xFFFF),
                (int)(umdVersion & 0xFFFF),
            ];
        }

        var architecture = RuntimeInformation.ProcessArchitecture;
        var (amfAvailable, amfVersion) = architecture == Architecture.X64 && description.VendorId == GpuCapabilities.AmdVendorId
            ? ProbeAmf()
            : (false, null);

        return new GpuAdapterInfo
        {
            VendorId = (uint)description.VendorId,
            DeviceId = (uint)description.DeviceId,
            Description = description.Description ?? "",
            IsSoftware = (description.Flags & AdapterFlags.Software) != 0,
            DriverVersion = driver,
            ProcessArchitecture = architecture,
            AmfAvailable = amfAvailable,
            AmfVersion = amfVersion,
            DedicatedGpuNotDefault = DedicatedGpuNotDefault(factory, description),
        };
    }

    /// <summary>
    /// A hybrid laptop: another hardware adapter from NVIDIA or AMD with more
    /// dedicated memory than the default one, so Windows is running Edendale
    /// on the integrated GPU.
    /// </summary>
    private static bool DedicatedGpuNotDefault(Factory1 factory, AdapterDescription1 defaultAdapter)
    {
        var count = factory.GetAdapterCount1();
        for (var index = 1; index < count; index++)
        {
            using var other = factory.GetAdapter1(index);
            var description = other.Description1;
            if ((description.Flags & AdapterFlags.Software) != 0) continue;
            var vendor = (uint)description.VendorId;
            if (vendor is not (GpuCapabilities.NvidiaVendorId or GpuCapabilities.AmdVendorId)) continue;
            if ((long)description.DedicatedVideoMemory > (long)defaultAdapter.DedicatedVideoMemory) return true;
        }
        return false;
    }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int AmfQueryVersion(out ulong version);

    /// <summary>Loads amfrt64.dll and reads AMFQueryVersion (major.minor.release.build in 16-bit parts).</summary>
    private static (bool Available, (int, int, int)? Version) ProbeAmf()
    {
        if (!NativeLibrary.TryLoad("amfrt64.dll", out var library)) return (false, null);
        try
        {
            if (!NativeLibrary.TryGetExport(library, "AMFQueryVersion", out var export)) return (true, null);
            var query = Marshal.GetDelegateForFunctionPointer<AmfQueryVersion>(export);
            if (query(out var packed) != 0) return (true, null);
            return (true, ((int)(packed >> 48), (int)((packed >> 32) & 0xFFFF), (int)((packed >> 16) & 0xFFFF)));
        }
        catch (Exception error) when (error is MarshalDirectiveException or SEHException)
        {
            return (true, null);
        }
        finally
        {
            NativeLibrary.Free(library);
        }
    }
}
