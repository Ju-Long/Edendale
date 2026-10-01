// Buffered remote reads (DIFF.md §3.13, EDBufferedByteSource.m). LibVLC
// asks for small reads; over a VPN, Tailscale, or a hotspot one round trip
// per read caps throughput below video bit rates. A worker thread fetches
// 1 MiB chunks up to 48 MiB ahead of the read position, within a 64 MiB
// cache. The chunk a blocked read needs always goes first, so a seek never
// waits behind read-ahead. After a failure on a file that opened once, the
// connection is dropped and reopened with delays of 0.25, 0.5, 1, 2, 4, and
// 8 s; the read fails only after every retry has. An idle connection gets a
// keep-alive after 20 s. Cancel() fails blocked reads at once, and the
// "connection lost" message names the host.

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
    private readonly string _host;
    private readonly Func<IBufferedFile> _open;

    public int ChunkSize { get; init; } = 1 << 20;
    public long ReadAheadBytes { get; init; } = 48L << 20;
    public long CacheBytes { get; init; } = 64L << 20;
    public IReadOnlyList<TimeSpan> RetryDelays { get; init; } =
        new[] { 0.25, 0.5, 1, 2, 4, 8 }.Select(TimeSpan.FromSeconds).ToArray();
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

    /// <param name="host">Named in "Lost the connection to …".</param>
    /// <param name="open">Connects and opens the file; throws with a readable message on failure.</param>
    public BufferedByteSource(string host, Func<IBufferedFile> open)
    {
        _host = host;
        _open = open;
    }

    private long AheadChunks => Math.Max(1, ReadAheadBytes / Math.Max(ChunkSize, 1));

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
            var index = offset / ChunkSize;
            if (_readChunk != index || _prefetchHalted)
            {
                _readChunk = index;
                _prefetchHalted = false;
                Monitor.PulseAll(_gate);
            }
            try
            {
                while (true)
                {
                    if (_cancelled) return -1;
                    if (_fatal)
                    {
                        _reason = _failure;
                        return -1;
                    }
                    if (_opened && _size >= 0 && offset >= _size) return 0;
                    if (_chunks.TryGetValue(index, out var chunk))
                    {
                        var start = (int)(offset - index * ChunkSize);
                        // A chunk shorter than the offset ends the file.
                        if (start >= chunk.Length) return 0;
                        var count = Math.Min(buffer.Length, chunk.Length - start);
                        chunk.AsSpan(start, count).CopyTo(buffer);
                        return count;
                    }
                    if (_failedChunk == index)
                    {
                        _failedChunk = -1;
                        _reason = _failure;
                        return -1;
                    }
                    if (_wanted != index)
                    {
                        _wanted = index;
                        Monitor.PulseAll(_gate);
                    }
                    if (shouldAbort?.Invoke() == true) return -1;
                    Monitor.Wait(_gate, 50);
                }
            }
            finally
            {
                if (_wanted == index) _wanted = -1;
            }
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

    // ------------------------------------------------------------------
    // Worker thread
    // ------------------------------------------------------------------

    private void Run()
    {
        while (true)
        {
            long target = -1;
            var keepAlive = false;
            lock (_gate)
            {
                while (!_cancelled)
                {
                    target = NextChunk();
                    if (target >= 0) break;
                    var wait = TimeSpan.FromHours(1);
                    if (_file is not null && KeepAliveInterval > TimeSpan.Zero)
                    {
                        var idle = TimeSpan.FromMilliseconds(Now - _lastActivity);
                        if (idle >= KeepAliveInterval)
                        {
                            keepAlive = true;
                            break;
                        }
                        wait = KeepAliveInterval - idle;
                    }
                    Monitor.Wait(_gate, wait);
                }
                if (_cancelled) break;
            }

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
            else
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
        var end = Math.Min(_lastChunk, _readChunk + AheadChunks);
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
                        if (size >= 0) _lastChunk = size == 0 ? -1 : (size - 1) / ChunkSize;
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
                if (data.Length < ChunkSize) _lastChunk = Math.Min(_lastChunk, index);
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
        var offset = index * ChunkSize;
        long want = ChunkSize;
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

    /// <summary>Drops the chunks farthest from the read position, those behind it first.</summary>
    private void Evict()
    {
        var limit = Math.Max(Math.Max(CacheBytes, ReadAheadBytes + 2L * ChunkSize) / ChunkSize, AheadChunks + 2);
        while (_chunks.Count > limit)
        {
            var victim = _chunks.Keys
                .OrderByDescending(index => index < _readChunk ? (_readChunk - index) * 4 : index - _readChunk)
                .First();
            _chunks.Remove(victim);
        }
    }
}
