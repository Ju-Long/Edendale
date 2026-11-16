using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The resting pointer over the video hides (apple-27.1 a4c8561), ported
/// from Apple's PlayerIdlePointerTests with a fake clock instead of sleeps.
/// </summary>
[TestClass]
public sealed class IdlePointerTests
{
    private static readonly TimeSpan Delay = TimeSpan.FromMilliseconds(50);

    private static TimeSpan At(double milliseconds) => TimeSpan.FromMilliseconds(milliseconds);

    [TestMethod]
    public void HidesAfterFiveSecondsByDefault()
    {
        Assert.AreEqual(TimeSpan.FromSeconds(5), IdlePointer.DefaultDelay);
        Assert.AreEqual(IdlePointer.DefaultDelay, new IdlePointer().Delay);
    }

    [TestMethod]
    public void HidesOnceThePointerRests()
    {
        var pointer = new IdlePointer(Delay);
        Assert.IsTrue(pointer.PointerMoved(10, 10, At(0)));
        Assert.IsFalse(pointer.IsHidden);
        Assert.AreEqual(At(50), pointer.Deadline);
        Assert.IsFalse(pointer.Tick(At(49), isButtonHeld: false));
        Assert.IsTrue(pointer.Tick(At(50), isButtonHeld: false));
        Assert.IsTrue(pointer.IsHidden);
        Assert.IsNull(pointer.Deadline);
    }

    [TestMethod]
    public void AMoveBringsItBackAndRestartsTheCountdown()
    {
        var pointer = new IdlePointer(Delay);
        pointer.PointerMoved(10, 10, At(0));
        pointer.Tick(At(50), isButtonHeld: false);
        Assert.IsTrue(pointer.IsHidden);

        Assert.IsTrue(pointer.PointerMoved(12, 10, At(80)));
        Assert.IsFalse(pointer.IsHidden);
        Assert.IsFalse(pointer.Tick(At(100), isButtonHeld: false), "the countdown started again at the move");
        Assert.IsTrue(pointer.Tick(At(130), isButtonHeld: false));
    }

    [TestMethod]
    public void AReportAtTheSamePointLeavesItHidden()
    {
        var pointer = new IdlePointer(Delay);
        pointer.PointerMoved(10, 10, At(0));
        pointer.Tick(At(50), isButtonHeld: false);

        Assert.IsFalse(pointer.PointerMoved(10, 10, At(70)));
        Assert.IsTrue(pointer.IsHidden);
        Assert.IsNull(pointer.Deadline);
    }

    [TestMethod]
    public void StaysWhileAButtonIsHeld()
    {
        var pointer = new IdlePointer(Delay);
        pointer.PointerMoved(10, 10, At(0));

        Assert.IsFalse(pointer.Tick(At(50), isButtonHeld: true));
        Assert.IsFalse(pointer.IsHidden);
        Assert.AreEqual(At(100), pointer.Deadline);
        // Released: it hides once the countdown that started over runs out.
        Assert.IsTrue(pointer.Tick(At(100), isButtonHeld: false));
    }

    [TestMethod]
    public void LeavingTheVideoShowsItAndStopsTheCountdown()
    {
        var pointer = new IdlePointer(Delay);
        pointer.PointerMoved(10, 10, At(0));
        pointer.Tick(At(50), isButtonHeld: false);

        pointer.PointerLeft();
        Assert.IsFalse(pointer.IsHidden);
        Assert.IsNull(pointer.Deadline);
        Assert.IsFalse(pointer.Tick(At(500), isButtonHeld: false));
        // Coming back over the video, even at the old point, is a move.
        Assert.IsTrue(pointer.PointerMoved(10, 10, At(600)));
        Assert.AreEqual(At(650), pointer.Deadline);
    }

    [TestMethod]
    public void RestartingShowsItWhileOverTheVideo()
    {
        var pointer = new IdlePointer(Delay);
        pointer.PointerMoved(10, 10, At(0));
        pointer.Tick(At(50), isButtonHeld: false);

        pointer.RestartCountdown(At(60));
        Assert.IsFalse(pointer.IsHidden);
        Assert.AreEqual(At(110), pointer.Deadline);
        Assert.IsTrue(pointer.Tick(At(110), isButtonHeld: false));

        pointer.PointerLeft();
        pointer.RestartCountdown(At(200));
        Assert.IsFalse(pointer.IsHidden);
        Assert.IsNull(pointer.Deadline, "away from the video nothing counts down");
    }
}
