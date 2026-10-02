// Intel frame generation and upscaling (ENHANCEMENT.md G.3): the same
// algorithm as the CUDA kernels, as Direct3D 11 compute shaders
// (FrameGeneration/FrameGeneration.hlsl) compiled at run time. Registers
// follow the HLSL: t0 LumaA, t1 LumaB, t2 FieldIn, t3 ColorA, t4 ColorB,
// t5 MeanIn, t6 Chroma; u0 FieldOut, u1 MeanOut, u2 ColorOut; b0 Params.

using System.Runtime.InteropServices;
using Edendale.Windows.Core;
using SharpDX.Direct3D11;
using SharpDX.DXGI;
using Buffer = SharpDX.Direct3D11.Buffer;
using Device = SharpDX.Direct3D11.Device;

namespace Edendale.Windows.Services.FrameGeneration;

internal sealed class ComputeShaderFrameGenerator : IFrameGenerator
{
    /// <summary>The HLSL cbuffer Params: twelve ints, 48 bytes.</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct Parameters
    {
        public int Width;
        public int Height;
        public int BlocksX;
        public int BlocksY;
        public int OutWidth;
        public int OutHeight;
        public int RectX;
        public int RectY;
        public int RectWidth;
        public int RectHeight;
        public int Matrix;
        public int Count;
    }

    private const int ShaderResourceSlots = 7;
    private const int UnorderedAccessSlots = 3;

    private readonly Device _device;
    private readonly DeviceContext _context;
    private readonly ComputeShader _convert;
    private readonly ComputeShader _motionCoarse;
    private readonly ComputeShader _motionRefine;
    private readonly ComputeShader _smoothField;
    private readonly ComputeShader _fieldStats;
    private readonly ComputeShader _synthesize;
    private readonly ComputeShader _scale;
    private readonly Buffer _parameters;
    private readonly Dictionary<nint, UnorderedAccessView> _targets = [];
    private readonly List<IDisposable> _sized = [];

    private int _width;
    private int _height;
    private int _blocksX;
    private int _blocksY;
    private int _current;
    private bool _hasCurrent;
    private bool _hasPrevious;

    private readonly Texture2D?[] _luma = new Texture2D?[2];
    private readonly ShaderResourceView?[] _lumaViews = new ShaderResourceView?[2];
    private readonly ShaderResourceView?[] _colorViews = new ShaderResourceView?[2];
    private readonly UnorderedAccessView?[] _colorWrites = new UnorderedAccessView?[2];
    private Texture2D? _chroma;
    private ShaderResourceView? _chromaView;
    private ShaderResourceView? _middleView;
    private UnorderedAccessView? _middleWrite;
    private (ShaderResourceView Read, UnorderedAccessView Write) _coarse;
    private (ShaderResourceView Read, UnorderedAccessView Write) _refined;
    private (ShaderResourceView Read, UnorderedAccessView Write) _smoothed;
    private (ShaderResourceView Read, UnorderedAccessView Write) _meanCost;

    /// <summary>Compiles the shaders for <paramref name="device"/>. Call on the presenter thread.</summary>
    public ComputeShaderFrameGenerator(Device device)
    {
        _device = device;
        _context = device.ImmediateContext;
        var source = FrameGenerationAssets.Read("Edendale.FrameGeneration.hlsl");
        ComputeShader Shader(string entryPoint) => new(device, ShaderCompiler.Compile(source, entryPoint));
        _convert = Shader("Convert");
        _motionCoarse = Shader("MotionCoarse");
        _motionRefine = Shader("MotionRefine");
        _smoothField = Shader("SmoothField");
        _fieldStats = Shader("FieldStats");
        _synthesize = Shader("Synthesize");
        _scale = Shader("Scale");
        _parameters = new Buffer(device, Marshal.SizeOf<Parameters>(), ResourceUsage.Default, BindFlags.ConstantBuffer,
            CpuAccessFlags.None, ResourceOptionFlags.None, 0);
    }

