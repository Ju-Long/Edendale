// Option B's video output (ENHANCEMENT.md G.4). With frame generation on,
// LibVLC hands each decoded frame to these callbacks as NV12 in memory
// instead of drawing it, and this presenter draws it with Edendale's own
// generator and upscaler into a Direct3D 11 swap chain on a SwapChainPanel.
//
// Threads: LibVLC's video output thread runs the callbacks and only touches
// the frame pool; one presenter thread owns the immediate context, the
// generator, and the swap chain; the UI thread starts, resizes, and stops it.
// Any GPU failure raises Failed once, and the player reopens without frame
// generation.

using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using Edendale.Windows.Core;
using LibVLCSharp.Shared;
using Microsoft.UI.Xaml.Controls;
using SharpDX.Direct3D11;
using SharpDX.DXGI;
using Device = SharpDX.Direct3D11.Device;

namespace Edendale.Windows.Services.FrameGeneration;

/// <summary>WinUI 3's ISwapChainPanelNative, as LibVLCSharp.WinUI declares it.</summary>
[Guid("63aad0b8-7c24-40ff-85a8-640d944cc325")]
internal sealed class SwapChainPanelNative : SharpDX.DXGI.ISwapChainPanelNative
{
    public SwapChainPanelNative(nint nativePointer)
        : base(nativePointer)
    {
    }
}

internal sealed class FrameGenerationPresenter : IDisposable
{
    private const int PoolSize = 8;
    private const int PictureCount = 6;

    private readonly SwapChainPanel _panel;
    private readonly FrameGenerationBackend _backend;
    private readonly double _nominalInterval;
    private readonly uint _sarNum;
    private readonly uint _sarDen;
    private readonly FramePool _pool = new();
    private readonly ManualResetEventSlim _wake = new(false);
    private readonly object _gate = new();

    // LibVLC keeps only native pointers to these, so they must live as long as the player.
    private readonly MediaPlayer.LibVLCVideoFormatCb _formatCallback;
    private readonly MediaPlayer.LibVLCVideoCleanupCb _cleanupCallback;
    private readonly MediaPlayer.LibVLCVideoLockCb _lockCallback;
    private readonly MediaPlayer.LibVLCVideoUnlockCb _unlockCallback;
    private readonly MediaPlayer.LibVLCVideoDisplayCb _displayCallback;

    private Device? _device;
    private SwapChain1? _swapChain;
    private Thread? _thread;
    private MediaPlayer? _player;
    private volatile bool _stopping;
    private volatile bool _failed;
    private bool _fill;
    private (int Width, int Height, float ScaleX, float ScaleY) _size;
    private bool _sizeChanged;
    private bool _redraw;

    /// <summary>Raised once, on any thread, when the GPU path can't continue; the message is for diagnostics only.</summary>
    public event Action<string>? Failed;

    /// <param name="frameRate">The source's frame rate (frame generation needs one).</param>
    public FrameGenerationPresenter(SwapChainPanel panel, FrameGenerationBackend backend, double frameRate, uint sarNum, uint sarDen, bool fill)
    {
        _panel = panel;
        _backend = backend;
        _nominalInterval = 1 / frameRate;
        _sarNum = sarNum;
        _sarDen = sarDen;
        _fill = fill;
        _formatCallback = SetupFormat;
        _cleanupCallback = Cleanup;
        _lockCallback = Lock;
        _unlockCallback = Unlock;
        _displayCallback = Display;
    }

    public FrameGenerationBackend Backend => _backend;

    /// <summary>How far video runs behind LibVLC at normal speed; the player delays audio by as much.</summary>
    public int DelayMilliseconds => (int)Math.Round(_nominalInterval * 500);

    /// <summary>Fit or Fill, applied from the next drawn frame (or at once while paused).</summary>
    public bool Fill
    {
        set
        {
            lock (_gate)
            {
                _fill = value;
                _redraw = true;
            }
            _wake.Set();
        }
    }

