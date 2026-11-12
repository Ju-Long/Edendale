// Buffered remote reads (DIFF.md §3.13, EDBufferedByteSource.m). LibVLC
// asks for small reads; over a VPN, Tailscale, or a hotspot one round trip
// per read caps throughput below video bit rates. A worker thread fetches
// 1 MiB chunks up to 48 MiB ahead of the read position. The chunk a blocked
// read needs always goes first, so a seek never waits behind read-ahead.
// After a failure on a file that opened once, the connection is dropped and
// reopened with delays of 0.25, 0.5, 1, 2, 4, and 8 s; the read fails only
// after every retry has. An idle connection gets a keep-alive after 20 s.
// Cancel() fails blocked reads at once, and the "connection lost" message
// names the host.
//
// The 48 MiB is what every device can spare. While the whole app uses less
// than 500 MiB the worker reads on, up to 384 MiB ahead; once the app is
// past that it reads no further, and more than 32 MiB past it the extra is
// given back, farthest chunks first (Apple 5020ec3). Outside the read-ahead
// it keeps 16 chunks (the 64 MiB cache less the read-ahead): just behind the
// read position, or where reading was before it jumped. That place is kept
// until reading has settled 8 chunks on at the new one, so LibVLC's detour
// to a Matroska file's index at its end, on the first seek, doesn't throw
// the buffer away. The source also says how much it holds past the last
// read (BufferedBytesAhead), so the player can tell a stream that ran dry.

using System.Runtime.InteropServices;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

/// <summary>One open remote file, used only from the buffer's worker thread.</summary>
public interface IBufferedFile : IDisposable
{
    /// <summary>The file size, or -1 when the server doesn't say.</summary>
    long Size { get; }

    /// <summary>Reads into <paramref name="buffer"/>; 0 at the end. Throws on failure.</summary>
    int Read(long offset, Span<byte> buffer);

    /// <summary>A cheap request that keeps an idle connection open; false when it has died.</summary>
    bool KeepAlive();

    /// <summary>Aborts a read in progress from another thread (Cancel).</summary>
    void Abort();
}

public sealed class BufferedByteSource : IByteSource
{
    /// <summary>
    /// The app may pass its memory target by this much before read-ahead
    /// that grew into spare memory is given back. Keeps growing and
    /// trimming apart.
    /// </summary>
    private const long MemoryTrimSlack = 32L << 20;

    /// <summary>
    /// Between two trims, in milliseconds. Freed memory leaves the app's
    /// private bytes a moment late; trimming again before it has would give
    /// back too much.
    /// </summary>
    private const long MemoryTrimIntervalMilliseconds = 15_000;

    /// <summary>Reading this many chunks on from where it jumped to has settled there.</summary>
    private const long JumpSettleChunks = 8;

    private readonly string _host;
    private readonly Func<IBufferedFile> _open;

    // Tuning, read as the worker uses it. Tests shrink these.

    /// <summary>Bytes per fetch.</summary>
    public int ChunkSize { get; init; } = 1 << 20;

    /// <summary>How far past the read position to fetch, whatever memory the app uses.</summary>
    public long ReadAheadBytes { get; init; } = 48L << 20;

    /// <summary>
    /// How far to fetch while the app uses less than <see cref="MemoryTarget"/>;
    /// never less than <see cref="ReadAheadBytes"/>.
    /// </summary>
    public long MaxReadAheadBytes { get; init; } = 384L << 20;

    /// <summary>
    /// The app memory (its private bytes, the closest Windows measure to the
    /// footprint Apple's gauge shows) up to which the read-ahead grows past
    /// <see cref="ReadAheadBytes"/>.
    /// </summary>
    public long MemoryTarget { get; init; } = 500L << 20;

    /// <summary>
    /// Replaces the measurement behind <see cref="MemoryTarget"/>: the memory
    /// the app may still take, negative once it is past it. Called on the
    /// worker thread. Tests set it.
    /// </summary>
    public Func<long>? MemoryHeadroom { get; init; }

    /// <summary>
    /// <see cref="ReadAheadBytes"/> plus what is kept outside the read-ahead
    /// (just behind the read position, or where reading was before a seek);
    /// the difference is at least two chunks.
    /// </summary>
    public long CacheBytes { get; init; } = 64L << 20;