    public FrameGenerationBackend Backend => FrameGenerationBackend.Direct3D;

    public bool HasPrevious => _hasPrevious;

    public void Submit(nint frame, int pitch, int width, int height)
    {
        if (width != _width || height != _height) Allocate(width, height);

        var next = _hasCurrent ? 1 - _current : _current;
        _context.UpdateSubresource(new SharpDX.DataBox(frame, pitch, 0), _luma[next]!, 0);
        _context.UpdateSubresource(new SharpDX.DataBox(frame + pitch * height, pitch, 0), _chroma!, 0);

        SetParameters(new Parameters { Width = width, Height = height, Matrix = FrameGenerationRules.ColorMatrix(height) });
        Run(_convert, Groups(width), Groups(height),
            read: [_lumaViews[next], null, null, null, null, null, _chromaView],
            write: [null, null, _colorWrites[next]]);

        _hasPrevious = _hasCurrent;
        _hasCurrent = true;
        _current = next;
    }

    public void Render(bool generated, Texture2D target, int outputWidth, int outputHeight, OutputRect rect)
    {
        if (!_hasCurrent) return;

        var source = _colorViews[_current];
        if (generated && _hasPrevious)
        {
            var previous = 1 - _current;
            var parameters = new Parameters
            {
                Width = _width,
                Height = _height,
                BlocksX = _blocksX,
                BlocksY = _blocksY,
                Count = _blocksX * _blocksY,
            };
            SetParameters(parameters);
            Run(_motionCoarse, _blocksX, _blocksY,
                read: [_lumaViews[previous], _lumaViews[_current]], write: [_coarse.Write]);
            Run(_motionRefine, _blocksX, _blocksY,
                read: [_lumaViews[previous], _lumaViews[_current], _coarse.Read], write: [_refined.Write]);
            Run(_smoothField, Groups(_blocksX), Groups(_blocksY),
                read: [null, null, _refined.Read], write: [_smoothed.Write]);
            Run(_fieldStats, 1, 1,
                read: [null, null, _smoothed.Read], write: [null, _meanCost.Write]);
            Run(_synthesize, Groups(_width), Groups(_height),
                read: [null, null, _smoothed.Read, _colorViews[previous], _colorViews[_current], _meanCost.Read],
                write: [null, null, _middleWrite]);
            source = _middleView;
        }

        SetParameters(new Parameters
        {
            Width = _width,
            Height = _height,
            OutWidth = outputWidth,
            OutHeight = outputHeight,
            RectX = rect.X,
            RectY = rect.Y,
            RectWidth = rect.Width,
            RectHeight = rect.Height,
        });
        Run(_scale, Groups(outputWidth), Groups(outputHeight),
            read: [null, null, null, source], write: [null, null, TargetView(target)]);
    }

    public void Forget(Texture2D target)
    {
        if (_targets.Remove(target.NativePointer, out var view)) view.Dispose();
    }

    public void Dispose()
    {
        foreach (var view in _targets.Values) view.Dispose();
        _targets.Clear();
        FreeSized();
        _parameters.Dispose();
        foreach (var shader in new[] { _convert, _motionCoarse, _motionRefine, _smoothField, _fieldStats, _synthesize, _scale })
        {
            shader.Dispose();
        }
    }

    private UnorderedAccessView TargetView(Texture2D target)
    {
        if (!_targets.TryGetValue(target.NativePointer, out var view))
        {
            view = new UnorderedAccessView(_device, target);
            _targets[target.NativePointer] = view;
        }
        return view;
    }

    private static int Groups(int items) => (items + 7) / 8;

    private void SetParameters(Parameters parameters) => _context.UpdateSubresource(ref parameters, _parameters);

