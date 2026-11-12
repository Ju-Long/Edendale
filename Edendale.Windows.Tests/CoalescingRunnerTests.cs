using System.Collections.Concurrent;
using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The TMDB sweep's runner: one pass at a time, and no request dropped
/// (android-27.1 CoalescingRunnerTest.kt, plus the merged flag and the
/// nothing-to-do check Windows adds).
/// </summary>
[TestClass]
public sealed class CoalescingRunnerTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(10);

    /// <summary>A pass that waits, the first time it runs, until the test lets it finish.</summary>
    private sealed class HeldPass
    {
        private readonly TaskCompletionSource _started = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly TaskCompletionSource _release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly ConcurrentQueue<bool> _flags = new();

        public Exception? FailFirst { get; init; }

        public Task Started => _started.Task;

        public bool[] Flags => [.. _flags];

        public void Release() => _release.SetResult();

        public async Task RunAsync(bool flag)
        {
            _flags.Enqueue(flag);
            if (_flags.Count != 1) return;
            _started.SetResult();
            await _release.Task;
            if (FailFirst is { } failure) throw failure;
        }
    }

    [TestMethod]
    public async Task ARequestWhileIdleRunsOnePass()
    {
        var passes = 0;
        var runner = new CoalescingRunner(_ =>
        {
            Interlocked.Increment(ref passes);
            return Task.CompletedTask;
        });

        await runner.Request().WaitAsync(Patience);
        Assert.AreEqual(1, passes);
        Assert.IsFalse(runner.IsRunning);

        await runner.Request().WaitAsync(Patience);
        Assert.AreEqual(2, passes);
    }

    [TestMethod]
    [DataRow(1)]
    [DataRow(5)]
    public async Task RequestsDuringAPassRunExactlyOneMore(int requestsDuringPass)
    {
        var pass = new HeldPass();
        var runner = new CoalescingRunner(pass.RunAsync);

        var run = runner.Request();
        await pass.Started.WaitAsync(Patience);
        Assert.IsTrue(runner.IsRunning);
        for (var i = 0; i < requestsDuringPass; i++) Assert.AreSame(run, runner.Request());
        pass.Release();
        await run.WaitAsync(Patience);

        Assert.AreEqual(2, pass.Flags.Length, $"{requestsDuringPass} request(s) during a pass");
        Assert.IsFalse(runner.IsRunning);
    }

    [TestMethod]
    public async Task AFailedPassDoesNotStopLaterOnes()
    {
        var pass = new HeldPass { FailFirst = new HttpRequestException("TMDB unreachable") };
        var runner = new CoalescingRunner(pass.RunAsync);

        var run = runner.Request();
        await pass.Started.WaitAsync(Patience);
        runner.Request();
        pass.Release();
        // The failure doesn't fault the run, and the pass asked for during it still runs.
        await run.WaitAsync(Patience);
        Assert.AreEqual(2, pass.Flags.Length);

        await runner.Request().WaitAsync(Patience);
        Assert.AreEqual(3, pass.Flags.Length);
    }

    [TestMethod]
    public async Task AFlagAnyMergedRequestSetReachesTheNextPass()
    {
        var pass = new HeldPass();
        var runner = new CoalescingRunner(pass.RunAsync);

        var run = runner.Request(flag: false);
        await pass.Started.WaitAsync(Patience);
        runner.Request(flag: true);
        runner.Request(flag: false);
        pass.Release();
        await run.WaitAsync(Patience);
        CollectionAssert.AreEqual(new[] { false, true }, pass.Flags);

        // A later run starts without it.
        await runner.Request(flag: false).WaitAsync(Patience);
        CollectionAssert.AreEqual(new[] { false, true, false }, pass.Flags);
    }

    [TestMethod]
    public async Task NothingToDoStartsNoPass()
    {
        var passes = 0;
        var changes = 0;
        var runner = new CoalescingRunner(
            _ =>
            {
                Interlocked.Increment(ref passes);
                return Task.CompletedTask;
            },
            hasWork: retrying => retrying);
        runner.RunningChanged += (_, _) => Interlocked.Increment(ref changes);

        var idle = runner.Request(flag: false);
        Assert.IsTrue(idle.IsCompleted);
        Assert.IsFalse(runner.IsRunning);
        Assert.AreEqual(0, passes);
        Assert.AreEqual(0, changes);

        // The check sees the request's flag: a forced rescan has work.
        await runner.Request(flag: true).WaitAsync(Patience);
        Assert.AreEqual(1, passes);
        Assert.AreEqual(2, changes, "started and ended");
    }

    [TestMethod]
    public async Task ARequestDuringAPassQueuesOneWithoutTheCheck()
    {
        // Titles stored during a pass may have missed its snapshot, so the
        // request queues a pass whatever the check would have said then.
        var checks = 0;
        var pass = new HeldPass();
        var runner = new CoalescingRunner(pass.RunAsync, hasWork: _ => Interlocked.Increment(ref checks) == 1);

        var run = runner.Request();
        await pass.Started.WaitAsync(Patience);
        runner.Request();
        pass.Release();
        await run.WaitAsync(Patience);

        Assert.AreEqual(2, pass.Flags.Length);
        Assert.AreEqual(1, checks);
    }
}
