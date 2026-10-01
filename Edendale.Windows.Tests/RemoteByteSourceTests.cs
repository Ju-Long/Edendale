using System.Diagnostics;
using Edendale.Windows.Core;
using Edendale.Windows.Services.Remote;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The HTTP range reader against a stub that serves bytes with Range support
/// (DIFF.md §3.12), ported from Apple's RemoteByteSourceTests: chunking,
/// prefetch, cached backward seeks, a 401 answered by one token refresh, a
/// 410 by a new link, an ignored Range, backoff, and cancellation. Small
/// chunks keep the fixtures tiny.
/// </summary>
[TestClass]
public sealed class RemoteByteSourceTests
{
    private const long Chunk = 1024;

    /// <summary>Hands out requests to the stub, counting refreshes; a refresh bumps the token or link version.</summary>
    internal sealed class StubResolver(HttpStub stub, MediaSourceKind kind = MediaSourceKind.WebDav, bool preauthorized = false) : IRemoteContentResolver
    {
        private int _refreshes;
        private int _version;

        public MediaSourceKind Kind => kind;
        public bool UsesPreauthorizedLinks => preauthorized;
        public HttpMessageInvoker Client { get; } = stub.Client;
        public int RefreshCount => Volatile.Read(ref _refreshes);

        public Task<HttpRequestMessage> ContentRequestAsync(bool refresh, CancellationToken cancellation)
        {
            if (refresh)
            {
                Interlocked.Increment(ref _refreshes);
                Interlocked.Increment(ref _version);
            }
            var version = Volatile.Read(ref _version);
            var request = new HttpRequestMessage(HttpMethod.Get, $"https://stub.example/file.mkv?link={version}");
            if (!preauthorized) request.Headers.TryAddWithoutValidation("Authorization", $"Bearer token{version}");
            return Task.FromResult(request);
        }
    }

    private static RemoteByteSource MakeSource(IRemoteContentResolver resolver, int cachedChunks = 4) =>
        new(resolver, configuration: new RemoteByteSource.Configuration
        {
            ChunkSize = Chunk,
            CachedChunks = cachedChunks,
            RequestTimeout = TimeSpan.FromSeconds(5),
            BackoffDelays = [TimeSpan.FromMilliseconds(10), TimeSpan.FromMilliseconds(10), TimeSpan.FromMilliseconds(10)],
            BackoffJitter = 0,
        });

    /// <summary>LibVLC reads on its own thread and blocks; tests do the same.</summary>
    internal static Task<(int Count, byte[] Data)> BlockingRead(IByteSource source, long offset, int length, Func<bool>? abort = null) =>
        Task.Factory.StartNew(() =>
        {
            var buffer = new byte[length];
            var count = source.Read(offset, buffer, abort);
            return (count, buffer[..Math.Max(count, 0)]);
        }, TaskCreationOptions.LongRunning);

