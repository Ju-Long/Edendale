using System.Net;
using Edendale.Windows.Services.Sync;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The name this device shows the others (SYNC-PROTOCOL.md §3): the PC's own
/// name until the user sets one, never longer than the 64 characters the
/// strict reader accepts.
/// </summary>
[TestClass]
public sealed class SyncStateTests
{
    private string _root = "";

    [TestInitialize]
    public void Setup()
    {
        _root = Path.Combine(Path.GetTempPath(), "edendale-sync-state-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(_root, recursive: true);

    [TestMethod]
    public void DeviceName_IsTheFullHostName_NotTheNetBiosName()
    {
        var state = new SyncState(Path.Combine(_root, "sync-state.json"));
        var host = Dns.GetHostName().Trim();

        // MachineName would be upper-cased and cut to 15 characters.
        Assert.AreEqual(host.Length <= 64 ? host : host[..64], state.DeviceName);
    }

    [TestMethod]
    public void DeviceName_IsTrimmed_AndCutToTheReadersLimit()
    {
        var state = new SyncState(Path.Combine(_root, "sync-state.json"));

        state.DeviceName = "  Living Room PC  ";
        Assert.AreEqual("Living Room PC", state.DeviceName);

        state.DeviceName = new string('x', 80);
        Assert.AreEqual(64, state.DeviceName.Length, "a longer name would get this device's file skipped");
    }
}
