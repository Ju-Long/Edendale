using Edendale.Windows.Core;
using Edendale.Windows.Core.Handoff;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Services.Sync;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// FEATURES-27.1.md §6.5 H10 and SYNC-PROTOCOL.md §8.5: no pairing code, QR
/// secret, session key, refresh token, password, review text, or title
/// reaches the log while a sync round and a handoff run with known secrets.
/// </summary>
[TestClass]
public sealed class LogHygieneTests
{
    private readonly List<string> _lines = [];
    private string _root = "";

    [TestInitialize]
    public void Setup()
    {
        _root = Path.Combine(Path.GetTempPath(), "edendale-hygiene-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
        SyncLog.Clear();
        SyncLog.Sink += Capture;
    }

    [TestCleanup]
    public void Cleanup()
    {
        SyncLog.Sink -= Capture;
        Directory.Delete(_root, recursive: true);
    }

    private void Capture(string line)
    {
        lock (_lines) _lines.Add(line);
    }

    private void AssertNoneLogged(params string[] secrets)
    {
        string[] lines;
        lock (_lines) lines = [.. _lines, .. SyncLog.Tail];
        Assert.IsTrue(lines.Length > 0, "the log was written to");
        foreach (var secret in secrets)
        {
            foreach (var line in lines)
            {
                Assert.IsFalse(line.Contains(secret, StringComparison.OrdinalIgnoreCase), $"the log leaked '{secret}': {line}");
            }
        }
    }

    [TestMethod]
    public async Task ASyncRound_LogsCountsAndStamps_NotContents()
    {
        var location = Path.Combine(_root, "location");
        using var a = new SyncEngineTests.Device(_root, "A", 1762000000000, location);
        a.Media.SetFavourite(603, "movie", true, "A Secret Title", "/poster-secret.jpg");
        a.Media.SetRating(1399, "tv", 9.5);
        a.Progress.Update(603, "movie", 0.5, 100, durationSeconds: 200);
        await a.Engine.SyncNowAsync();

        // A second device's file with a review, read by A.
        using var b = new SyncEngineTests.Device(_root, "B", 1762000001000, location);
        b.Media.SetFavourite(11, "movie", true);
        await b.Engine.SyncNowAsync();
        await a.Engine.SyncNowAsync();

        // File names and counts are allowed (§8.5); record contents, titles, and paths are not.
        AssertNoneLogged("A Secret Title", "poster-secret", "\"favourite\"", "rating", "watchlist", location, _root);
        Assert.IsTrue(_lines.Any(line => line.Contains("wrote own file")));
    }

    [TestMethod]
    public async Task AHandoff_LogsNothingSecret()
    {
        var (hostStream, guestStream, cleanup) = await HandoffSessionTests.ConnectAsync();
        using var _ = cleanup;
        using var pairing = new HandoffHostPairing("PC");
        var code = pairing.Code!;
        var qr = pairing.QrCode("127.0.0.1", 5);
        var received = new List<HandoffOffer>();
        SyncLog.Info("handoff", "pairing screen open");

        var host = HandoffSession.RunHostAsync(hostStream, pairing, new HandoffParty
        {
            DeviceName = "PC",
            Role = HandoffRole.Sender,
            ProvideOffer = (_, _) => Task.FromResult(HandoffSessionTests.SampleOffer),
            Progress = step => SyncLog.Info("handoff", "host " + step),
        }, CancellationToken.None);
        var guest = HandoffSession.RunGuestAsync(guestStream, new HandoffGuestPairing.Typed(code), new HandoffParty
        {
            DeviceName = "Phone",
            Platform = "android",
            Role = HandoffRole.Receiver,
            StoreOffer = (offer, _, _) => { received.Add(offer); return Task.FromResult(new HandoffResult(["location"], [])); },
            Progress = step => SyncLog.Info("handoff", "guest " + step),
        }, CancellationToken.None);
        await host;
        await guest;
        SyncLog.Info("handoff", "done: " + HandoffSessionTests.SampleOffer);

        var secretHex = Convert.ToHexString(qr.Secret);
        AssertNoneLogged(code, HandoffCrypto.Base64Url(qr.Secret), secretHex, "REFRESH-TOKEN-SECRET-7f3a", "PASSWORD-SECRET-9c1d", "ONEDRIVE-REFRESH-SECRET-2b", "me@example.com");
        Assert.IsTrue(_lines.Any(line => line.Contains("host result")));
    }

    [TestMethod]
    public void ErrorsAreLoggedByClassNotMessage()
    {
        SyncLog.Error("sync", "write", new IOException("Could not write C:\\Users\\someone\\OneDrive\\Apps\\Edendale\\x.json: password=hunter2"));
        SyncLog.Error("sync", "list", new ConnectorException(ConnectorFailure.AuthenticationFailed, "nas.local"));
        AssertNoneLogged("hunter2", "someone", "nas.local");
        Assert.IsTrue(_lines.Any(line => line.Contains("IOException")));
        Assert.IsTrue(_lines.Any(line => line.Contains("AuthenticationFailed")));
    }
}
