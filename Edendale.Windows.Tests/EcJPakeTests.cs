using System.Text.Json;
using Edendale.Windows.Core.Handoff;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Org.BouncyCastle.Math;

namespace Edendale.Windows.Tests;

/// <summary>
/// The typed-code pairing (SYNC-PROTOCOL.md §9.4): J-PAKE over P-256 as Bouncy
/// Castle Java runs it on Android, ported here. RFC 8236 publishes no test
/// vectors (checked against the RFC text on 2026-11-02), so the checks are
/// agreement, the wrong-password failure at round 3 and only there, refused
/// forgeries, and the wire shape the Android handoff reads.
/// </summary>
[TestClass]
public sealed class EcJPakeTests
{
    [TestMethod]
    public void BothSidesAgreeTheSameKey()
    {
        var host = new EcJPakeParticipant("edendale-host", "123456");
        var guest = new EcJPakeParticipant("edendale-guest", "123456");

        var guestRound1 = guest.CreateRound1PayloadToSend();
        var hostRound1 = host.CreateRound1PayloadToSend();
        host.ValidateRound1PayloadReceived(guestRound1);
        guest.ValidateRound1PayloadReceived(hostRound1);

        var guestRound2 = guest.CreateRound2PayloadToSend();
        var hostRound2 = host.CreateRound2PayloadToSend();
        host.ValidateRound2PayloadReceived(guestRound2);
        guest.ValidateRound2PayloadReceived(hostRound2);

        var hostKey = host.CalculateKeyingMaterial();
        var guestKey = guest.CalculateKeyingMaterial();
        Assert.AreEqual(hostKey, guestKey);

        var guestRound3 = guest.CreateRound3PayloadToSend(guestKey);
        var hostRound3 = host.CreateRound3PayloadToSend(hostKey);
        host.ValidateRound3PayloadReceived(guestRound3, hostKey);
        guest.ValidateRound3PayloadReceived(hostRound3, guestKey);
        Assert.AreEqual(EcJPakeParticipant.StateRound3Validated, host.State);
    }

    [TestMethod]
    public void AWrongPassword_FailsAtRound3_AndNotBefore()
    {
        var host = new EcJPakeParticipant("edendale-host", "123456");
        var guest = new EcJPakeParticipant("edendale-guest", "654321");

        var guestRound1 = guest.CreateRound1PayloadToSend();
        var hostRound1 = host.CreateRound1PayloadToSend();
        host.ValidateRound1PayloadReceived(guestRound1);
        guest.ValidateRound1PayloadReceived(hostRound1);
        var guestRound2 = guest.CreateRound2PayloadToSend();
        var hostRound2 = host.CreateRound2PayloadToSend();
        host.ValidateRound2PayloadReceived(guestRound2);
        guest.ValidateRound2PayloadReceived(hostRound2);

        var hostKey = host.CalculateKeyingMaterial();
        var guestKey = guest.CalculateKeyingMaterial();
        Assert.AreNotEqual(hostKey, guestKey);
        var guestRound3 = guest.CreateRound3PayloadToSend(guestKey);
        Assert.ThrowsException<EcJPakeException>(() => host.ValidateRound3PayloadReceived(guestRound3, hostKey));
    }

    [TestMethod]
    public void ForgedProofs_AndSameParticipantIds_AreRefused()
    {
        var host = new EcJPakeParticipant("edendale-host", "123456");
        var guest = new EcJPakeParticipant("edendale-guest", "123456");
        var guestRound1 = guest.CreateRound1PayloadToSend();
        host.CreateRound1PayloadToSend();

        var forged = guestRound1 with { ProofX1 = new EcSchnorrZkp(guestRound1.ProofX1.V, guestRound1.ProofX1.R.Add(BigInteger.One)) };
        Assert.ThrowsException<EcJPakeException>(() => host.ValidateRound1PayloadReceived(forged));

        var twin = new EcJPakeParticipant("edendale-host", "123456");
        twin.CreateRound1PayloadToSend();
        var sameId = new EcJPakeParticipant("edendale-host", "123456");
        var sameIdRound1 = sameId.CreateRound1PayloadToSend();
        Assert.ThrowsException<EcJPakeException>(() => twin.ValidateRound1PayloadReceived(sameIdRound1));

        Assert.ThrowsException<ArgumentException>(() => new EcJPakeParticipant("x", ""));
    }

