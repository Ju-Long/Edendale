using System.Diagnostics;
using Edendale.Windows.Services.Remote;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The buffered reader (DIFF.md §3.13) against an in-memory server whose
/// connections can be dropped, refused, or slowed, ported from Apple's
/// BufferedByteSourceTests: read-ahead, a seek jumping the read-ahead queue,
/// reconnecting after a drop, giving up after the retries, failing a bad
/// first login at once, interrupts, cancel, keep-alives, and eviction; and
/// since apple-27.1 5020ec3, what it holds past the last read, a read-ahead
/// that grows into the memory the app can spare and gives it back, and a
/// brief jump away that keeps the buffer.
/// </summary>
[TestClass]
public sealed class BufferedByteSourceTests
{
    private const int Chunk = 1024;

    /// <summary>A file server whose state the tests change while the source reads.</summary>
    private sealed class FakeFileServer(byte[] data)
    {
        private readonly object _gate = new();
        private int _generation;
        private bool _refusing;
        private int _opens;
        private readonly List<long> _reads = [];
        private int _keepAlives;
        private TimeSpan _delay;
        private bool _failKeepAlive;

        public byte[] Data => data;
        public int OpenCount { get { lock (_gate) return _opens; } }
        public List<long> ReadOffsets { get { lock (_gate) return [.. _reads]; } }
        public int KeepAliveCount { get { lock (_gate) return _keepAlives; } }
        public TimeSpan Delay { set { lock (_gate) _delay = value; } }

        /// <summary>Kills every open connection; <paramref name="refuse"/> also turns new ones away.</summary>
        public void Drop(bool refuse = false)
        {
            lock (_gate)
            {
                _generation++;
                _refusing = refuse;
            }
        }

        public void Restore() { lock (_gate) _refusing = false; }
        public void FailKeepAlives() { lock (_gate) _failKeepAlive = true; }

        public IBufferedFile Open()
        {
            lock (_gate)
            {
                _opens++;
                if (_refusing) throw new IOException("Host is down");
                return new FakeFile(this, _generation);
            }
        }

        public int Read(long offset, Span<byte> buffer, int generation)
        {
            TimeSpan delay;
            lock (_gate) delay = _delay;
            if (delay > TimeSpan.Zero) Thread.Sleep(delay);
            lock (_gate)
            {
                if (generation != _generation) throw new IOException("Connection reset");
                _reads.Add(offset);
                if (offset >= data.Length) return 0;
                var count = Math.Min(buffer.Length, data.Length - (int)offset);
                data.AsSpan((int)offset, count).CopyTo(buffer);
                return count;
            }
        }

        public bool KeepAlive(int generation)
        {
            lock (_gate)
            {
                _keepAlives++;
                return !_failKeepAlive && generation == _generation;
            }
        }
    }

    private sealed class FakeFile(FakeFileServer server, int generation) : IBufferedFile
    {
        public long Size => server.Data.Length;
        public int Read(long offset, Span<byte> buffer) => server.Read(offset, buffer, generation);
        public bool KeepAlive() => server.KeepAlive(generation);
        public void Abort() { }
        public void Dispose() { }
    }

    /// <summary>The memory the app has to spare, as a test sets it.</summary>
    private sealed class FakeHeadroom(long bytes)
    {
        private long _bytes = bytes;

        public long Bytes
        {
            get => Interlocked.Read(ref _bytes);
            set => Interlocked.Exchange(ref _bytes, value);
        }
    }

    private static BufferedByteSource MakeSource(FakeFileServer server, int aheadChunks = 8, int cacheChunks = 16,
        double[]? retryDelays = null, double keepAlive = 0, int? maxAheadChunks = null, FakeHeadroom? headroom = null) =>
        new("nas.local", server.Open)
        {
            ChunkSize = Chunk,
            ReadAheadBytes = aheadChunks * Chunk,
            // Without a test's say-so the read-ahead never grows with memory.
            MaxReadAheadBytes = (maxAheadChunks ?? aheadChunks) * Chunk,
            MemoryHeadroom = headroom is null ? null : () => headroom.Bytes,
            CacheBytes = cacheChunks * Chunk,
            RetryDelays = (retryDelays ?? [0.01, 0.01, 0.01]).Select(TimeSpan.FromSeconds).ToArray(),
            KeepAliveInterval = TimeSpan.FromSeconds(keepAlive),
        };

    private static (long Bytes, bool Complete) Ahead(BufferedByteSource source)
    {
        var bytes = source.BufferedBytesAhead(out var complete);
        return (bytes, complete);
    }

