// The slice of the CUDA driver API (nvcuda.dll, installed with every NVIDIA
// display driver) that the CUDA frame generator needs. Edendale ships no CUDA
// runtime: the kernels arrive as PTX, which the driver compiles for the GPU
// in the machine.

using System.Runtime.InteropServices;

namespace Edendale.Windows.Services.FrameGeneration;

/// <summary>A CUDA driver call failed; the frame generator falls back to normal playback.</summary>
internal sealed class CudaException(string call, int result)
    : Exception($"{call} failed ({CudaDriver.ErrorName(result)})")
{
    public int Result { get; } = result;
}

internal static class CudaDriver
{
    private const string Library = "nvcuda.dll";

    public const int MemoryTypeDevice = 2;
    public const int MemoryTypeArray = 3;
    public const int MemoryTypeHost = 1;

    /// <summary>CUDA_MEMCPY2D (the _v2 layout): every pointer and size is 64-bit on x64 and ARM64.</summary>
    [StructLayout(LayoutKind.Sequential)]
    public struct Memcpy2D
    {
        public nuint SrcXInBytes;
        public nuint SrcY;
        public int SrcMemoryType;
        public nint SrcHost;
        public ulong SrcDevice;
        public nint SrcArray;
        public nuint SrcPitch;
        public nuint DstXInBytes;
        public nuint DstY;
        public int DstMemoryType;
        public nint DstHost;
        public ulong DstDevice;
        public nint DstArray;
        public nuint DstPitch;
        public nuint WidthInBytes;
        public nuint Height;
    }

    [DllImport(Library)] private static extern int cuInit(uint flags);
    [DllImport(Library)] private static extern int cuD3D11GetDevice(out int device, nint dxgiAdapter);
    [DllImport(Library, EntryPoint = "cuCtxCreate_v2")] private static extern int cuCtxCreate(out nint context, uint flags, int device);
    [DllImport(Library, EntryPoint = "cuCtxDestroy_v2")] private static extern int cuCtxDestroy(nint context);
    [DllImport(Library)] private static extern int cuCtxSetCurrent(nint context);
    [DllImport(Library)] private static extern int cuCtxSynchronize();
    [DllImport(Library)] private static extern int cuModuleLoadData(out nint module, byte[] image);
    [DllImport(Library)] private static extern int cuModuleUnload(nint module);
    [DllImport(Library)] private static extern int cuModuleGetFunction(out nint function, nint module, [MarshalAs(UnmanagedType.LPStr)] string name);
    [DllImport(Library, EntryPoint = "cuMemAlloc_v2")] private static extern int cuMemAlloc(out ulong pointer, nuint bytes);
    [DllImport(Library, EntryPoint = "cuMemFree_v2")] private static extern int cuMemFree(ulong pointer);
    [DllImport(Library, EntryPoint = "cuMemcpyHtoD_v2")] private static extern int cuMemcpyHtoD(ulong destination, nint source, nuint bytes);
    [DllImport(Library, EntryPoint = "cuMemcpyDtoD_v2")] private static extern int cuMemcpyDtoD(ulong destination, ulong source, nuint bytes);
    [DllImport(Library, EntryPoint = "cuMemcpy2D_v2")] private static extern int cuMemcpy2D(ref Memcpy2D copy);
    [DllImport(Library)]
    private static extern int cuLaunchKernel(
        nint function, uint gridX, uint gridY, uint gridZ, uint blockX, uint blockY, uint blockZ,
        uint sharedBytes, nint stream, nint parameters, nint extra);
    [DllImport(Library)] private static extern int cuGraphicsD3D11RegisterResource(out nint resource, nint d3dResource, uint flags);
    [DllImport(Library)] private static extern int cuGraphicsUnregisterResource(nint resource);
    [DllImport(Library)] private static extern int cuGraphicsMapResources(uint count, ref nint resources, nint stream);
    [DllImport(Library)] private static extern int cuGraphicsUnmapResources(uint count, ref nint resources, nint stream);
    [DllImport(Library)] private static extern int cuGraphicsSubResourceGetMappedArray(out nint array, nint resource, uint arrayIndex, uint mipLevel);
    [DllImport(Library)] private static extern int cuGetErrorName(int result, out nint name);

    private static void Check(int result, string call)
    {
        if (result != 0) throw new CudaException(call, result);
    }

    public static string ErrorName(int result)
    {
        try
        {
            return cuGetErrorName(result, out var name) == 0 && name != 0
                ? Marshal.PtrToStringAnsi(name) ?? result.ToString(System.Globalization.CultureInfo.InvariantCulture)
                : result.ToString(System.Globalization.CultureInfo.InvariantCulture);
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException)
        {
            return result.ToString(System.Globalization.CultureInfo.InvariantCulture);
        }
    }

    public static void Init() => Check(cuInit(0), nameof(cuInit));

    /// <summary>The CUDA device behind a DXGI adapter, so CUDA and Direct3D share one GPU.</summary>
    public static int DeviceFor(nint dxgiAdapter)
    {
        Check(cuD3D11GetDevice(out var device, dxgiAdapter), nameof(cuD3D11GetDevice));
        return device;
    }

