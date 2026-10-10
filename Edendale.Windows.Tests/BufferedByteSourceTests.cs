using System.Diagnostics;
using Edendale.Windows.Services.Remote;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The buffered reader (DIFF.md §3.13) against an in-memory server whose
/// connections can be dropped, refused, or slowed, ported from Apple's
/// BufferedByteSourceTests: read-ahead, a seek jumping the read-ahead queue,
/// reconnecting after a drop, giving up after the retries, failing a bad
/// first login at once, interrupts, cancel, keep-alives, and eviction.
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

    private static BufferedByteSource MakeSource(FakeFileServer server, int aheadChunks = 8, int cacheChunks = 16,
        double[]? retryDelays = null, double keepAlive = 0) =>
        new("nas.local", server.Open)
        {
            ChunkSize = Chunk,
            ReadAheadBytes = aheadChunks * Chunk,
            CacheBytes = cacheChunks * Chunk,
            RetryDelays = (retryDelays ?? [0.01, 0.01, 0.01]).Select(TimeSpan.FromSeconds).ToArray(),
            KeepAliveInterval = TimeSpan.FromSeconds(keepAlive),
        };

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

    [TestMethod]
    public void DefaultsMatchDiff()
    {
        var source = new BufferedByteSource("nas", () => throw new IOException());
        Assert.AreEqual(1 << 20, source.ChunkSize);
        Assert.AreEqual(48L << 20, source.ReadAheadBytes);
        Assert.AreEqual(64L << 20, source.CacheBytes);
        CollectionAssert.AreEqual(new[] { 0.25, 0.5, 1, 2, 4, 8 }, source.RetryDelays.Select(delay => delay.TotalSeconds).ToArray());
        Assert.AreEqual(TimeSpan.FromSeconds(20), source.KeepAliveInterval);
    }
}
