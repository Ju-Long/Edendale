// NVIDIA frame generation and upscaling (ENHANCEMENT.md G.2): Edendale's
// CUDA kernels (FrameGeneration/FrameGeneration.cu, shipped as PTX) run on
// the GPU behind the presenter's Direct3D device, and the result is copied
// straight into a Direct3D texture registered with CUDA. Nothing crosses
// back to the CPU.

using Edendale.Windows.Core;
using SharpDX.Direct3D11;

namespace Edendale.Windows.Services.FrameGeneration;

internal sealed class CudaFrameGenerator : IFrameGenerator
{
    private readonly nint _context;
    private readonly nint _module;
    private readonly nint _convert;
    private readonly nint _motionCoarse;
    private readonly nint _motionRefine;
    private readonly nint _smoothField;
    private readonly nint _fieldStats;
    private readonly nint _synthesize;
    private readonly nint _scale;
    private readonly Dictionary<nint, nint> _targets = [];

    private int _width;
    private int _height;
    private int _pitch;
    private int _blocksX;
    private int _blocksY;
    private int _current;
    private bool _hasCurrent;
    private bool _hasPrevious;

    // Two NV12 frames and their RGBA conversions, ping-ponged by _current.
    private readonly ulong[] _frames = new ulong[2];
    private readonly ulong[] _colors = new ulong[2];
    private ulong _coarse;
    private ulong _refined;
    private ulong _smoothed;
    private ulong _meanCost;
    private ulong _middle;
    private ulong _output;
    private long _outputBytes;

    /// <summary>Creates the CUDA context on the GPU behind <paramref name="device"/>. Call on the presenter thread.</summary>
    public CudaFrameGenerator(Device device)
    {
        CudaDriver.Init();
        using var dxgiDevice = device.QueryInterface<SharpDX.DXGI.Device>();
        using var adapter = dxgiDevice.Adapter;
        _context = CudaDriver.CreateContext(CudaDriver.DeviceFor(adapter.NativePointer));
        try
        {
            CudaDriver.MakeCurrent(_context);
            _module = CudaDriver.LoadModule(FrameGenerationAssets.Read("Edendale.FrameGeneration.ptx"));
            _convert = CudaDriver.Function(_module, "nv12_to_rgba");
            _motionCoarse = CudaDriver.Function(_module, "motion_coarse");
            _motionRefine = CudaDriver.Function(_module, "motion_refine");
            _smoothField = CudaDriver.Function(_module, "smooth_field");
            _fieldStats = CudaDriver.Function(_module, "field_stats");
            _synthesize = CudaDriver.Function(_module, "synthesize");
            _scale = CudaDriver.Function(_module, "scale");
        }
        catch
        {
            if (_module != 0) CudaDriver.UnloadModule(_module);
            CudaDriver.DestroyContext(_context);
            throw;
        }
    }

    public FrameGenerationBackend Backend => FrameGenerationBackend.Cuda;

    public bool HasPrevious => _hasPrevious;

    public void Submit(nint frame, int pitch, int width, int height)
    {
        CudaDriver.MakeCurrent(_context);
        if (width != _width || height != _height || pitch != _pitch) Allocate(width, height, pitch);

        var next = _hasCurrent ? 1 - _current : _current;
        CudaDriver.CopyToDevice(_frames[next], frame, (long)pitch * height * 3 / 2);
        CudaDriver.Launch(_convert, Grid(width, height), (8, 8),
            KernelArgument.Pointer(_frames[next]), KernelArgument.Pointer(_frames[next] + (ulong)((long)pitch * height)),
            pitch, width, height, FrameGenerationRules.ColorMatrix(height), KernelArgument.Pointer(_colors[next]));

        _hasPrevious = _hasCurrent;
        _hasCurrent = true;
        _current = next;
    }