    /// <summary>Delays before each reconnect after a failure; their count is the number of retries.</summary>
    public IReadOnlyList<TimeSpan> RetryDelays { get; init; } =
        new[] { 0.25, 0.5, 1, 2, 4, 8 }.Select(TimeSpan.FromSeconds).ToArray();

    /// <summary>Idle time before a keep-alive; zero disables it.</summary>
    public TimeSpan KeepAliveInterval { get; init; } = TimeSpan.FromSeconds(20);

    // Guarded by _gate.
    private readonly object _gate = new();
    private readonly Dictionary<long, byte[]> _chunks = [];
    private bool _started;
    private bool _cancelled;
    private bool _opened;
    private long _size = -1;
    /// <summary>The last chunk of the file, once the size or a short chunk shows it.</summary>
    private long _lastChunk = long.MaxValue;
    /// <summary>The chunk LibVLC read last; read-ahead starts here.</summary>
    private long _readChunk;
    /// <summary>Where LibVLC's last read ended.</summary>
    private long _readEnd;
    /// <summary>
    /// The app had memory to spare at the worker's last look, so the
    /// read-ahead may grow past <see cref="ReadAheadBytes"/>.
    /// </summary>
    private bool _roomy;
    /// <summary>
    /// Where reading was before it last jumped, or -1 once it has settled at
    /// the new place. What is buffered there stays meanwhile: LibVLC jumps
    /// away for a moment (to a Matroska file's index at its end, on the first
    /// seek) and comes straight back.
    /// </summary>
    private long _heldChunk = -1;
    /// <summary>Chunks read on from the place of the last jump.</summary>
    private long _sinceJump;
    /// <summary>The chunk a blocked read waits for, or -1.</summary>
    private long _wanted = -1;
    /// <summary>A chunk whose fetch failed while a read waited for it, or -1.</summary>
    private long _failedChunk = -1;
    /// <summary>The first open failed: every read fails.</summary>
    private bool _fatal;
    /// <summary>A prefetch failed for good; the next read resumes read-ahead.</summary>
    private bool _prefetchHalted;
    private string? _failure;
    private string? _reason;
    private IBufferedFile? _liveFile;

    // Worker thread only.
    private IBufferedFile? _file;
    private bool _everOpened;
    private long _lastActivity;
    /// <summary>When read-ahead was last given back (Environment.TickCount64), or null.</summary>
    private long? _lastTrim;

    /// <param name="host">Named in "Lost the connection to …".</param>
    /// <param name="open">Connects and opens the file; throws with a readable message on failure.</param>
    public BufferedByteSource(string host, Func<IBufferedFile> open)
    {
        _host = host;
        _open = open;
    }

    private int Chunk => Math.Max(ChunkSize, 1);

    private long AheadChunks => Math.Max(1, ReadAheadBytes / Chunk);

    private long MaxAheadChunks => Math.Max(AheadChunks, MaxReadAheadBytes / Chunk);

    /// <summary>How far the worker reads ahead right now (with _gate held).</summary>
    private long AheadLimit => _roomy ? MaxAheadChunks : AheadChunks;

    /// <summary>
    /// Chunks kept outside the read-ahead: just behind the read position, or
    /// left where reading was before a seek.
    /// </summary>
    private long SpareChunks => Math.Max(2, (CacheBytes - ReadAheadBytes) / Chunk);

    public long Length
    {
        get { lock (_gate) return _opened ? _size : -1; }
    }

    public string? FailureReason
    {
        get { lock (_gate) return _reason; }
    }