    /// <summary>
    /// Creates the device and swap chain, puts the swap chain on the panel,
    /// starts the presenter thread, and routes <paramref name="player"/>'s
    /// frames here. Call on the UI thread before playback starts.
    /// </summary>
    public void Start(MediaPlayer player)
    {
        _size = PanelSize();
        _device = new Device(SharpDX.Direct3D.DriverType.Hardware, DeviceCreationFlags.BgraSupport);
        using (var dxgiDevice = _device.QueryInterface<SharpDX.DXGI.Device2>())
        using (var adapter = dxgiDevice.Adapter)
        using (var factory = adapter.GetParent<Factory2>())
        {
            var description = new SwapChainDescription1
            {
                Width = Math.Max(1, _size.Width),
                Height = Math.Max(1, _size.Height),
                Format = Format.R8G8B8A8_UNorm,
                Stereo = false,
                SampleDescription = new SampleDescription(1, 0),
                Usage = Usage.RenderTargetOutput,
                BufferCount = 2,
                Scaling = Scaling.Stretch,
                SwapEffect = SwapEffect.FlipSequential,
                AlphaMode = AlphaMode.Ignore,
                Flags = SwapChainFlags.None,
            };
            _swapChain = new SwapChain1(factory, _device, ref description, null);
            dxgiDevice.MaximumFrameLatency = 1;
        }
        ApplyScale(_size.ScaleX, _size.ScaleY);
        using (var native = SharpDX.ComObject.As<SwapChainPanelNative>(_panel))
        {
            native.SwapChain = _swapChain;
        }

        _player = player;
        player.SetVideoFormatCallbacks(_formatCallback, _cleanupCallback);
        player.SetVideoCallbacks(_lockCallback, _unlockCallback, _displayCallback);

        _thread = new Thread(Run) { IsBackground = true, Name = "Edendale frame generation", Priority = ThreadPriority.AboveNormal };
        _thread.Start();
    }

    /// <summary>The panel's size or DPI changed. UI thread.</summary>
    public void Resize()
    {
        var size = PanelSize();
        lock (_gate)
        {
            if (size == _size) return;
            _size = size;
            _sizeChanged = true;
        }
        _wake.Set();
    }

    /// <summary>Stops the thread and releases the GPU. Call after the player has stopped.</summary>
    public void Dispose()
    {
        _stopping = true;
        _wake.Set();
        var stopped = _thread?.Join(TimeSpan.FromSeconds(2)) ?? true;
        using (var native = SharpDX.ComObject.As<SwapChainPanelNative>(_panel))
        {
            native.SwapChain = null;
        }
        _swapChain?.Dispose();
        _device?.Dispose();
        _pool.Dispose();
        // A thread still finishing a GPU call keeps its event; it exits on _stopping.
        if (stopped) _wake.Dispose();
    }

    private (int Width, int Height, float ScaleX, float ScaleY) PanelSize()
    {
        var scaleX = _panel.CompositionScaleX > 0 ? _panel.CompositionScaleX : 1;
        var scaleY = _panel.CompositionScaleY > 0 ? _panel.CompositionScaleY : 1;
        return ((int)Math.Round(_panel.ActualWidth * scaleX), (int)Math.Round(_panel.ActualHeight * scaleY), scaleX, scaleY);
    }

    /// <summary>The swap chain is in physical pixels; composition scales by DPI, so undo that.</summary>
    private void ApplyScale(float scaleX, float scaleY)
    {
        using var swapChain2 = _swapChain!.QueryInterface<SwapChain2>();
        swapChain2.MatrixTransform = new SharpDX.Mathematics.Interop.RawMatrix3x2 { M11 = 1 / scaleX, M22 = 1 / scaleY };
    }

    private void Fail(string reason)
    {
        if (_failed) return;
        _failed = true;
        Failed?.Invoke(reason);
    }

    // ------------------------------------------------------------------
    // LibVLC callbacks (its video output thread)
    // ------------------------------------------------------------------

    private uint SetupFormat(ref nint opaque, nint chroma, ref uint width, ref uint height, ref uint pitches, ref uint lines)
    {
        var source = Marshal.PtrToStringAnsi(chroma, 4);
        if (FrameGenerationRules.IsHighBitDepth(source)) Fail("high bit depth source " + source);

        Marshal.Copy("NV12"u8.ToArray(), 0, chroma, 4);
        var frameWidth = (int)((width + 1) & ~1u);
        var frameHeight = (int)((height + 1) & ~1u);
        width = (uint)frameWidth;
        height = (uint)frameHeight;
        var pitch = (frameWidth + 63) & ~63;
        pitches = (uint)pitch;
        Unsafe.Add(ref pitches, 1) = (uint)pitch;
        lines = (uint)frameHeight;
        Unsafe.Add(ref lines, 1) = (uint)(frameHeight / 2);
        _pool.Allocate(frameWidth, frameHeight, pitch, PoolSize);
        return PictureCount;
    }

    private void Cleanup(ref nint opaque) => _pool.Retire();

    private nint Lock(nint opaque, nint planes)
    {
        var id = _pool.Lock();
        var buffer = _pool.Pointer(id);
        Marshal.WriteIntPtr(planes, 0, buffer);
        Marshal.WriteIntPtr(planes, IntPtr.Size, buffer + _pool.Pitch * _pool.Height);
        return id;
    }

    private void Unlock(nint opaque, nint picture, nint planes) => _pool.Unlock((int)picture);