    public static nint CreateContext(int device)
    {
        Check(cuCtxCreate(out var context, 0, device), nameof(cuCtxCreate));
        return context;
    }

    public static void DestroyContext(nint context) => cuCtxDestroy(context);

    public static void MakeCurrent(nint context) => Check(cuCtxSetCurrent(context), nameof(cuCtxSetCurrent));

    public static void Synchronize() => Check(cuCtxSynchronize(), nameof(cuCtxSynchronize));

    /// <summary>Loads PTX text; the driver JIT-compiles it for the installed GPU.</summary>
    public static nint LoadModule(string ptx)
    {
        var image = System.Text.Encoding.ASCII.GetBytes(ptx + "\0");
        Check(cuModuleLoadData(out var module, image), nameof(cuModuleLoadData));
        return module;
    }

    public static void UnloadModule(nint module) => cuModuleUnload(module);

    public static nint Function(nint module, string name)
    {
        Check(cuModuleGetFunction(out var function, module, name), $"{nameof(cuModuleGetFunction)}({name})");
        return function;
    }

    public static ulong Allocate(long bytes)
    {
        Check(cuMemAlloc(out var pointer, (nuint)Math.Max(1, bytes)), nameof(cuMemAlloc));
        return pointer;
    }

    public static void Free(ulong pointer)
    {
        if (pointer != 0) cuMemFree(pointer);
    }

    public static void CopyToDevice(ulong destination, nint source, long bytes) =>
        Check(cuMemcpyHtoD(destination, source, (nuint)bytes), nameof(cuMemcpyHtoD));

    public static void CopyDeviceToDevice(ulong destination, ulong source, long bytes) =>
        Check(cuMemcpyDtoD(destination, source, (nuint)bytes), nameof(cuMemcpyDtoD));

    /// <summary>Copies a pitched device image into a CUDA array (a mapped Direct3D texture).</summary>
    public static void CopyDeviceToArray(ulong source, long pitch, nint array, long widthInBytes, long height)
    {
        var copy = new Memcpy2D
        {
            SrcMemoryType = MemoryTypeDevice,
            SrcDevice = source,
            SrcPitch = (nuint)pitch,
            DstMemoryType = MemoryTypeArray,
            DstArray = array,
            WidthInBytes = (nuint)widthInBytes,
            Height = (nuint)height,
        };
        Check(cuMemcpy2D(ref copy), nameof(cuMemcpy2D));
    }

    /// <summary>
    /// Launches <paramref name="function"/>. Each argument is a pointer-sized
    /// or 32-bit value; the driver reads them through an array of pointers to
    /// the values, which lives in unmanaged memory for the call.
    /// </summary>
    public static void Launch(nint function, (uint X, uint Y) grid, (uint X, uint Y) block, params KernelArgument[] arguments)
    {
        var slot = 8;
        var values = Marshal.AllocHGlobal(slot * Math.Max(1, arguments.Length));
        var pointers = Marshal.AllocHGlobal(IntPtr.Size * Math.Max(1, arguments.Length));
        try
        {
            for (var index = 0; index < arguments.Length; index++)
            {
                var address = values + index * slot;
                arguments[index].Write(address);
                Marshal.WriteIntPtr(pointers, index * IntPtr.Size, address);
            }
            Check(cuLaunchKernel(function, grid.X, grid.Y, 1, block.X, block.Y, 1, 0, 0, pointers, 0), nameof(cuLaunchKernel));
        }
        finally
        {
            Marshal.FreeHGlobal(pointers);
            Marshal.FreeHGlobal(values);
        }
    }

    /// <summary>Registers a Direct3D 11 texture so CUDA can write into it.</summary>
    public static nint RegisterTexture(nint d3dResource)
    {
        Check(cuGraphicsD3D11RegisterResource(out var resource, d3dResource, 0), nameof(cuGraphicsD3D11RegisterResource));
        return resource;
    }

    public static void Unregister(nint resource)
    {
        if (resource != 0) cuGraphicsUnregisterResource(resource);
    }

    /// <summary>Maps a registered texture, hands its array to <paramref name="write"/>, and unmaps it.</summary>
    public static void WithMappedArray(nint resource, Action<nint> write)
    {
        Check(cuGraphicsMapResources(1, ref resource, 0), nameof(cuGraphicsMapResources));
        try
        {
            Check(cuGraphicsSubResourceGetMappedArray(out var array, resource, 0, 0), nameof(cuGraphicsSubResourceGetMappedArray));
            write(array);
        }
        finally
        {
            cuGraphicsUnmapResources(1, ref resource, 0);
        }
    }
}

/// <summary>One kernel argument: a device pointer (64-bit) or a 32-bit int.</summary>
internal readonly struct KernelArgument
{
    private readonly long _value;
    private readonly bool _wide;

    private KernelArgument(long value, bool wide)
    {
        _value = value;
        _wide = wide;
    }

    public static implicit operator KernelArgument(int value) => new(value, wide: false);

    public static KernelArgument Pointer(ulong devicePointer) => new((long)devicePointer, wide: true);

    public void Write(nint address)
    {
        if (_wide) Marshal.WriteInt64(address, _value);
        else Marshal.WriteInt32(address, (int)_value);
    }
}