    /// <summary>
    /// Binds exactly the given views (null clears a slot, so no resource is
    /// ever bound for reading and writing at once), dispatches, and unbinds.
    /// </summary>
    private void Run(ComputeShader shader, int groupsX, int groupsY, ShaderResourceView?[] read, UnorderedAccessView?[] write)
    {
        var stage = _context.ComputeShader;
        stage.Set(shader);
        stage.SetConstantBuffer(0, _parameters);
        for (var slot = 0; slot < UnorderedAccessSlots; slot++)
        {
            stage.SetUnorderedAccessView(slot, slot < write.Length ? write[slot] : null);
        }
        for (var slot = 0; slot < ShaderResourceSlots; slot++)
        {
            stage.SetShaderResource(slot, slot < read.Length ? read[slot] : null);
        }
        _context.Dispatch(Math.Max(1, groupsX), Math.Max(1, groupsY), 1);
        for (var slot = 0; slot < UnorderedAccessSlots; slot++) stage.SetUnorderedAccessView(slot, null);
        for (var slot = 0; slot < ShaderResourceSlots; slot++) stage.SetShaderResource(slot, null);
    }

    /// <summary>A new source size (the first frame, or a stream change): every sized resource is rebuilt.</summary>
    private void Allocate(int width, int height)
    {
        FreeSized();
        _width = width;
        _height = height;
        _blocksX = FrameGenerationRules.Blocks(width);
        _blocksY = FrameGenerationRules.Blocks(height);

        Texture2D Texture(int w, int h, Format format, BindFlags bind)
        {
            var texture = new Texture2D(_device, new Texture2DDescription
            {
                Width = w,
                Height = h,
                MipLevels = 1,
                ArraySize = 1,
                Format = format,
                SampleDescription = new SampleDescription(1, 0),
                Usage = ResourceUsage.Default,
                BindFlags = bind,
                CpuAccessFlags = CpuAccessFlags.None,
                OptionFlags = ResourceOptionFlags.None,
            });
            _sized.Add(texture);
            return texture;
        }

        T Keep<T>(T resource) where T : IDisposable
        {
            _sized.Add(resource);
            return resource;
        }

        (ShaderResourceView, UnorderedAccessView) Structured(int elements, int stride)
        {
            var buffer = Keep(new Buffer(_device, new BufferDescription
            {
                SizeInBytes = elements * stride,
                Usage = ResourceUsage.Default,
                BindFlags = BindFlags.ShaderResource | BindFlags.UnorderedAccess,
                CpuAccessFlags = CpuAccessFlags.None,
                OptionFlags = ResourceOptionFlags.BufferStructured,
                StructureByteStride = stride,
            }));
            return (Keep(new ShaderResourceView(_device, buffer)), Keep(new UnorderedAccessView(_device, buffer)));
        }

        for (var index = 0; index < 2; index++)
        {
            _luma[index] = Texture(width, height, Format.R8_UNorm, BindFlags.ShaderResource);
            _lumaViews[index] = Keep(new ShaderResourceView(_device, _luma[index]));
            var color = Texture(width, height, Format.R8G8B8A8_UNorm, BindFlags.ShaderResource | BindFlags.UnorderedAccess);
            _colorViews[index] = Keep(new ShaderResourceView(_device, color));
            _colorWrites[index] = Keep(new UnorderedAccessView(_device, color));
        }
        _chroma = Texture(width / 2, height / 2, Format.R8G8_UNorm, BindFlags.ShaderResource);
        _chromaView = Keep(new ShaderResourceView(_device, _chroma));
        var middle = Texture(width, height, Format.R8G8B8A8_UNorm, BindFlags.ShaderResource | BindFlags.UnorderedAccess);
        _middleView = Keep(new ShaderResourceView(_device, middle));
        _middleWrite = Keep(new UnorderedAccessView(_device, middle));

        var blocks = _blocksX * _blocksY;
        _coarse = Structured(blocks, 16);
        _refined = Structured(blocks, 16);
        _smoothed = Structured(blocks, 16);
        _meanCost = Structured(1, 4);

        _hasCurrent = false;
        _hasPrevious = false;
        _current = 0;
    }

    private void FreeSized()
    {
        // Views first, then the resources behind them.
        for (var index = _sized.Count - 1; index >= 0; index--) _sized[index].Dispose();
        _sized.Clear();
    }
}