    private void Display(nint opaque, nint picture)
    {
        if (_pool.Display((int)picture, Stopwatch.GetTimestamp())) _wake.Set();
    }

    // ------------------------------------------------------------------
    // The presenter thread
    // ------------------------------------------------------------------

    [DllImport("winmm.dll")] private static extern uint timeBeginPeriod(uint period);
    [DllImport("winmm.dll")] private static extern uint timeEndPeriod(uint period);

    private void Run()
    {
        // Half-frame waits need millisecond timers rather than the default 15.6 ms.
        timeBeginPeriod(1);
        IFrameGenerator? generator = null;
        Texture2D? generatedTarget = null;
        Texture2D? realTarget = null;
        var clock = new FrameGenerationClock(_nominalInterval);
        try
        {
            generator = _backend == FrameGenerationBackend.Cuda
                ? new CudaFrameGenerator(_device!)
                : new ComputeShaderFrameGenerator(_device!);

            var outputWidth = 0;
            var outputHeight = 0;
            var hasFrame = false;
            while (!_stopping)
            {
                _wake.Wait(50);
                _wake.Reset();
                if (_stopping) break;

                bool sizeChanged, redraw, fill;
                (int Width, int Height, float ScaleX, float ScaleY) size;
                lock (_gate)
                {
                    sizeChanged = _sizeChanged;
                    redraw = _redraw;
                    fill = _fill;
                    size = _size;
                    _sizeChanged = false;
                    _redraw = false;
                }

                if (sizeChanged || generatedTarget is null)
                {
                    outputWidth = Math.Max(1, size.Width);
                    outputHeight = Math.Max(1, size.Height);
                    if (generatedTarget is not null) generator.Forget(generatedTarget);
                    if (realTarget is not null) generator.Forget(realTarget);
                    generatedTarget?.Dispose();
                    realTarget?.Dispose();
                    _swapChain!.ResizeBuffers(2, outputWidth, outputHeight, Format.R8G8B8A8_UNorm, SwapChainFlags.None);
                    ApplyScale(size.ScaleX, size.ScaleY);
                    generatedTarget = Target(outputWidth, outputHeight);
                    realTarget = Target(outputWidth, outputHeight);
                    redraw = true;
                }

                var rect = new Func<OutputRect>(() => FrameGenerationRules.DestinationRect(
                    _pool.Width, _pool.Height, _sarNum, _sarDen, outputWidth, outputHeight, fill));

                if (_pool.TryTake(out var index, out var arrival))
                {
                    try
                    {
                        generator.Submit(_pool.Pointer(index + 1), _pool.Pitch, _pool.Width, _pool.Height);
                    }
                    finally
                    {
                        _pool.Release(index);
                    }
                    hasFrame = true;

                    var now = Seconds(arrival);
                    var plan = clock.Arrive(now);
                    var rate = _player?.Rate ?? 1f;
                    if (plan.Interpolate && generator.HasPrevious && FrameGenerationRules.GeneratesAt(rate))
                    {
                        generator.Render(true, generatedTarget!, outputWidth, outputHeight, rect());
                        WaitUntil(plan.GeneratedAt);
                        Present(generatedTarget!);
                    }
                    generator.Render(false, realTarget!, outputWidth, outputHeight, rect());
                    WaitUntil(plan.RealAt);
                    Present(realTarget!);
                }
                else if (redraw && hasFrame)
                {
                    // Paused, resized, or Fit/Fill changed: draw the current frame again.
                    generator.Render(false, realTarget!, outputWidth, outputHeight, rect());
                    Present(realTarget!);
                }
            }
        }
        catch (Exception error)
        {
            // Anything thrown here would end the process (it's a background
            // thread), so every failure falls back to normal playback instead.
            Fail(error.Message);
        }
        finally
        {
            if (generatedTarget is not null) generator?.Forget(generatedTarget);
            if (realTarget is not null) generator?.Forget(realTarget);
            generatedTarget?.Dispose();
            realTarget?.Dispose();
            generator?.Dispose();
            timeEndPeriod(1);
        }
    }

    private Texture2D Target(int width, int height) => new(_device!, new Texture2DDescription
    {
        Width = width,
        Height = height,
        MipLevels = 1,
        ArraySize = 1,
        Format = Format.R8G8B8A8_UNorm,
        SampleDescription = new SampleDescription(1, 0),
        Usage = ResourceUsage.Default,
        BindFlags = BindFlags.ShaderResource | BindFlags.UnorderedAccess,
        CpuAccessFlags = CpuAccessFlags.None,
        OptionFlags = ResourceOptionFlags.None,
    });

