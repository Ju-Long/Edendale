using System.Runtime.InteropServices;

namespace Edendale.Windows.Core;

public enum GpuVendor
{
    Unknown,
    Nvidia,
    Amd,
    Intel,
    Qualcomm,
    /// <summary>The Microsoft Basic Render Driver (a VM or Remote Desktop).</summary>
    Software,
}

/// <summary>Whether LibVLC's super resolution will run: Intel can't be detected, so it is "maybe".</summary>
public enum CapabilitySupport
{
    No,
    Maybe,
    Yes,
}

/// <summary>What the DXGI probe reads about the default adapter. Plain data, so tests can build any machine.</summary>
public sealed record GpuAdapterInfo
{
    public uint VendorId { get; init; }
    public uint DeviceId { get; init; }
    public string Description { get; init; } = "";

    /// <summary>DXGI's software flag (or the Microsoft Basic Render Driver ids).</summary>
    public bool IsSoftware { get; init; }

    /// <summary>The user-mode driver version as four parts, e.g. 31.0.15.3118; null when unknown.</summary>
    public IReadOnlyList<int>? DriverVersion { get; init; }

    public Architecture ProcessArchitecture { get; init; } = Architecture.X64;

    /// <summary>amfrt64.dll loaded (AMD's Advanced Media Framework runtime).</summary>
    public bool AmfAvailable { get; init; }

    /// <summary>AMFQueryVersion as (major, minor, release); null when unknown.</summary>
    public (int Major, int Minor, int Release)? AmfVersion { get; init; }

    /// <summary>
    /// A dedicated GPU exists but isn't the default adapter (a hybrid laptop
    /// running Edendale on its integrated GPU).
    /// </summary>
    public bool DedicatedGpuNotDefault { get; init; }
}

/// <summary>
/// What Option A can use on this machine (ENHANCEMENT.md E.1): LibVLC 3's
/// d3d11 upscaler modes and AMD's AMF filters. Pure, so every vendor,
/// driver boundary, and architecture is unit-tested.
/// </summary>
public sealed record GpuCapabilities
{
    public const uint NvidiaVendorId = 0x10DE;
    public const uint AmdVendorId = 0x1002;
    public const uint IntelVendorId = 0x8086;
    public const uint QualcommVendorId = 0x5143;
    public const uint MicrosoftVendorId = 0x1414;
    public const uint BasicRenderDeviceId = 0x8C;

    /// <summary>VLC's NVIDIA check: the driver's last two parts as third × 10000 + fourth must exceed this.</summary>
    public const int NvidiaSuperResolutionDriverFloor = 153_000;

    /// <summary>The AMF runtime VLC's source names for the frame-rate doubler.</summary>
    public static readonly (int Major, int Minor, int Release) MinimumAmfForFrameRateDoubler = (1, 4, 34);

    public GpuVendor Vendor { get; init; }
    public CapabilitySupport SuperResolution { get; init; }

    /// <summary>AMD's frame-rate doubler (amf_frc).</summary>
    public bool MotionSmoothing { get; init; }

    /// <summary>AMD's denoise and artifact removal (amf_vqenhancer).</summary>
    public bool GpuDenoise { get; init; }

    /// <summary>The driver's video-processor scaler (any hardware GPU).</summary>
    public bool VideoProcessor { get; init; }

    public bool IsSoftwareAdapter => Vendor == GpuVendor.Software;

    /// <summary>For E.4: explain the "High performance" graphics setting.</summary>
    public bool RunningOnIntegratedGpu { get; init; }

    /// <summary>A machine with nothing but bilinear scaling: the software adapter, or no probe yet.</summary>
    public static GpuCapabilities None { get; } = new() { Vendor = GpuVendor.Unknown };

    public static GpuVendor VendorFor(GpuAdapterInfo adapter)
    {
        if (adapter.IsSoftware || adapter.VendorId == MicrosoftVendorId && adapter.DeviceId == BasicRenderDeviceId)
        {
            return GpuVendor.Software;
        }
        return adapter.VendorId switch
        {
            NvidiaVendorId => GpuVendor.Nvidia,
            AmdVendorId => GpuVendor.Amd,
            IntelVendorId => GpuVendor.Intel,
            QualcommVendorId => GpuVendor.Qualcomm,
            _ => GpuVendor.Unknown,
        };
    }

    /// <summary>VLC's driver test for RTX Video Super Resolution (31.0.15.3118 gives 153118).</summary>
    public static bool NvidiaDriverSupportsSuperResolution(IReadOnlyList<int>? driverVersion) =>
        driverVersion is { Count: 4 } parts && parts[2] * 10_000 + parts[3] > NvidiaSuperResolutionDriverFloor;

    public static GpuCapabilities Evaluate(GpuAdapterInfo adapter)
    {
        var vendor = VendorFor(adapter);
        if (vendor == GpuVendor.Software)
        {
            return new GpuCapabilities { Vendor = vendor };
        }

        // AMD's AMF filters ship only in the x64 LibVLC build.
        var amd = vendor == GpuVendor.Amd
            && adapter.ProcessArchitecture == Architecture.X64
            && adapter.AmfAvailable;
        var doublerRuntime = adapter.AmfVersion is not { } version
            || version.CompareTo(MinimumAmfForFrameRateDoubler) >= 0;

        var superResolution = vendor switch
        {
            GpuVendor.Nvidia => NvidiaDriverSupportsSuperResolution(adapter.DriverVersion) ? CapabilitySupport.Yes : CapabilitySupport.No,
            GpuVendor.Intel => CapabilitySupport.Maybe,
            GpuVendor.Amd => amd ? CapabilitySupport.Yes : CapabilitySupport.No,
            _ => CapabilitySupport.No,
        };

        return new GpuCapabilities
        {
            Vendor = vendor,
            SuperResolution = superResolution,
            MotionSmoothing = amd && doublerRuntime,
            GpuDenoise = amd,
            VideoProcessor = true,
            RunningOnIntegratedGpu = adapter.DedicatedGpuNotDefault,
        };
    }
}