    public int Read(long offset, Span<byte> buffer, Func<bool>? shouldAbort = null)
    {
        if (buffer.Length == 0 || offset < 0) return 0;
        lock (_gate)
        {
            StartIfNeeded();
            var index = offset / Chunk;
            if (_readChunk != index || _prefetchHalted)
            {
                if (index == _readChunk + 1)
                {
                    if (_heldChunk >= 0 && ++_sinceJump >= JumpSettleChunks) _heldChunk = -1;
                }
                else if (index != _readChunk)
                {
                    _heldChunk = _readChunk;
                    _sinceJump = 0;
                }
                _readChunk = index;
                _prefetchHalted = false;
                // Chunks now behind the read position go at once, whether or
                // not the worker has anything to fetch.
                Evict();
                Monitor.PulseAll(_gate);
            }
            int result;
            while (true)
            {
                if (_cancelled)
                {
                    result = -1;
                    break;
                }
                if (_fatal)
                {
                    _reason = _failure;
                    result = -1;
                    break;
                }
                if (_opened && _size >= 0 && offset >= _size)
                {
                    result = 0;
                    break;
                }
                if (_chunks.TryGetValue(index, out var chunk))
                {
                    var start = (int)(offset - index * Chunk);
                    // A chunk shorter than the offset ends the file.
                    if (start >= chunk.Length)
                    {
                        result = 0;
                        break;
                    }
                    var count = Math.Min(buffer.Length, chunk.Length - start);
                    chunk.AsSpan(start, count).CopyTo(buffer);
                    result = count;
                    break;
                }
                if (_failedChunk == index)
                {
                    _failedChunk = -1;
                    _reason = _failure;
                    result = -1;
                    break;
                }
                if (_wanted != index)
                {
                    _wanted = index;
                    Monitor.PulseAll(_gate);
                }
                if (shouldAbort?.Invoke() == true)
                {
                    result = -1;
                    break;
                }
                Monitor.Wait(_gate, 50);
            }
            if (_wanted == index) _wanted = -1;
            if (result >= 0) _readEnd = offset + result;
            return result;
        }
    }

    /// <summary>
    /// The bytes fetched contiguously past the end of the last read, for the
    /// player's rebuffering. <paramref name="complete"/> once the read-ahead
    /// for that position is all here, reaches the end of the file, or has
    /// given up after a failure: nothing more arrives until reading goes on.
    /// </summary>
    public long BufferedBytesAhead(out bool complete)
    {
        lock (_gate)
        {
            long bytes = 0;
            var index = _readEnd / Chunk;
            var last = Math.Min(_lastChunk, _readChunk + MaxAheadChunks);
            for (; index <= last; index++)
            {
                if (!_chunks.TryGetValue(index, out var chunk)) break;
                var start = Math.Max(_readEnd - index * Chunk, 0);
                bytes += Math.Max(chunk.Length - start, 0);
            }
            // The last chunk the worker fetches for this read position, with
            // the memory there is now.
            var end = Math.Min(_lastChunk, _readChunk + AheadLimit);
            // After a failure nothing more arrives until a read asks again.
            complete = _cancelled || _fatal || _prefetchHalted || (_opened && index > end);
            return bytes;
        }
    }

    public void Cancel()
    {
        IBufferedFile? file;
        lock (_gate)
        {
            _cancelled = true;
            _chunks.Clear();
            file = _liveFile;
            _liveFile = null;
            Monitor.PulseAll(_gate);
        }
        try
        {
            file?.Abort();
        }
        catch
        {
            // Already closed.
        }
    }

    private void StartIfNeeded()
    {
        if (_started || _cancelled) return;
        _started = true;
        var thread = new Thread(Run) { IsBackground = true, Name = "Edendale.BufferedByteSource" };
        thread.Start();
    }

    /// <summary>Sets the worker's file, keeping Cancel()'s reference in step.</summary>
    private void SetFile(IBufferedFile? file)
    {
        if (_file is not null && !ReferenceEquals(_file, file))
        {
            try
            {
                _file.Dispose();
            }
            catch
            {
                // A dead connection may refuse to close cleanly.
            }
        }
        _file = file;
        lock (_gate) _liveFile = _cancelled ? null : file;
    }

    private static long Now => Environment.TickCount64;

    /// <summary>
    /// The memory the app may still take before it reaches
    /// <see cref="MemoryTarget"/>; negative once it is past it, and 0 when it
    /// can't be measured. Like macOS, no system limit is added: a desktop has
    /// memory to page.
    /// </summary>
    private long CurrentMemoryHeadroom()
    {
        if (MemoryHeadroom is { } headroom) return headroom();
        return ProcessMemory.PrivateBytes() is { } used ? MemoryTarget - used : 0;
    }

    // ------------------------------------------------------------------
    // Worker thread
    // ------------------------------------------------------------------

