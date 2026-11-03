// The typed-code pairing of SYNC-PROTOCOL.md §9.4 on the wire: the three
// J-PAKE rounds as JSON frames in the shape the Android handoff sends
// (HandoffCrypto.kt), with `"v": 2` and the participant IDs edendale-host
// and edendale-guest. Points are base64url of the compressed encoding; the
// proof's r and the round-3 MAC tag are base64url of the signed big-endian
// bytes Java's BigInteger.toByteArray produces. The channel key is
// HKDF-SHA256(keying material bytes, no salt, info "edendale-handoff-v2").

using System.Text.Json;
using System.Text.Json.Nodes;
using Org.BouncyCastle.Math;
using Org.BouncyCastle.Math.EC;

namespace Edendale.Windows.Core.Handoff;

public sealed class JPakeSession
{
    private readonly EcJPakeParticipant _participant;
    private readonly EcJPakeCurve _curve;
    private BigInteger? _keyingMaterial;
    private byte[]? _round1;
    private byte[]? _round2;
    private byte[]? _round3;

    public JPakeSession(string participantId, string code, EcJPakeCurve? curve = null)
    {
        if (!HandoffCrypto.IsCode(code)) throw new ArgumentException("A code is six digits", nameof(code));
        _curve = curve ?? EcJPakeCurve.NistP256;
        _participant = new EcJPakeParticipant(participantId, code, _curve);
    }

    public static JPakeSession ForHost(string code) => new(HandoffCrypto.HostParticipant, code);
    public static JPakeSession ForGuest(string code) => new(HandoffCrypto.GuestParticipant, code);

    // Bouncy Castle creates each round's payload before it validates the other
    // side's, whichever order the frames arrive in, so each is made once.

    public byte[] Round1()
    {
        if (_round1 is not null) return _round1;
        var payload = _participant.CreateRound1PayloadToSend();
        return _round1 = HandoffFrames.Message("round1", writer =>
        {
            writer.WriteString("id", payload.ParticipantId);
            writer.WriteString("gx1", Point(payload.Gx1));
            writer.WriteString("gx2", Point(payload.Gx2));
            Zkp(writer, "zkp1", payload.ProofX1);
            Zkp(writer, "zkp2", payload.ProofX2);
        });
    }

    public void ReceiveRound1(byte[] body)
    {
        Round1();
        var fields = HandoffFrames.Expect(body, "round1", requireVersion: true);
        var payload = new EcJPakeRound1(
            HandoffFrames.Require(fields, "id"),
            Point(fields, "gx1"),
            Point(fields, "gx2"),
            Zkp(fields, "zkp1"),
            Zkp(fields, "zkp2"));
        Validate(() => _participant.ValidateRound1PayloadReceived(payload));
    }

    public byte[] Round2()
    {
        if (_round2 is not null) return _round2;
        var payload = _participant.CreateRound2PayloadToSend();
        return _round2 = HandoffFrames.Message("round2", writer =>
        {
            writer.WriteString("id", payload.ParticipantId);
            writer.WriteString("a", Point(payload.A));
            Zkp(writer, "zkp", payload.ProofX2s);
        });
    }

    public void ReceiveRound2(byte[] body)
    {
        Round2();
        var fields = HandoffFrames.Expect(body, "round2", requireVersion: true);
        var payload = new EcJPakeRound2(HandoffFrames.Require(fields, "id"), Point(fields, "a"), Zkp(fields, "zkp"));
        Validate(() => _participant.ValidateRound2PayloadReceived(payload));
    }

    /// <summary>Derives the key, then the confirmation tag for the other side to check.</summary>
    public byte[] Round3()
    {
        if (_round3 is not null) return _round3;
        _keyingMaterial ??= _participant.CalculateKeyingMaterial();
        var payload = _participant.CreateRound3PayloadToSend(_keyingMaterial);
        return _round3 = HandoffFrames.Message("round3", writer =>
        {
            writer.WriteString("id", payload.ParticipantId);
            writer.WriteString("mac", HandoffCrypto.Base64Url(payload.MacTag.ToByteArray()));
        });
    }

    /// <summary>Checks the other side's confirmation: a wrong code fails here, and only here.</summary>
    public void ReceiveRound3(byte[] body)
    {
        Round3();
        var fields = HandoffFrames.Expect(body, "round3", requireVersion: true); // an error frame throws its reason
        var payload = new EcJPakeRound3(HandoffFrames.Require(fields, "id"), new BigInteger(HandoffCrypto.FromBase64Url(HandoffFrames.String(fields, "mac"))));
        try
        {
            _participant.ValidateRound3PayloadReceived(payload, _keyingMaterial!);
        }
        catch (EcJPakeException)
        {
            throw new HandoffException(HandoffError.Code);
        }
        catch (InvalidOperationException)
        {
            throw new HandoffException(HandoffError.Malformed, "round3 out of order");
        }
    }

    /// <summary>The 32-byte channel key, once both confirmations passed.</summary>
    public byte[] SessionKey()
    {
        if (_participant.State != EcJPakeParticipant.StateRound3Validated) throw new InvalidOperationException("Key confirmation hasn't completed");
        return HandoffCrypto.DeriveCodeKey(_keyingMaterial!.ToByteArray());
    }

    private static void Validate(Action validate)
    {
        try
        {
            validate();
        }
        catch (EcJPakeException)
        {
            throw new HandoffException(HandoffError.Malformed, "proof failed");
        }
        catch (InvalidOperationException)
        {
            throw new HandoffException(HandoffError.Malformed, "round out of order");
        }
    }

    private static string Point(ECPoint point) => HandoffCrypto.Base64Url(point.GetEncoded(true));

    private ECPoint Point(JsonObject fields, string name)
    {
        try
        {
            return _curve.Curve.DecodePoint(HandoffCrypto.FromBase64Url(HandoffFrames.String(fields, name)));
        }
        catch (Exception error) when (error is not HandoffException)
        {
            throw new HandoffException(HandoffError.Malformed, $"bad point {name}");
        }
    }

    private static void Zkp(Utf8JsonWriter writer, string name, EcSchnorrZkp proof)
    {
        writer.WriteStartObject(name);
        writer.WriteString("v", Point(proof.V));
        writer.WriteString("r", HandoffCrypto.Base64Url(proof.R.ToByteArray()));
        writer.WriteEndObject();
    }

    private EcSchnorrZkp Zkp(JsonObject fields, string name)
    {
        if (fields[name] is not JsonObject proof) throw new HandoffException(HandoffError.Malformed, $"missing {name}");
        return new EcSchnorrZkp(Point(proof, "v"), new BigInteger(HandoffCrypto.FromBase64Url(HandoffFrames.String(proof, "r"))));
    }
}