    [TestMethod]
    public async Task ReadsTheWholeFileInRangeChunks()
    {
        var data = HttpStub.TestData(5000);
        var stub = new HttpStub(request => HttpStub.File(data, request));
        var source = MakeSource(new StubResolver(stub));
        try
        {
            Assert.AreEqual(-1, source.Length);
            var collected = new List<byte>();
            while (collected.Count < data.Length)
            {
                var (count, bytes) = await BlockingRead(source, collected.Count, 700);
                Assert.IsTrue(count > 0);
                collected.AddRange(bytes);
            }
            CollectionAssert.AreEqual(data, collected.ToArray());
            // The first Content-Range told it the size.
            Assert.AreEqual(5000, source.Length);
            Assert.AreEqual(0, (await BlockingRead(source, 5000, 10)).Count);
            // Every request asked for a whole, chunk-aligned range.
            foreach (var range in stub.Ranges)
            {
                Assert.AreEqual(0, long.Parse(range["bytes=".Length..].Split('-')[0]) % Chunk, range);
            }
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task PrefetchesTheNextChunkDuringSequentialReads()
    {
        var data = HttpStub.TestData(8 * 1024);
        var stub = new HttpStub(request => HttpStub.File(data, request));
        var source = MakeSource(new StubResolver(stub));
        try
        {
            await BlockingRead(source, 0, 512);
            await BlockingRead(source, 512, 512);
            // The second read was sequential: chunk 1 loads before anyone asks.
            var deadline = Stopwatch.StartNew();
            while (!stub.Ranges.Contains("bytes=1024-2047") && deadline.Elapsed < TimeSpan.FromSeconds(3)) await Task.Delay(10);
            CollectionAssert.Contains(stub.Ranges.ToList(), "bytes=1024-2047");
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task ServesBackwardSeeksFromTheCache()
    {
        var data = HttpStub.TestData(4096);
        var stub = new HttpStub(request => HttpStub.File(data, request));
        var source = MakeSource(new StubResolver(stub));
        try
        {
            await BlockingRead(source, 3000, 100);
            await BlockingRead(source, 10, 100);
            var before = stub.Requests.Count;
            var (count, bytes) = await BlockingRead(source, 3050, 20);
            Assert.AreEqual(20, count);
            CollectionAssert.AreEqual(data[3050..3070], bytes);
            Assert.AreEqual(before, stub.Requests.Count);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task RefreshesTheTokenOnceAfterA401()
    {
        var data = HttpStub.TestData(2048);
        var stub = new HttpStub(request => request.Headers.Authorization?.ToString() == "Bearer token0"
            ? HttpStub.Text("expired", 401)
            : HttpStub.File(data, request));
        var resolver = new StubResolver(stub);
        var source = MakeSource(resolver);
        try
        {
            var (count, bytes) = await BlockingRead(source, 0, 100);
            Assert.AreEqual(100, count);
            CollectionAssert.AreEqual(data[..100], bytes);
            Assert.AreEqual(1, resolver.RefreshCount);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task ARejectedRefreshedTokenAsksForSignIn()
    {
        var stub = new HttpStub(_ => HttpStub.Text("no", 401));
        var resolver = new StubResolver(stub, MediaSourceKind.OneDrive);
        var source = MakeSource(resolver);
        try
        {
            Assert.AreEqual(-1, (await BlockingRead(source, 0, 100)).Count);
            Assert.AreEqual(1, resolver.RefreshCount);
            Assert.AreEqual(new ConnectorException(ConnectorFailure.SignInRequired, "OneDrive").Message, source.FailureReason);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task ResolvesANewLinkAfterA410()
    {
        var data = HttpStub.TestData(3000);
        var stub = new HttpStub(request => request.RequestUri!.Query == "?link=0"
            ? HttpStub.Text("gone", 410)
            : HttpStub.File(data, request));
        var resolver = new StubResolver(stub, MediaSourceKind.Dropbox, preauthorized: true);
        var source = MakeSource(resolver);
        try
        {
            var (count, bytes) = await BlockingRead(source, 1500, 200);
            Assert.AreEqual(200, count);
            CollectionAssert.AreEqual(data[1500..1700], bytes);
            Assert.AreEqual(1, resolver.RefreshCount);
            // A pre-authorized link never carries a token.
            Assert.IsTrue(stub.Requests.All(request => request.Header("Authorization") is null));
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task AcceptsAServerThatIgnoresRangeAtTheStart()
    {
        var data = HttpStub.TestData(5000);
        var stub = new HttpStub(_ => HttpStub.Bytes(System.Net.HttpStatusCode.OK, data));
        var source = MakeSource(new StubResolver(stub));
        try
        {
            var (count, bytes) = await BlockingRead(source, 0, 300);
            Assert.AreEqual(300, count);
            CollectionAssert.AreEqual(data[..300], bytes);
            Assert.AreEqual(5000, source.Length);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task AnIgnoredRangeLaterInTheFileFailsAfterOneRetry()
    {
        var data = HttpStub.TestData(5000);
        var stub = new HttpStub(_ => HttpStub.Bytes(System.Net.HttpStatusCode.OK, data));
        var source = MakeSource(new StubResolver(stub, MediaSourceKind.OneDrive));
        try
        {
            Assert.AreEqual(-1, (await BlockingRead(source, 3000, 100)).Count);
            Assert.AreEqual(2, stub.Ranges.Count(range => range == "bytes=2048-3071"));
            Assert.AreEqual(new ConnectorException(ConnectorFailure.RangeRequestsUnsupported, "OneDrive").Message, source.FailureReason);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task BacksOffWhenRateLimited()
    {
        var data = HttpStub.TestData(2000);
        var attempts = 0;
        var stub = new HttpStub(request => Interlocked.Increment(ref attempts) <= 2
            ? HttpStub.Text("slow down", 429, ("Retry-After", "0"))
            : HttpStub.File(data, request));
        var source = MakeSource(new StubResolver(stub));
        try
        {
            Assert.AreEqual(64, (await BlockingRead(source, 0, 64)).Count);
            Assert.AreEqual(3, attempts);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task PersistentServerErrorsFailAfterTheBackoffs()
    {
        var stub = new HttpStub(_ => HttpStub.Text("down", 503));
        var source = MakeSource(new StubResolver(stub, MediaSourceKind.OneDrive));
        try
        {
            Assert.AreEqual(-1, (await BlockingRead(source, 0, 64)).Count);
            Assert.AreEqual(4, stub.Requests.Count);
            Assert.AreEqual(new ConnectorException(ConnectorFailure.RateLimited, "OneDrive").Message, source.FailureReason);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task AMissingFileSaysItsGone()
    {
        var stub = new HttpStub(_ => HttpStub.Text("missing", 404));
        var source = MakeSource(new StubResolver(stub, MediaSourceKind.OneDrive, preauthorized: true));
        try
        {
            Assert.AreEqual(-1, (await BlockingRead(source, 0, 64)).Count);
            Assert.AreEqual("This file is no longer in OneDrive.", source.FailureReason);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task RateLimitsInA403AreRetried()
    {
        var data = HttpStub.TestData(2000);
        var attempts = 0;
        const string rateLimit = """{"error":{"errors":[{"domain":"usageLimits","reason":"userRateLimitExceeded"}],"code":403}}""";
        var stub = new HttpStub(request => Interlocked.Increment(ref attempts) == 1
            ? HttpStub.Text(rateLimit, 403)
            : HttpStub.File(data, request));
        var source = MakeSource(new StubResolver(stub));
        try
        {
            Assert.AreEqual(64, (await BlockingRead(source, 0, 64)).Count);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task CancelFailsABlockedReadAtOnce()
    {
        var data = HttpStub.TestData(2000);
        var stub = new HttpStub(request =>
        {
            Thread.Sleep(2000);
            return HttpStub.File(data, request);
        });
        var source = MakeSource(new StubResolver(stub));
        var started = Stopwatch.StartNew();
        var result = BlockingRead(source, 0, 64);
        await Task.Delay(150);
        source.Cancel();
        Assert.AreEqual(-1, (await result).Count);
        Assert.IsTrue(started.Elapsed < TimeSpan.FromSeconds(1.5));
        Assert.AreEqual(-1, (await BlockingRead(source, 0, 64)).Count);
    }

    [TestMethod]
    public async Task AnInterruptFailsOnlyTheBlockedRead()
    {
        var data = HttpStub.TestData(2000);
        var stub = new HttpStub(request =>
        {
            Thread.Sleep(500);
            return HttpStub.File(data, request);
        });
        var source = MakeSource(new StubResolver(stub));
        try
        {
            var interrupted = false;
            var started = Stopwatch.StartNew();
            var result = BlockingRead(source, 0, 64, () => Volatile.Read(ref interrupted));
            await Task.Delay(100);
            Volatile.Write(ref interrupted, true);
            Assert.AreEqual(-1, (await result).Count);
            Assert.IsTrue(started.Elapsed < TimeSpan.FromSeconds(0.45));
            // Reading on after the interrupt clears works.
            var (next, bytes) = await BlockingRead(source, 0, 64);
            Assert.AreEqual(64, next);
            CollectionAssert.AreEqual(data[..64], bytes);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public async Task ARangePastTheEndIsTheEndOfTheFile()
    {
        var data = HttpStub.TestData(2048);
        var stub = new HttpStub(request => HttpStub.File(data, request));
        // Exactly two chunks, size unknown until the server says.
        var source = MakeSource(new StubResolver(stub));
        try
        {
            Assert.AreEqual(0, (await BlockingRead(source, 2048, 64)).Count);
            Assert.AreEqual(2048, source.Length);
        }
        finally
        {
            source.Cancel();
        }
    }

    [TestMethod]
    public void ParsesContentRangeHeaders()
    {
        Assert.AreEqual(new ContentRange(0, 499, 1234), ContentRange.Parse("bytes 0-499/1234"));
        Assert.AreEqual(new ContentRange(500, 999, null), ContentRange.Parse("bytes 500-999/*"));
        Assert.AreEqual(new ContentRange(0, null, 1234), ContentRange.Parse("bytes */1234"));
        Assert.IsNull(ContentRange.Parse("items 0-1/2"));
        Assert.IsNull(ContentRange.Parse(null));
    }

    [TestMethod]
    public void ClassifiesProviderResponses()
    {
        ProviderAction Action(int status, string body = "", bool preauthorized = false, MediaSourceKind kind = MediaSourceKind.OneDrive, string? retryAfter = null) =>
            ProviderResponse.Action(status, body, retryAfter, kind, preauthorized);

        Assert.AreEqual(ProviderActionKind.Refresh, Action(401).Kind);
        Assert.AreEqual(ProviderActionKind.Refresh, Action(410).Kind);
        Assert.AreEqual(ProviderActionKind.Backoff, Action(403, """{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}""").Kind);
        Assert.AreEqual(ProviderActionKind.Refresh,
            Action(403, "<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>", preauthorized: true, kind: MediaSourceKind.S3).Kind);
        Assert.AreEqual(ConnectorFailure.AccessDenied, Action(403, "forbidden").Error?.Failure);
        Assert.AreEqual(ConnectorFailure.NotFound, Action(404).Error?.Failure);
        Assert.AreEqual(ConnectorFailure.NotFound, Action(409, """{"error_summary":"path/not_found/"}""", kind: MediaSourceKind.Dropbox).Error?.Failure);
        Assert.AreEqual(ProviderActionKind.Backoff, Action(429).Kind);
        Assert.AreEqual(ProviderActionKind.Backoff, Action(503).Kind);
        Assert.AreEqual(TimeSpan.FromSeconds(3), Action(429, retryAfter: "3").RetryAfter);
        // A hostile Retry-After can't stall playback for minutes.
        Assert.AreEqual(TimeSpan.FromSeconds(10), Action(429, retryAfter: "600").RetryAfter);
        Assert.AreEqual(418, Action(418).Error?.Status);
        Assert.AreEqual(ConnectorFailure.ServerError, Action(418).Error?.Failure);
    }
}