    private void Run()
    {
        while (true)
        {
            // Asked outside the lock: it is a call into the kernel.
            var headroom = CurrentMemoryHeadroom();
            long target;
            var keepAlive = false;
            bool cancelled;
            lock (_gate)
            {
                _roomy = headroom > 0;
                if (headroom < -MemoryTrimSlack
                    && (_lastTrim is not { } trimmed || Now - trimmed > MemoryTrimIntervalMilliseconds)
                    && Trim(-headroom))
                {
                    _lastTrim = Now;
                }
                target = _cancelled ? -1 : NextChunk();
                if (!_cancelled && target < 0)
                {
                    var wait = TimeSpan.FromHours(1);
                    if (_file is not null && KeepAliveInterval > TimeSpan.Zero)
                    {
                        var idle = TimeSpan.FromMilliseconds(Now - _lastActivity);
                        if (idle >= KeepAliveInterval) keepAlive = true;
                        else wait = KeepAliveInterval - idle;
                    }
                    // Any wake (a read moved on, a cancel, the timeout) goes
                    // round again and looks at memory afresh.
                    if (!keepAlive) Monitor.Wait(_gate, wait);
                }
                cancelled = _cancelled;
            }
            if (cancelled) break;

            if (keepAlive)
            {
                // A dead connection reopens at the next fetch.
                bool alive;
                try
                {
                    alive = _file!.KeepAlive();
                }
                catch
                {
                    alive = false;
                }
                if (!alive) SetFile(null);
                _lastActivity = Now;
            }
            else if (target >= 0)
            {
                FetchChunk(target);
            }
        }
        // Closes the connection on the thread that used it.
        SetFile(null);
    }

    /// <summary>The chunk a read waits for, then the first one missing ahead of the read position.</summary>
    private long NextChunk()
    {
        if (_fatal) return -1;
        if (_wanted >= 0 && _wanted != _failedChunk && !_chunks.ContainsKey(_wanted)) return _wanted;
        if (_prefetchHalted || !_opened) return -1;
        var end = Math.Min(_lastChunk, _readChunk + AheadLimit);
        for (var index = _readChunk; index <= end; index++)
        {
            if (index != _failedChunk && !_chunks.ContainsKey(index)) return index;
        }
        return -1;
    }

    private bool IsCancelled
    {
        get { lock (_gate) return _cancelled; }
    }

    private void FetchChunk(long index)
    {
        Exception? error = null;
        byte[]? data = null;
        var attempt = 0;
        while (true)
        {
            if (_file is null)
            {
                error = null;
                try
                {
                    SetFile(_open());
                }
                catch (Exception failure)
                {
                    error = failure;
                }
                if (_file is not null)
                {
                    _everOpened = true;
                    var size = _file.Size;
                    lock (_gate)
                    {
                        _opened = true;
                        _size = size;
                        if (size >= 0) _lastChunk = size == 0 ? -1 : (size - 1) / Chunk;
                    }
                }
            }
            if (_file is not null)
            {
                try
                {
                    data = ReadChunk(index);
                }
                catch (Exception failure)
                {
                    error = failure;
                }
                _lastActivity = Now;
                if (data is not null) break;
                // Drop the connection: a timed-out one rarely recovers.
                SetFile(null);
            }
            // A login or path that never worked won't work on a retry either.
            if (!_everOpened || attempt >= RetryDelays.Count || IsCancelled) break;
            var until = Now + (long)RetryDelays[attempt++].TotalMilliseconds;
            lock (_gate)
            {
                while (!_cancelled && Now < until) Monitor.Wait(_gate, (int)Math.Max(1, until - Now));
            }
        }

        lock (_gate)
        {
            if (data is not null)
            {
                _chunks[index] = data;
                if (data.Length < Chunk) _lastChunk = Math.Min(_lastChunk, index);
                Evict();
            }
            else if (!_cancelled)
            {
                var detail = error?.Message;
                if (!_everOpened)
                {
                    _fatal = true;
                    _failure = string.IsNullOrEmpty(detail) ? AppText.Format("Remote_CouldNotConnect", _host) : detail;
                }
                else
                {
                    _failure = string.IsNullOrEmpty(detail)
                        ? AppText.Format("Remote_ConnectionLostPlain", _host)
                        : AppText.Format("Remote_ConnectionLost", _host, detail);
                    if (_wanted == index) _failedChunk = index;
                    // Retrying again at once would only fail again; wait for a read.
                    _prefetchHalted = true;
                }
            }
            Monitor.PulseAll(_gate);
        }
    }

