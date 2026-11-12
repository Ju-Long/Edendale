using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The buffering indicator's stall signal (apple-27.1 5020ec3 on Windows):
/// LibVLC's playback clock standing still while it plays.
/// </summary>
[TestClass]
public sealed class PlaybackStallWatchTests
{
    private const long Duration = 1_200_000;

    private static TimeSpan At(double seconds) => TimeSpan.FromSeconds(seconds);

    [TestMethod]
    public void AClockThatStandsStillForASecondIsAStall()
    {
        var watch = new PlaybackStallWatch();
        Assert.IsFalse(watch.Observe(60_000, Duration, playing: true, At(0)));
        Assert.IsFalse(watch.Observe(60_000, Duration, playing: true, At(0.75)));
        Assert.IsTrue(watch.Observe(60_000, Duration, playing: true, At(1)));
        Assert.IsTrue(watch.Observe(60_000, Duration, playing: true, At(5)));
        // The time moving again ends it at once.
        Assert.IsFalse(watch.Observe(60_250, Duration, playing: true, At(5.25)));
    }

    [TestMethod]
    public void AMovingClockNeverStalls()
    {
        var watch = new PlaybackStallWatch();
        for (var tick = 0; tick < 20; tick++)
        {
            Assert.IsFalse(watch.Observe(60_000 + tick * 250, Duration, playing: true, At(tick * 0.25)));
        }
    }

    [TestMethod]
    public void APauseIsNotAStallAndPlayingAgainCountsAfresh()
    {
        var watch = new PlaybackStallWatch();
        watch.Observe(60_000, Duration, playing: true, At(0));
        Assert.IsFalse(watch.Observe(60_000, Duration, playing: false, At(3)));

        Assert.IsFalse(watch.Observe(60_000, Duration, playing: true, At(3.5)));
        Assert.IsFalse(watch.Observe(60_000, Duration, playing: true, At(4.25)));
        Assert.IsTrue(watch.Observe(60_000, Duration, playing: true, At(4.5)));
    }

    [TestMethod]
    public void TheLastTwoSecondsDrainWithoutAStall()
    {
        var watch = new PlaybackStallWatch();
        watch.Observe(Duration - 1_500, Duration, playing: true, At(0));
        Assert.IsFalse(watch.Observe(Duration - 1_500, Duration, playing: true, At(3)));

        // Without a known length any still clock counts.
        var unknown = new PlaybackStallWatch();
        unknown.Observe(5_000, 0, playing: true, At(0));
        Assert.IsTrue(unknown.Observe(5_000, 0, playing: true, At(1)));
    }

    [TestMethod]
    public void AResetStartsTheCountAtTheNextLook()
    {
        var watch = new PlaybackStallWatch();
        watch.Observe(60_000, Duration, playing: true, At(0));
        watch.Reset();
        Assert.IsFalse(watch.Observe(60_000, Duration, playing: true, At(1.5)));
        Assert.IsTrue(watch.Observe(60_000, Duration, playing: true, At(2.5)));
    }

    /// <summary>e90a988: Home's hero waits while the player is presented, told once per change.</summary>
    [TestMethod]
    public void ThePlayerReportsPresentationOncePerChange()
    {
        var session = new PlayerSession();
        var changes = new List<bool>();
        session.PresentedChanged += (_, _) => changes.Add(session.IsPresented);
        Assert.IsFalse(session.IsPresented);

        session.SetPresented(true);
        session.SetPresented(true); // the next episode opens in the same player
        session.SetPresented(false);
        session.SetPresented(false);

        CollectionAssert.AreEqual(new[] { true, false }, changes);
    }
}
