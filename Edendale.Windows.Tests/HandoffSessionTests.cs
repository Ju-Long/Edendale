using System.Net;
using System.Net.Sockets;
using System.Text;
using Edendale.Windows.Core.Handoff;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Whole handoffs over a loopback socket (SYNC-PROTOCOL.md §9.3–§9.9): QR and
/// typed-code pairing in both directions of transfer, a wrong secret and a
/// wrong code failing before any payload moves, same-role and version
/// errors, declines, and the Google Drive ecosystem rule.
/// </summary>
[TestClass]
public sealed class HandoffSessionTests
{
    /// <summary>A connected pair of loopback streams.</summary>
    internal static async Task<(NetworkStream Host, NetworkStream Guest, IDisposable Cleanup)> ConnectAsync()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var accept = listener.AcceptTcpClientAsync();
        var guestClient = new TcpClient();
        await guestClient.ConnectAsync(IPAddress.Loopback, port);
        var hostClient = await accept;
        listener.Stop();
        return (hostClient.GetStream(), guestClient.GetStream(), new Disposer(() => { hostClient.Dispose(); guestClient.Dispose(); }));
    }

    private sealed class Disposer(Action dispose) : IDisposable
    {
        public void Dispose() => dispose();
    }

    /// <summary>Offers hold lists, so records compare by their wire form.</summary>
    internal static void AssertSameOffer(HandoffOffer expected, HandoffOffer actual) =>
        CollectionAssert.AreEqual(HandoffMessages.EncodeOffer(expected), HandoffMessages.EncodeOffer(actual));

    internal static readonly HandoffAccount GoogleAccount = new("gdrive", "110169484474386276334", "me@example.com", "Me", "REFRESH-TOKEN-SECRET-7f3a", ["https://www.googleapis.com/auth/drive.file"], null);
    internal static readonly HandoffLogin SftpLogin = new("sftp", "nas.local", 2222, "me", "PASSWORD-SECRET-9c1d", "SHA256:abc", null, null, ["sftp://nas.local:2222/media/"]);
    internal static readonly HandoffOffer SampleOffer = new(true,
        new HandoffLocation("onedrive", "Family", new HandoffAccount("onedrive", "ms-user-1", "me@outlook.com", "Me", "ONEDRIVE-REFRESH-SECRET-2b", ["Files.ReadWrite.AppFolder"], "drive-1")),
        [GoogleAccount], [SftpLogin]);

    private static HandoffParty Sender(string name, HandoffOffer offer, string platform = "windows") => new()
    {
        DeviceName = name,
        Platform = platform,
        Role = HandoffRole.Sender,
        ProvideOffer = (peer, _) => Task.FromResult(offer),
    };

    private static HandoffParty Receiver(string name, List<HandoffOffer> received, string platform = "windows") => new()
    {
        DeviceName = name,
        Platform = platform,
        Role = HandoffRole.Receiver,
        StoreOffer = (offer, peer, _) =>
        {
            received.Add(offer);
            var stored = new List<string>();
            if (offer.Location is not null) stored.Add(HandoffResult.LocationItem);
            stored.AddRange(offer.AccountList.Select(HandoffResult.AccountItem));
            stored.AddRange(offer.LoginList.Select(HandoffResult.LoginItem));
            return Task.FromResult(new HandoffResult(stored, [new HandoffFailure("login:webdav:old", "unreachable")]));
        },
    };

    [TestMethod]
    public async Task QrPairing_HostSends_GuestReceives()
    {
        var (hostStream, guestStream, cleanup) = await ConnectAsync();
        using var _ = cleanup;
        using var pairing = new HandoffHostPairing("Living Room PC");
        var qr = pairing.QrCode("127.0.0.1", 1);
        var received = new List<HandoffOffer>();

        var host = HandoffSession.RunHostAsync(hostStream, pairing, Sender("Living Room PC", SampleOffer), CancellationToken.None);
        var guest = HandoffSession.RunGuestAsync(guestStream, new HandoffGuestPairing.Qr(HandoffQrCode.Parse(qr.ToUri())!), Receiver("Phone", received, "android"), CancellationToken.None);
        var (hostResult, guestResult) = (await host, await guest);

        Assert.AreEqual("qr", hostResult.PairedBy);
        Assert.AreEqual("Phone", hostResult.Peer.Name);
        Assert.AreEqual("android", hostResult.Peer.Platform);
        Assert.AreEqual(HandoffRole.Receiver, hostResult.Peer.Role);
        Assert.AreEqual(1, received.Count);
        AssertSameOffer(SampleOffer, received[0]);
        Assert.AreEqual("REFRESH-TOKEN-SECRET-7f3a", received[0].AccountList[0].RefreshToken);
        Assert.AreEqual(SftpLogin with { Addresses = null }, received[0].LoginList[0] with { Addresses = null });
        CollectionAssert.AreEqual(SftpLogin.Addresses!.ToList(), received[0].LoginList[0].Addresses!.ToList());
        CollectionAssert.AreEqual(new[] { "location", "account:gdrive:110169484474386276334", "login:sftp:nas.local" }, hostResult.Result.Stored.ToList());
        Assert.AreEqual("unreachable", hostResult.Result.Failed[0].Reason);
        CollectionAssert.AreEqual(HandoffMessages.EncodeResult(hostResult.Result), HandoffMessages.EncodeResult(guestResult.Result));
    }

    [TestMethod]
    public async Task CodePairing_GuestSends_HostReceives()
    {
        var (hostStream, guestStream, cleanup) = await ConnectAsync();
        using var _ = cleanup;
        using var pairing = new HandoffHostPairing("Windows PC");
        var received = new List<HandoffOffer>();

        var host = HandoffSession.RunHostAsync(hostStream, pairing, Receiver("Windows PC", received), CancellationToken.None);
        var guest = HandoffSession.RunGuestAsync(guestStream, new HandoffGuestPairing.Typed(pairing.Code!), Sender("Pixel", SampleOffer, "android"), CancellationToken.None);
        var (hostResult, guestResult) = (await host, await guest);

        Assert.AreEqual("code", hostResult.PairedBy);
        Assert.AreEqual(1, received.Count);
        AssertSameOffer(SampleOffer, received[0]);
        Assert.AreEqual(3, guestResult.Result.Stored.Count);
        Assert.AreEqual("Windows PC", guestResult.Peer.Name);
    }

    [TestMethod]
    public async Task WrongSecret_FailsBeforeConfirm_AndTheCodeIsSpentAfterOneHello()
    {
        var (hostStream, guestStream, cleanup) = await ConnectAsync();
        using var _ = cleanup;
        using var pairing = new HandoffHostPairing("PC");
        var real = pairing.QrCode(null, null);
        var wrong = new HandoffQrCode(real.Name, null, null, real.PublicKey, new byte[16]);

        var host = HandoffSession.RunHostAsync(hostStream, pairing, Sender("PC", SampleOffer), CancellationToken.None);
        var guest = HandoffSession.RunGuestAsync(guestStream, new HandoffGuestPairing.Qr(wrong), Receiver("Phone", []), CancellationToken.None);
        Assert.AreEqual(HandoffError.Secret, (await Assert.ThrowsExceptionAsync<HandoffException>(() => host)).Reason);
        Assert.AreEqual(HandoffError.Secret, (await Assert.ThrowsExceptionAsync<HandoffException>(() => guest)).Reason, "the guest reads the error frame");

        // A second hello on the same code is refused even with the right secret.
        var (hostStream2, guestStream2, cleanup2) = await ConnectAsync();
        using var __ = cleanup2;
        var host2 = HandoffSession.RunHostAsync(hostStream2, pairing, Sender("PC", SampleOffer), CancellationToken.None);
        var guest2 = HandoffSession.RunGuestAsync(guestStream2, new HandoffGuestPairing.Qr(real), Receiver("Phone", []), CancellationToken.None);
        Assert.AreEqual(HandoffError.Expired, (await Assert.ThrowsExceptionAsync<HandoffException>(() => host2)).Reason);
        await Assert.ThrowsExceptionAsync<HandoffException>(() => guest2);
    }

    [TestMethod]
    public async Task WrongCode_FailsAtRound3_WithNothingSent()
    {
        var (hostStream, guestStream, cleanup) = await ConnectAsync();
        using var _ = cleanup;
        using var pairing = new HandoffHostPairing("PC");
        var wrongCode = pairing.Code == "000000" ? "000001" : "000000";
        var offered = false;
        var sender = new HandoffParty
        {
            DeviceName = "PC",
            Role = HandoffRole.Sender,
            ProvideOffer = (_, _) => { offered = true; return Task.FromResult(SampleOffer); },
        };

        var host = HandoffSession.RunHostAsync(hostStream, pairing, sender, CancellationToken.None);
        var guest = HandoffSession.RunGuestAsync(guestStream, new HandoffGuestPairing.Typed(wrongCode), Receiver("Phone", []), CancellationToken.None);
        Assert.AreEqual(HandoffError.Code, (await Assert.ThrowsExceptionAsync<HandoffException>(() => host)).Reason);
        Assert.AreEqual(HandoffError.Code, (await Assert.ThrowsExceptionAsync<HandoffException>(() => guest)).Reason);
        Assert.IsFalse(offered, "no payload was built or sent");
    }

    [TestMethod]
    public async Task SameRole_EndsWithRoleError()
    {
        var (hostStream, guestStream, cleanup) = await ConnectAsync();
        using var _ = cleanup;
        using var pairing = new HandoffHostPairing("PC");
        var host = HandoffSession.RunHostAsync(hostStream, pairing, Sender("PC", SampleOffer), CancellationToken.None);
        var guest = HandoffSession.RunGuestAsync(guestStream, new HandoffGuestPairing.Typed(pairing.Code!), Sender("Phone", SampleOffer), CancellationToken.None);
        Assert.AreEqual(HandoffError.Role, (await Assert.ThrowsExceptionAsync<HandoffException>(() => host)).Reason);
        Assert.AreEqual(HandoffError.Role, (await Assert.ThrowsExceptionAsync<HandoffException>(() => guest)).Reason);
    }

    [TestMethod]
    public async Task ADecline_ReachesTheReceiver()
    {
        var (hostStream, guestStream, cleanup) = await ConnectAsync();
        using var _ = cleanup;
        using var pairing = new HandoffHostPairing("PC");
        var host = HandoffSession.RunHostAsync(hostStream, pairing, Sender("PC", HandoffOffer.Declined), CancellationToken.None);
        var guest = HandoffSession.RunGuestAsync(guestStream, new HandoffGuestPairing.Qr(pairing.QrCode(null, null)), Receiver("Phone", []), CancellationToken.None);
        var hostResult = await host;
        Assert.IsFalse(hostResult.Offer.Approved);
        Assert.AreEqual(HandoffError.Declined, (await Assert.ThrowsExceptionAsync<HandoffException>(() => guest)).Reason);
    }

    [TestMethod]
    public async Task AnUnsupportedVersion_IsAnsweredWithAVersionError()
    {
        var (hostStream, guestStream, cleanup) = await ConnectAsync();
        using var _ = cleanup;
        using var pairing = new HandoffHostPairing("PC");
        var host = HandoffSession.RunHostAsync(hostStream, pairing, Sender("PC", SampleOffer), CancellationToken.None);
        await HandoffFrames.WriteAsync(guestStream, Encoding.UTF8.GetBytes("""{"v":1,"type":"round1","id":"edendale-phone"}"""), CancellationToken.None);
        Assert.AreEqual(HandoffError.Version, (await Assert.ThrowsExceptionAsync<HandoffException>(() => host)).Reason);
        var answer = await HandoffFrames.ReadAsync(guestStream, TimeSpan.FromSeconds(5), CancellationToken.None);
        Assert.IsTrue(HandoffFrames.Utf8(answer).Contains("\"reason\":\"version\""), HandoffFrames.Utf8(answer));
    }

    [TestMethod]
    public async Task AnExpiredCode_IsRefused()
    {
        var now = DateTimeOffset.UtcNow;
        using var pairing = new HandoffHostPairing("PC", () => now);
        var code = pairing.Code!;
        now += TimeSpan.FromMinutes(3);
        Assert.IsTrue(pairing.IsExpired);
        var (hostStream, guestStream, cleanup) = await ConnectAsync();
        using var _ = cleanup;
        var host = HandoffSession.RunHostAsync(hostStream, pairing, Sender("PC", SampleOffer), CancellationToken.None);
        var guest = HandoffSession.RunGuestAsync(guestStream, new HandoffGuestPairing.Typed(code), Receiver("Phone", []), CancellationToken.None);
        Assert.AreEqual(HandoffError.Expired, (await Assert.ThrowsExceptionAsync<HandoffException>(() => host)).Reason);
        Assert.AreEqual(HandoffError.Code, (await Assert.ThrowsExceptionAsync<HandoffException>(() => guest)).Reason);
    }

    [TestMethod]
    public void Messages_RoundTrip_AndNeverPrintSecrets()
    {
        var offer = HandoffMessages.DecodeOffer(HandoffMessages.EncodeOffer(SampleOffer));
        AssertSameOffer(SampleOffer, offer);
        Assert.AreEqual(SampleOffer.Location!.Space, offer.Location!.Space);
        Assert.AreEqual(SampleOffer.Location.Account!.RefreshToken, offer.Location.Account!.RefreshToken);
        Assert.AreEqual(GoogleAccount with { Scopes = GoogleAccount.Scopes }, offer.AccountList[0] with { Scopes = GoogleAccount.Scopes });
        CollectionAssert.AreEqual(GoogleAccount.Scopes.ToList(), offer.AccountList[0].Scopes.ToList());
        Assert.IsFalse(SampleOffer.ToString().Contains("SECRET"));
        Assert.IsFalse(GoogleAccount.ToString().Contains("REFRESH"));
        Assert.IsFalse(SftpLogin.ToString().Contains("PASSWORD"));

        var folderOnly = new HandoffOffer(true, new HandoffLocation("folder", "Family"));
        AssertSameOffer(folderOnly, HandoffMessages.DecodeOffer(HandoffMessages.EncodeOffer(folderOnly)));
        var hinted = new HandoffOffer(true, new HandoffLocation("gdrive", "Family", null, null, "person@example.com", "1AbC"));
        AssertSameOffer(hinted, HandoffMessages.DecodeOffer(HandoffMessages.EncodeOffer(hinted)));
        Assert.IsFalse(HandoffMessages.DecodeOffer(HandoffMessages.EncodeOffer(HandoffOffer.Declined)).Approved);

        var role = new HandoffRoleMessage(HandoffRole.Receiver, "TV", "tvos");
        Assert.AreEqual(role, HandoffMessages.DecodeRole(HandoffMessages.EncodeRole(role)));
        var result = new HandoffResult(["location"], [new HandoffFailure("account:gdrive:x", "token refused")]);
        Assert.AreEqual(result.Stored[0], HandoffMessages.DecodeResult(HandoffMessages.EncodeResult(result)).Stored[0]);
        Assert.AreEqual("token refused", HandoffMessages.DecodeResult(HandoffMessages.EncodeResult(result)).Failed[0].Reason);

        Assert.AreEqual(HandoffError.Malformed, Assert.ThrowsException<HandoffException>(() => HandoffMessages.DecodeOffer(HandoffMessages.EncodeRole(role))).Reason);
        Assert.AreEqual(HandoffError.Malformed, Assert.ThrowsException<HandoffException>(() => HandoffMessages.DecodeOffer("""{"v":2,"type":"offer","status":"approved","accounts":[{"kind":"gdrive","subject":"s","refreshToken":""}]}"""u8.ToArray())).Reason);
    }

    [TestMethod]
    public void GoogleDrive_MovesOnlyWithinAnEcosystem()
    {
        Assert.IsTrue(HandoffEcosystem.SharesGoogleClient("ios", "tvos"));
        Assert.IsTrue(HandoffEcosystem.SharesGoogleClient("android", "horizon"));
        Assert.IsTrue(HandoffEcosystem.SharesGoogleClient("windows", "windows"));
        Assert.IsFalse(HandoffEcosystem.SharesGoogleClient("windows", "android"));
        Assert.IsFalse(HandoffEcosystem.SharesGoogleClient("macos", "windows"));
        Assert.IsFalse(HandoffEcosystem.SharesGoogleClient("windows", null));
        Assert.IsFalse(HandoffEcosystem.SharesGoogleClient("fridge", "fridge"), "unknown platforms never share a client");
        Assert.IsTrue(HandoffEcosystem.CannotSignInToGoogle("tvos"));
        Assert.IsTrue(HandoffEcosystem.CannotSignInToGoogle("androidtv"));
        Assert.IsFalse(HandoffEcosystem.CannotSignInToGoogle("windows"));
    }
}