    /// <summary>Reads one whole chunk, which may take several calls; null when cancelled.</summary>
    private byte[]? ReadChunk(long index)
    {
        var offset = index * Chunk;
        long want = Chunk;
        var size = _file!.Size;
        if (size >= 0) want = Math.Max(0, Math.Min(want, size - offset));
        var data = new byte[want];
        var filled = 0;
        while (filled < want)
        {
            if (IsCancelled) return null;
            var count = _file.Read(offset + filled, data.AsSpan(filled));
            if (count < 0) throw new IOException();
            if (count == 0) break;
            filled += count;
        }
        return filled == data.Length ? data : data[..filled];
    }

    /// <summary>
    /// Whether <paramref name="index"/> is in the read-ahead of the read
    /// position, or of the place reading has just jumped from (with _gate held).
    /// </summary>
    private bool IsHeld(long index)
    {
        var ahead = MaxAheadChunks;
        if (index >= _readChunk && index <= _readChunk + ahead) return true;
        return _heldChunk >= 0 && index >= _heldChunk && index <= _heldChunk + ahead;
    }

    /// <summary>
    /// Keeps the read-ahead and <see cref="SpareChunks"/> more. Of the rest
    /// (chunks behind the read position, or left where reading was before a
    /// seek) the farthest go first, those behind before those ahead.
    /// </summary>
    private void Evict()
    {
        var spare = SpareChunks;
        while (true)
        {
            long victim = -1;
            long worst = -1;
            long outside = 0;
            foreach (var index in _chunks.Keys)
            {
                if (IsHeld(index)) continue;
                outside++;
                var distance = index < _readChunk ? (_readChunk - index) * 4 : index - _readChunk;
                if (distance > worst)
                {
                    worst = distance;
                    victim = index;
                }
            }
            if (outside <= spare || victim < 0) return;
            _chunks.Remove(victim);
        }
    }

    /// <summary>
    /// Gives back <paramref name="bytes"/> of the read-ahead that grew into
    /// spare memory: first at the place reading jumped from, then here, the
    /// farthest chunks first. The read-ahead of <see cref="ReadAheadBytes"/>
    /// always stays. Returns whether there was anything to give (with _gate held).
    /// </summary>
    private bool Trim(long bytes)
    {
        var keep = AheadChunks;
        var ahead = MaxAheadChunks;
        var trimmed = false;
        long[] starts = [_heldChunk, _readChunk];
        for (var place = 0; place < starts.Length; place++)
        {
            var start = starts[place];
            if (start < 0) continue;
            for (var index = start + ahead; index > start + keep && bytes > 0; index--)
            {
                if (!_chunks.TryGetValue(index, out var chunk)) continue;
                // The other place's read-ahead is not this one's to give.
                if (place == 0 && index >= _readChunk && index <= _readChunk + keep) continue;
                bytes -= chunk.Length;
                _chunks.Remove(index);
                trimmed = true;
            }
        }
        return trimmed;
    }
}

/// <summary>
/// The app's private bytes (its commit charge, Task Manager's "Commit size"),
/// the closest Windows measure to the footprint Apple's read-ahead watches.
/// One cheap kernel call, made on a byte source's worker thread.
/// </summary>
internal static class ProcessMemory
{
    /// <summary>PROCESS_MEMORY_COUNTERS_EX.</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct Counters
    {
        public uint Size;
        public uint PageFaultCount;
        public nuint PeakWorkingSetSize;
        public nuint WorkingSetSize;
        public nuint QuotaPeakPagedPoolUsage;
        public nuint QuotaPagedPoolUsage;
        public nuint QuotaPeakNonPagedPoolUsage;
        public nuint QuotaNonPagedPoolUsage;
        public nuint PagefileUsage;
        public nuint PeakPagefileUsage;
        public nuint PrivateUsage;
    }

    private static readonly uint CountersSize = (uint)Marshal.SizeOf<Counters>();

    /// <summary>GetCurrentProcess()'s pseudo-handle, which needs no closing.</summary>
    private static readonly nint CurrentProcess = -1;

    [DllImport("kernel32.dll", EntryPoint = "K32GetProcessMemoryInfo")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetProcessMemoryInfo(nint process, out Counters counters, uint size);

    /// <summary>The private bytes, or null when they can't be read (not on Windows).</summary>
    public static long? PrivateBytes()
    {
        if (!OperatingSystem.IsWindows()) return null;
        try
        {
            return GetProcessMemoryInfo(CurrentProcess, out var counters, CountersSize) ? (long)counters.PrivateUsage : null;
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException)
        {
            return null;
        }
    }
}