    [TestMethod]
    public void TheWireFormat_MatchesTheAndroidHandoff()
    {
        var host = JPakeSession.ForHost("123456");
        var guest = JPakeSession.ForGuest("123456");

        var round1 = guest.Round1();
        using (var document = JsonDocument.Parse(round1))
        {
            var root = document.RootElement;
            Assert.AreEqual(2, root.GetProperty("v").GetInt32());
            Assert.AreEqual("round1", root.GetProperty("type").GetString());
            Assert.AreEqual("edendale-guest", root.GetProperty("id").GetString());
            Assert.AreEqual(33, HandoffCrypto.FromBase64Url(root.GetProperty("gx1").GetString()).Length, "compressed P-256 point");
            Assert.AreEqual(33, HandoffCrypto.FromBase64Url(root.GetProperty("zkp1").GetProperty("v").GetString()).Length);
            Assert.IsTrue(root.GetProperty("zkp2").TryGetProperty("r", out _));
        }
        host.ReceiveRound1(round1);
        guest.ReceiveRound1(host.Round1());
        var round2 = guest.Round2();
        using (var document = JsonDocument.Parse(round2))
        {
            Assert.AreEqual("round2", document.RootElement.GetProperty("type").GetString());
            Assert.IsTrue(document.RootElement.TryGetProperty("a", out _));
            Assert.IsTrue(document.RootElement.GetProperty("zkp").TryGetProperty("v", out _));
        }
        host.ReceiveRound2(round2);
        guest.ReceiveRound2(host.Round2());
        var round3 = guest.Round3();
        using (var document = JsonDocument.Parse(round3))
        {
            Assert.AreEqual("round3", document.RootElement.GetProperty("type").GetString());
            Assert.IsTrue(document.RootElement.TryGetProperty("mac", out _));
        }
        host.ReceiveRound3(round3);
        guest.ReceiveRound3(host.Round3());
        CollectionAssert.AreEqual(host.SessionKey(), guest.SessionKey());
        Assert.AreEqual(32, host.SessionKey().Length);
    }

    [TestMethod]
    public void AWrongCode_IsReportedAsCode_AndAnErrorFrameIsUnderstood()
    {
        var host = JPakeSession.ForHost("123456");
        var guest = JPakeSession.ForGuest("000000");
        host.ReceiveRound1(guest.Round1());
        guest.ReceiveRound1(host.Round1());
        host.ReceiveRound2(guest.Round2());
        guest.ReceiveRound2(host.Round2());
        Assert.AreEqual(HandoffError.Code, Assert.ThrowsException<HandoffException>(() => host.ReceiveRound3(guest.Round3())).Reason);
        Assert.AreEqual(HandoffError.Code, Assert.ThrowsException<HandoffException>(() => guest.ReceiveRound3(HandoffFrames.ErrorFrame(HandoffError.Code))).Reason);
        Assert.ThrowsException<InvalidOperationException>(() => guest.SessionKey());
    }

    [TestMethod]
    public void MalformedRounds_AreRefused()
    {
        var host = JPakeSession.ForHost("123456");
        Assert.AreEqual(HandoffError.Malformed, Assert.ThrowsException<HandoffException>(() => host.ReceiveRound1("not json"u8.ToArray())).Reason);
        var v1 = """{"v":1,"type":"round1","id":"edendale-phone"}"""u8.ToArray();
        Assert.AreEqual(HandoffError.Version, Assert.ThrowsException<HandoffException>(() => host.ReceiveRound1(v1)).Reason);
        var badPoint = """{"v":2,"type":"round1","id":"edendale-guest","gx1":"AAAA","gx2":"AAAA","zkp1":{"v":"AAAA","r":"AA"},"zkp2":{"v":"AAAA","r":"AA"}}"""u8.ToArray();
        Assert.AreEqual(HandoffError.Malformed, Assert.ThrowsException<HandoffException>(() => host.ReceiveRound1(badPoint)).Reason);
        Assert.ThrowsException<ArgumentException>(() => JPakeSession.ForHost("12"));
    }
}