    public void Render(bool generated, Texture2D target, int outputWidth, int outputHeight, OutputRect rect)
    {
        if (!_hasCurrent) return;
        CudaDriver.MakeCurrent(_context);

        var source = _colors[_current];
        if (generated && _hasPrevious)
        {
            var previous = 1 - _current;
            var a = _frames[previous];
            var b = _frames[_current];
            CudaDriver.Launch(_motionCoarse, ((uint)_blocksX, (uint)_blocksY), (128, 1),
                KernelArgument.Pointer(a), KernelArgument.Pointer(b), _pitch, _width, _height, _blocksX, KernelArgument.Pointer(_coarse));
            CudaDriver.Launch(_motionRefine, ((uint)_blocksX, (uint)_blocksY), (32, 1),
                KernelArgument.Pointer(a), KernelArgument.Pointer(b), _pitch, _width, _height, _blocksX,
                KernelArgument.Pointer(_coarse), KernelArgument.Pointer(_refined));
            CudaDriver.Launch(_smoothField, Grid(_blocksX, _blocksY), (8, 8),
                KernelArgument.Pointer(_refined), KernelArgument.Pointer(_smoothed), _blocksX, _blocksY);
            CudaDriver.Launch(_fieldStats, (1, 1), (256, 1),
                KernelArgument.Pointer(_smoothed), _blocksX * _blocksY, KernelArgument.Pointer(_meanCost));
            CudaDriver.Launch(_synthesize, Grid(_width, _height), (8, 8),
                KernelArgument.Pointer(_colors[previous]), KernelArgument.Pointer(_colors[_current]), _width, _height,
                KernelArgument.Pointer(_smoothed), _blocksX, _blocksY, KernelArgument.Pointer(_meanCost), KernelArgument.Pointer(_middle));
            source = _middle;
        }

        var bytes = (long)outputWidth * outputHeight * 4;
        if (bytes != _outputBytes)
        {
            CudaDriver.Free(_output);
            _output = CudaDriver.Allocate(bytes);
            _outputBytes = bytes;
        }
        CudaDriver.Launch(_scale, Grid(outputWidth, outputHeight), (8, 8),
            KernelArgument.Pointer(source), _width, _height, KernelArgument.Pointer(_output), outputWidth, outputHeight,
            rect.X, rect.Y, rect.Width, rect.Height);

        var resource = Registered(target);
        var output = _output;
        CudaDriver.WithMappedArray(resource, array =>
            CudaDriver.CopyDeviceToArray(output, (long)outputWidth * 4, array, (long)outputWidth * 4, outputHeight));
    }

    public void Forget(Texture2D target)
    {
        if (!_targets.Remove(target.NativePointer, out var resource)) return;
        CudaDriver.MakeCurrent(_context);
        CudaDriver.Unregister(resource);
    }

    public void Dispose()
    {
        CudaDriver.MakeCurrent(_context);
        foreach (var resource in _targets.Values) CudaDriver.Unregister(resource);
        _targets.Clear();
        FreeFrames();
        CudaDriver.Free(_output);
        CudaDriver.UnloadModule(_module);
        CudaDriver.DestroyContext(_context);
    }

    private nint Registered(Texture2D target)
    {
        if (!_targets.TryGetValue(target.NativePointer, out var resource))
        {
            resource = CudaDriver.RegisterTexture(target.NativePointer);
            _targets[target.NativePointer] = resource;
        }
        return resource;
    }

    private static (uint X, uint Y) Grid(int width, int height) => ((uint)((width + 7) / 8), (uint)((height + 7) / 8));

    /// <summary>A new source size (the first frame, or a stream change): every buffer is rebuilt.</summary>
    private void Allocate(int width, int height, int pitch)
    {
        FreeFrames();
        _width = width;
        _height = height;
        _pitch = pitch;
        _blocksX = FrameGenerationRules.Blocks(width);
        _blocksY = FrameGenerationRules.Blocks(height);
        var blocks = (long)_blocksX * _blocksY * 16;
        for (var index = 0; index < 2; index++)
        {
            _frames[index] = CudaDriver.Allocate((long)pitch * height * 3 / 2);
            _colors[index] = CudaDriver.Allocate((long)width * height * 4);
        }
        _coarse = CudaDriver.Allocate(blocks);
        _refined = CudaDriver.Allocate(blocks);
        _smoothed = CudaDriver.Allocate(blocks);
        _meanCost = CudaDriver.Allocate(4);
        _middle = CudaDriver.Allocate((long)width * height * 4);
        _hasCurrent = false;
        _hasPrevious = false;
        _current = 0;
    }

    private void FreeFrames()
    {
        for (var index = 0; index < 2; index++)
        {
            CudaDriver.Free(_frames[index]);
            CudaDriver.Free(_colors[index]);
            _frames[index] = 0;
            _colors[index] = 0;
        }
        CudaDriver.Free(_coarse);
        CudaDriver.Free(_refined);
        CudaDriver.Free(_smoothed);
        CudaDriver.Free(_meanCost);
        CudaDriver.Free(_middle);
        _coarse = _refined = _smoothed = _meanCost = _middle = 0;
    }
}