    private void Present(Texture2D frame)
    {
        using (var backBuffer = _swapChain!.GetBackBuffer<Texture2D>(0))
        {
            _device!.ImmediateContext.CopyResource(frame, backBuffer);
        }
        _swapChain.Present(1, PresentFlags.None);
    }

    private static double Seconds(long timestamp) => (double)timestamp / Stopwatch.Frequency;

    /// <summary>Sleeps until <paramref name="when"/>, but no longer once a newer frame is waiting or the player stops.</summary>
    private void WaitUntil(double when)
    {
        while (!_stopping && !_pool.HasQueued)
        {
            var remaining = when - Seconds(Stopwatch.GetTimestamp());
            if (remaining <= 0) return;
            if (remaining > 0.002) Thread.Sleep(TimeSpan.FromSeconds(remaining - 0.0015));
            else Thread.SpinWait(200);
        }
    }

    // ------------------------------------------------------------------
    // Frame memory shared with LibVLC
    // ------------------------------------------------------------------

    /// <summary>
    /// The NV12 buffers LibVLC decodes into. Picture ids are 1-based slots;
    /// id 0 is a spare that is never presented, for the rare moment every
    /// slot is in use. A displayed frame waits in "queued" until the
    /// presenter takes it; a newer one replaces it (that frame is dropped).
    /// </summary>
    private sealed class FramePool : IDisposable
    {
        private enum Slot
        {
            Free,
            Decoding,
            Queued,
            Busy,
        }

        private readonly object _gate = new();
        private nint[] _buffers = [];
        private Slot[] _slots = [];
        private nint _spare;
        private int _queued = -1;
        private long _queuedAt;
        private int _busy = -1;

        public int Width { get; private set; }
        public int Height { get; private set; }
        public int Pitch { get; private set; }

        public bool HasQueued
        {
            get
            {
                lock (_gate) return _queued >= 0;
            }
        }

        public void Allocate(int width, int height, int pitch, int count)
        {
            Retire();
            lock (_gate)
            {
                Width = width;
                Height = height;
                Pitch = pitch;
                var bytes = (nint)((long)pitch * height * 3 / 2);
                _buffers = new nint[count];
                _slots = new Slot[count];
                for (var index = 0; index < count; index++) _buffers[index] = Marshal.AllocHGlobal(bytes);
                _spare = Marshal.AllocHGlobal(bytes);
            }
        }

        /// <summary>Frees the buffers once the presenter has finished with its frame.</summary>
        public void Retire()
        {
            lock (_gate)
            {
                var deadline = Environment.TickCount64 + 1000;
                while (_busy >= 0 && Environment.TickCount64 < deadline) Monitor.Wait(_gate, 50);
                foreach (var buffer in _buffers) Marshal.FreeHGlobal(buffer);
                if (_spare != 0) Marshal.FreeHGlobal(_spare);
                _buffers = [];
                _slots = [];
                _spare = 0;
                _queued = -1;
                _busy = -1;
            }
        }

        public nint Pointer(int id)
        {
            lock (_gate) return id > 0 && id <= _buffers.Length ? _buffers[id - 1] : _spare;
        }

        public int Lock()
        {
            lock (_gate)
            {
                for (var index = 0; index < _slots.Length; index++)
                {
                    if (_slots[index] != Slot.Free) continue;
                    _slots[index] = Slot.Decoding;
                    return index + 1;
                }
                return 0;
            }
        }

        /// <summary>LibVLC is done with a picture; one never displayed (dropped) is free again.</summary>
        public void Unlock(int id)
        {
            lock (_gate)
            {
                if (id > 0 && id <= _slots.Length && _slots[id - 1] == Slot.Decoding) _slots[id - 1] = Slot.Free;
            }
        }

        /// <summary>A picture is due on screen now. False for the spare.</summary>
        public bool Display(int id, long timestamp)
        {
            lock (_gate)
            {
                if (id <= 0 || id > _slots.Length) return false;
                if (_queued >= 0 && _queued != id - 1) _slots[_queued] = Slot.Free;
                _queued = id - 1;
                _queuedAt = timestamp;
                _slots[id - 1] = Slot.Queued;
                return true;
            }
        }

        public bool TryTake(out int index, out long timestamp)
        {
            lock (_gate)
            {
                index = _queued;
                timestamp = _queuedAt;
                if (index < 0) return false;
                _queued = -1;
                _slots[index] = Slot.Busy;
                _busy = index;
                return true;
            }
        }

        public void Release(int index)
        {
            lock (_gate)
            {
                if (index >= 0 && index < _slots.Length && _slots[index] == Slot.Busy) _slots[index] = Slot.Free;
                _busy = -1;
                Monitor.PulseAll(_gate);
            }
        }

        public void Dispose() => Retire();
    }
}
