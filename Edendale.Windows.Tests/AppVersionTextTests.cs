using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The version Settings → About shows, read from the informational version
/// Directory.Build.props sets (VersionPrefix, plus +commit in a release).
/// </summary>
[TestClass]
public sealed class AppVersionTextTests
{
    [TestMethod]
    public void ReleaseShowsTheCommit() =>
        Assert.AreEqual("27.1.0 (5780275)", AppVersionText.Display("27.1.0+5780275"));

    [TestMethod]
    public void LocalBuildShowsTheVersion() =>
        Assert.AreEqual("27.1.0", AppVersionText.Display("27.1.0"));

    [TestMethod]
    public void EmptyCommitIsDropped() =>
        Assert.AreEqual("27.1.0", AppVersionText.Display("27.1.0+"));

    [TestMethod]
    public void NoVersionIsNull()
    {
        Assert.IsNull(AppVersionText.Display(null));
        Assert.IsNull(AppVersionText.Display(""));
        Assert.IsNull(AppVersionText.Display("   "));
        Assert.IsNull(AppVersionText.Display("+5780275"));
    }
}