    private static Task<(int Count, byte[] Data)> Read(IByteSource source, long offset, int length, Func<bool>? abort = null) =>
        RemoteByteSourceTests.BlockingRead(source, offset, length, abort);

    private static async Task<byte[]> ReadAll(IByteSource source, int count, int step = 700)
    {
        var collected = new List<byte>();
        while (collected.Count < count)
        {
            var (read, bytes) = await Read(source, collected.Count, step);
            if (read <= 0) break;
            collected.AddRange(bytes);
        }
        return [.. collected];
    }

    private static async Task WaitUntil(Func<bool> condition, double timeout = 3)
    {
        var clock = Stopwatch.StartNew();
        while (!condition() && clock.Elapsed < TimeSpan.FromSeconds(timeout)) await Task.Delay(10);
    }

    [TestMethod]
    public async Task ReadsTheWholeFileAndReadsAhead()
    {
        var server = new FakeFileServer(HttpStub.TestData(32 * 1024));
        var source = MakeSource(server);
        try
        {
            Assert.AreEqual(-1, source.Length);
            var (count, bytes) = await Read(source, 0, 100);
            Assert.AreEqual(100, count);
            CollectionAssert.AreEqual(server.Data[..100], bytes);
            Assert.AreEqual(server.Data.Length, source.Length);
            // Chunks up to eight ahead of the read position load unasked, and no further.
            await WaitUntil(() => server.ReadOffsets.Contains(8 * 1024));
            CollectionAssert.Contains(server.ReadOffsets, 8L * 1024);
            await Task.Delay(100);
            CollectionAssert.DoesNotContain(server.ReadOffsets, 9L * 1024);

            CollectionAssert.AreEqual(server.Data, await ReadAll(source, server.Data.Length));
            Assert.AreEqual(0, (await Read(source, server.Data.Length, 10)).Count);
            // Every fetch was a whole, chunk-aligned chunk, and none repeated.
            Assert.IsTrue(server.ReadOffsets.All(offset => offset % Chunk == 0));
            Assert.AreEqual(server.ReadOffsets.Count, server.ReadOffsets.Distinct().Count());
            Assert.AreEqual(1, server.OpenCount);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task ASeekJumpsTheReadAheadQueue()
    {
        var server = new FakeFileServer(HttpStub.TestData(256 * 1024)) { Delay = TimeSpan.FromMilliseconds(30) };
        var source = MakeSource(server, aheadChunks: 64, cacheChunks: 80);
        try
        {
            await Read(source, 0, 100);
            // Read-ahead now has 64 chunks (about 2 s) to fetch; the seek waits for one or two of them.
            var clock = Stopwatch.StartNew();
            var (count, bytes) = await Read(source, 200_000, 100);
            Assert.AreEqual(100, count);
            CollectionAssert.AreEqual(server.Data[200_000..200_100], bytes);
            Assert.IsTrue(clock.Elapsed < TimeSpan.FromSeconds(0.5), clock.Elapsed.ToString());
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task ReconnectsAfterADroppedConnection()
    {
        var server = new FakeFileServer(HttpStub.TestData(40 * 1024));
        var source = MakeSource(server, aheadChunks: 2, cacheChunks: 4);
        try
        {
            var collected = new List<byte>();
            while (collected.Count < server.Data.Length)
            {
                if (collected.Count is 10 * 1024 or 25 * 1024) server.Drop();
                var (read, bytes) = await Read(source, collected.Count, 1024);
                Assert.IsTrue(read > 0);
                collected.AddRange(bytes);
            }
            CollectionAssert.AreEqual(server.Data, collected.ToArray());
            Assert.AreEqual(3, server.OpenCount);
            Assert.IsNull(source.FailureReason);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task RidesOutAShortOutage()
    {
        var server = new FakeFileServer(HttpStub.TestData(16 * 1024));
        var source = MakeSource(server, aheadChunks: 1, cacheChunks: 3, retryDelays: [0.05, 0.1, 0.2, 0.4]);
        try
        {
            await Read(source, 0, 100);
            await WaitUntil(() => server.ReadOffsets.Contains(1024));
            server.Drop(refuse: true);
            _ = Task.Delay(200).ContinueWith(_ => server.Restore());
            var (count, bytes) = await Read(source, 8 * 1024, 100);
            Assert.AreEqual(100, count);
            CollectionAssert.AreEqual(server.Data[8192..8292], bytes);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task FailsTheReadOnceEveryRetryHasAndRecoversLater()
    {
        var server = new FakeFileServer(HttpStub.TestData(16 * 1024));
        var source = MakeSource(server, aheadChunks: 1, cacheChunks: 3);
        try
        {
            await Read(source, 0, 100);
            await WaitUntil(() => server.ReadOffsets.Contains(1024));
            server.Drop(refuse: true);
            var opensBefore = server.OpenCount;
            Assert.AreEqual(-1, (await Read(source, 8 * 1024, 100)).Count);
            // One open per retry.
            Assert.AreEqual(3, server.OpenCount - opensBefore);
            StringAssert.Contains(source.FailureReason, "Lost the connection to nas.local");
            StringAssert.Contains(source.FailureReason, "Host is down");

            server.Restore();
            var (later, bytes) = await Read(source, 8 * 1024, 100);
            Assert.AreEqual(100, later);
            CollectionAssert.AreEqual(server.Data[8192..8292], bytes);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task AFailedFirstLoginFailsAtOnce()
    {
        var server = new FakeFileServer(HttpStub.TestData(4096));
        server.Drop(refuse: true);
        var source = MakeSource(server, retryDelays: [1, 1, 1]);
        try
        {
            var clock = Stopwatch.StartNew();
            Assert.AreEqual(-1, (await Read(source, 0, 100)).Count);
            Assert.IsTrue(clock.Elapsed < TimeSpan.FromSeconds(0.5));
            Assert.AreEqual(1, server.OpenCount);
            Assert.AreEqual("Host is down", source.FailureReason);
            // Every later read fails the same way, without reconnecting.
            Assert.AreEqual(-1, (await Read(source, 0, 100)).Count);
            Assert.AreEqual(1, server.OpenCount);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task AnInterruptFailsOnlyTheBlockedRead()
    {
        var server = new FakeFileServer(HttpStub.TestData(4096)) { Delay = TimeSpan.FromMilliseconds(500) };
        var source = MakeSource(server, aheadChunks: 1, cacheChunks: 3);
        try
        {
            var interrupted = false;
            var clock = Stopwatch.StartNew();
            var result = Read(source, 0, 64, () => Volatile.Read(ref interrupted));
            await Task.Delay(100);
            Volatile.Write(ref interrupted, true);
            Assert.AreEqual(-1, (await result).Count);
            Assert.IsTrue(clock.Elapsed < TimeSpan.FromSeconds(0.45));
            Assert.IsNull(source.FailureReason);
            var (next, bytes) = await Read(source, 0, 64);
            Assert.AreEqual(64, next);
            CollectionAssert.AreEqual(server.Data[..64], bytes);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task CancelFailsABlockedReadAtOnce()
    {
        var server = new FakeFileServer(HttpStub.TestData(4096)) { Delay = TimeSpan.FromSeconds(2) };
        var source = MakeSource(server);
        var clock = Stopwatch.StartNew();
        var result = Read(source, 0, 64);
        await Task.Delay(150);
        source.Cancel();
        Assert.AreEqual(-1, (await result).Count);
        Assert.IsTrue(clock.Elapsed < TimeSpan.FromSeconds(1.5));
        Assert.AreEqual(-1, (await Read(source, 0, 64)).Count);
    }

    [TestMethod]
    public async Task KeepsAnIdleConnectionAliveAndReopensADeadOne()
    {
        var server = new FakeFileServer(HttpStub.TestData(8 * 1024));
        var source = MakeSource(server, aheadChunks: 1, cacheChunks: 3, keepAlive: 0.1);
        try
        {
            await Read(source, 0, 100);
            await WaitUntil(() => server.KeepAliveCount >= 2);
            Assert.IsTrue(server.KeepAliveCount >= 2);
            Assert.AreEqual(1, server.OpenCount);

            // A failed keep-alive drops the connection; the next fetch reopens it.
            server.FailKeepAlives();
            var before = server.KeepAliveCount;
            await WaitUntil(() => server.KeepAliveCount > before);
            var (count, bytes) = await Read(source, 6 * 1024, 100);
            Assert.AreEqual(100, count);
            CollectionAssert.AreEqual(server.Data[6144..6244], bytes);
            Assert.AreEqual(2, server.OpenCount);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task EvictsChunksFarBehindTheReadPosition()
    {
        var server = new FakeFileServer(HttpStub.TestData(32 * 1024));
        var source = MakeSource(server, aheadChunks: 2, cacheChunks: 6);
        try
        {
            CollectionAssert.AreEqual(server.Data, await ReadAll(source, server.Data.Length));
            var fetchesOfFirstChunk = server.ReadOffsets.Count(offset => offset == 0);
            var (count, bytes) = await Read(source, 0, 100);
            Assert.AreEqual(100, count);
            CollectionAssert.AreEqual(server.Data[..100], bytes);
            Assert.AreEqual(fetchesOfFirstChunk + 1, server.ReadOffsets.Count(offset => offset == 0));
        }
        finally
        {
            source.Cancel();
        }
    }

    // ------------------------------------------------------------------
    // apple-27.1 5020ec3: what it holds, memory, and brief jumps
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task ReportsWhatItHoldsPastTheLastRead()
    {
        var server = new FakeFileServer(HttpStub.TestData(32 * 1024));
        var source = MakeSource(server);
        try
        {
            Assert.AreEqual(0, Ahead(source).Bytes, "nothing is fetched before the first read");
            Assert.IsFalse(Ahead(source).Complete);

            await Read(source, 0, 100);
            await WaitUntil(() => Ahead(source).Complete);
            Assert.IsTrue(Ahead(source).Complete);
            Assert.AreEqual(9 * Chunk - 100, Ahead(source).Bytes, "the rest of the first chunk and the eight read ahead");

            await Read(source, 100, Chunk);
            Assert.AreEqual(8 * Chunk, Ahead(source).Bytes, "reading to the end of the chunk leaves the eight");
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task HoldsNothingOnceAStalledLinkHasEmptiedIt()
    {
        var server = new FakeFileServer(HttpStub.TestData(32 * 1024));
        var source = MakeSource(server, aheadChunks: 4, cacheChunks: 8);
        try
        {
            await Read(source, 0, Chunk);
            await WaitUntil(() => Ahead(source).Complete);
            Assert.AreEqual(4 * Chunk, Ahead(source).Bytes);

            // The link slows to a crawl while reading uses the buffer up.
            server.Delay = TimeSpan.FromSeconds(0.3);
            for (var index = 1; index <= 4; index++) await Read(source, index * Chunk, Chunk);
            Assert.AreEqual(0, Ahead(source).Bytes);
            Assert.IsFalse(Ahead(source).Complete);

            // It fills again behind the paused reader.
            await WaitUntil(() => Ahead(source).Complete, timeout: 5);
            Assert.AreEqual(4 * Chunk, Ahead(source).Bytes);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task AReadAheadThatGaveUpCountsAsComplete()
    {
        var server = new FakeFileServer(HttpStub.TestData(16 * 1024));
        var source = MakeSource(server, aheadChunks: 2, cacheChunks: 4, retryDelays: [0.01, 0.01]);
        try
        {
            await Read(source, 0, Chunk);
            await WaitUntil(() => Ahead(source).Complete);
            server.Drop(refuse: true);
            // Chunk 1 is cached; fetching chunk 3 behind it fails for good.
            await Read(source, Chunk, Chunk);
            await WaitUntil(() => Ahead(source).Complete);
            // Playback goes on with what is left and meets the error at a
            // read, rather than waiting for a buffer that will never fill.
            Assert.IsTrue(Ahead(source).Complete);
            Assert.AreEqual(Chunk, Ahead(source).Bytes);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task ReadsFurtherAheadWhileTheAppHasMemoryToSpare()
    {
        var server = new FakeFileServer(HttpStub.TestData(64 * 1024));
        var source = MakeSource(server, aheadChunks: 4, cacheChunks: 8, maxAheadChunks: 24, headroom: new FakeHeadroom(1 << 20));
        try
        {
            await Read(source, 0, 100);
            await WaitUntil(() => Ahead(source).Complete);
            Assert.AreEqual(25 * Chunk - 100, Ahead(source).Bytes, "the rest of the first chunk and 24 more");
            await Task.Delay(100);
            CollectionAssert.DoesNotContain(server.ReadOffsets, 25L * 1024, "and no further");
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task KeepsToTheSmallReadAheadWhenTheAppHasNoMemoryToSpare()
    {
        var server = new FakeFileServer(HttpStub.TestData(64 * 1024));
        var source = MakeSource(server, aheadChunks: 4, cacheChunks: 8, maxAheadChunks: 24, headroom: new FakeHeadroom(0));
        try
        {
            await Read(source, 0, 100);
            await WaitUntil(() => Ahead(source).Complete);
            Assert.AreEqual(5 * Chunk - 100, Ahead(source).Bytes);
            await Task.Delay(100);
            CollectionAssert.DoesNotContain(server.ReadOffsets, 5L * 1024);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task StopsGrowingWhenMemoryFillsAndGoesOnWhenItFrees()
    {
        var server = new FakeFileServer(HttpStub.TestData(64 * 1024)) { Delay = TimeSpan.FromSeconds(0.02) };
        var room = new FakeHeadroom(1 << 20);
        var source = MakeSource(server, aheadChunks: 4, cacheChunks: 8, maxAheadChunks: 24, headroom: room);
        try
        {
            await Read(source, 0, Chunk);
            await WaitUntil(() => server.ReadOffsets.Contains(8 * 1024));
            // The app reaches its memory target: what is here stays, no more comes.
            room.Bytes = 0;
            await WaitUntil(() => Ahead(source).Complete);
            var held = Ahead(source).Bytes;
            Assert.IsTrue(held >= 8 * Chunk, held.ToString());
            Assert.IsTrue(held < 24 * Chunk, held.ToString());
            await Task.Delay(150);
            Assert.AreEqual(held, Ahead(source).Bytes);

            // Memory frees up; the next read that moves on sets it growing again.
            server.Delay = TimeSpan.Zero;
            room.Bytes = 1 << 20;
            await Read(source, Chunk, Chunk);
            await WaitUntil(() => Ahead(source).Bytes == 24 * Chunk);
            Assert.AreEqual(24 * Chunk, Ahead(source).Bytes);
            Assert.IsTrue(Ahead(source).Complete);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task GivesTheExtraBackWhenTheAppRunsWellOverItsMemory()
    {
        var server = new FakeFileServer(HttpStub.TestData(64 * 1024));
        var room = new FakeHeadroom(1 << 20);
        var source = MakeSource(server, aheadChunks: 4, cacheChunks: 8, maxAheadChunks: 24, headroom: room);
        try
        {
            await Read(source, 0, Chunk);
            await WaitUntil(() => Ahead(source).Complete);
            Assert.AreEqual(24 * Chunk, Ahead(source).Bytes);

            // Something else in the app takes far more than the target allows.
            room.Bytes = -(64L << 20);
            await Read(source, Chunk, Chunk);
            await WaitUntil(() => Ahead(source).Bytes <= 4 * Chunk);
            Assert.AreEqual(4 * Chunk, Ahead(source).Bytes, "the read-ahead every device gets is all that is left");
            Assert.IsTrue(Ahead(source).Complete);
            var fetches = server.ReadOffsets.Count;
            await Task.Delay(100);
            Assert.AreEqual(fetches, server.ReadOffsets.Count);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task ABriefJumpAwayKeepsWhatWasBuffered()
    {
        var server = new FakeFileServer(HttpStub.TestData(64 * 1024));
        var source = MakeSource(server, aheadChunks: 8, cacheChunks: 10);
        try
        {
            await Read(source, 0, 100);
            await WaitUntil(() => Ahead(source).Complete);
            // FFmpeg (and LibVLC's demuxers) read a Matroska file's index at its
            // end on the first seek, then land a little further on from where it was.
            await Read(source, 63 * 1024, 100);
            var (count, bytes) = await Read(source, 3 * 1024, 100);
            Assert.AreEqual(100, count);
            CollectionAssert.AreEqual(server.Data[(3 * 1024)..(3 * 1024 + 100)], bytes);
            for (var index = 3; index <= 8; index++)
            {
                Assert.AreEqual(1, server.ReadOffsets.Count(offset => offset == index * 1024), $"chunk {index} fetched once");
            }
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task ASeekLetsGoOfTheOldPlaceOnceReadingHasSettled()
    {
        var server = new FakeFileServer(HttpStub.TestData(64 * 1024));
        var source = MakeSource(server, aheadChunks: 2, cacheChunks: 4);
        try
        {
            await Read(source, 0, Chunk);
            await WaitUntil(() => Ahead(source).Complete);
            for (var index = 30; index < 40; index++) await Read(source, index * Chunk, Chunk);
            // Ten chunks on, the start of the file is no longer kept.
            await Read(source, 0, 100);
            Assert.AreEqual(2, server.ReadOffsets.Count(offset => offset == 0));
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public void DefaultsMatchDiff()
    {
        var source = new BufferedByteSource("nas", () => throw new IOException());
        Assert.AreEqual(1 << 20, source.ChunkSize);
        Assert.AreEqual(48L << 20, source.ReadAheadBytes);
        Assert.AreEqual(64L << 20, source.CacheBytes);
        Assert.AreEqual(384L << 20, source.MaxReadAheadBytes);
        Assert.AreEqual(500L << 20, source.MemoryTarget);
        CollectionAssert.AreEqual(new[] { 0.25, 0.5, 1, 2, 4, 8 }, source.RetryDelays.Select(delay => delay.TotalSeconds).ToArray());
        Assert.AreEqual(TimeSpan.FromSeconds(20), source.KeepAliveInterval);
    }
}
