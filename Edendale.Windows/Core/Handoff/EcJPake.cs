// J-PAKE over an elliptic curve (RFC 8236 §3 and §5, Schnorr NIZK after
// RFC 8235), ported line for line from Bouncy Castle Java's
// org.bouncycastle.crypto.agreement.ecjpake (ECJPAKEParticipant and
// ECJPAKEUtil, bcprov 1.84, MIT), which is what the Android handoff runs.
// Bouncy Castle's C# port (2.7.0) ships only the finite-field J-PAKE, so the
// EC variant lives here on top of its EC arithmetic, hashes, and HMAC. Every
// hash input, encoding, and sign convention follows the Java code so a
// Windows PC and an Android device agree the same key:
//
//   - points travel compressed (SEC 1 §2.3.3);
//   - the ZKP challenge is SHA-256 over 4-byte-length-prefixed G, V, X, and
//     the participant ID, read as a signed big-endian integer;
//   - the keying material is the affine x of (B − G4·x2·s)·x2;
//   - the confirmation key is SHA-256(unsigned(K) ‖ "ECJPAKE_KC") and the
//     tag HMAC-SHA256 over "KC_1_U" ‖ IDs ‖ G1..G4, read as a signed integer.
//
// RFC 8236 publishes no test vectors; EcJPakeTests checks both sides agree,
// a wrong password fails at round 3, and forged proofs are refused.

using System.Text;
using Org.BouncyCastle.Crypto;
using Org.BouncyCastle.Crypto.Digests;
using Org.BouncyCastle.Crypto.EC;
using Org.BouncyCastle.Crypto.Macs;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Math;
using Org.BouncyCastle.Math.EC;
using Org.BouncyCastle.Security;
using Org.BouncyCastle.Utilities;

namespace Edendale.Windows.Core.Handoff;

/// <summary>The curve parameters J-PAKE runs over; P-256 for the handoff.</summary>
public sealed class EcJPakeCurve
{
    public static readonly EcJPakeCurve NistP256 = FromNamed("P-256");

    private EcJPakeCurve(ECCurve curve, ECPoint g)
    {
        Curve = curve;
        G = g;
        N = curve.Order ?? throw new ArgumentException("curve order", nameof(curve));
        H = curve.Cofactor ?? BigInteger.One;
        Q = curve.Field.Characteristic;
    }

    private static EcJPakeCurve FromNamed(string name)
    {
        var x9 = CustomNamedCurves.GetByName(name) ?? throw new InvalidOperationException(name);
        return new EcJPakeCurve(x9.Curve, x9.G);
    }

    public ECCurve Curve { get; }
    public ECPoint G { get; }
    public BigInteger N { get; }
    public BigInteger H { get; }
    public BigInteger Q { get; }
}

/// <summary>A Schnorr proof of knowledge of the scalar behind a point: {V = G·v, r = v − x·h mod n}.</summary>
public sealed record EcSchnorrZkp(ECPoint V, BigInteger R);

public sealed record EcJPakeRound1(string ParticipantId, ECPoint Gx1, ECPoint Gx2, EcSchnorrZkp ProofX1, EcSchnorrZkp ProofX2);
public sealed record EcJPakeRound2(string ParticipantId, ECPoint A, EcSchnorrZkp ProofX2s);
public sealed record EcJPakeRound3(string ParticipantId, BigInteger MacTag);

/// <summary>Thrown when a payload fails validation (a wrong password shows at round 3).</summary>
public sealed class EcJPakeException(string message) : Exception(message);

public static class EcJPakeUtil
{
    private static readonly BigInteger One = BigInteger.One;

    public static BigInteger GenerateX1(BigInteger n, SecureRandom random) =>
        BigIntegers.CreateRandomInRange(One, n.Subtract(One), random);

    /// <summary>`s = unsigned(password) mod n`, which must not be 0.</summary>
    public static BigInteger CalculateS(BigInteger n, byte[] password)
    {
        var s = new BigInteger(1, password).Mod(n);
        if (s.SignValue == 0) throw new EcJPakeException("MUST ensure s is not equal to 0 modulo n");
        return s;
    }

    public static BigInteger CalculateS(BigInteger n, string password) => CalculateS(n, Encoding.UTF8.GetBytes(password));

    public static ECPoint CalculateGx(ECPoint g, BigInteger x) => g.Multiply(x);

    public static ECPoint CalculateGA(ECPoint gx1, ECPoint gx3, ECPoint gx4) => gx1.Add(gx3).Add(gx4);

    public static BigInteger CalculateX2s(BigInteger n, BigInteger x2, BigInteger s) => x2.Multiply(s).Mod(n);

    public static ECPoint CalculateA(ECPoint gA, BigInteger x2s) => gA.Multiply(x2s);

    public static EcSchnorrZkp CalculateZeroKnowledgeProof(ECPoint generator, BigInteger n, BigInteger x, ECPoint X, IDigest digest, string userId, SecureRandom random)
    {
        var v = BigIntegers.CreateRandomInRange(One, n.Subtract(One), random);
        var V = generator.Multiply(v);
        var h = CalculateHashForZeroKnowledgeProof(generator, V, X, userId, digest);
        return new EcSchnorrZkp(V, v.Subtract(x.Multiply(h)).Mod(n));
    }

    private static BigInteger CalculateHashForZeroKnowledgeProof(ECPoint g, ECPoint v, ECPoint x, string participantId, IDigest digest)
    {
        digest.Reset();
        UpdateDigestIncludingSize(digest, g);
        UpdateDigestIncludingSize(digest, v);
        UpdateDigestIncludingSize(digest, x);
        UpdateDigestIncludingSize(digest, participantId);
        var output = new byte[digest.GetDigestSize()];
        digest.DoFinal(output, 0);
        // Java's `new BigInteger(output)`: signed, big-endian.
        return new BigInteger(output);
    }

    private static void UpdateDigestIncludingSize(IDigest digest, ECPoint point)
    {
        var bytes = point.GetEncoded(true);
        digest.BlockUpdate(IntToByteArray(bytes.Length), 0, 4);
        digest.BlockUpdate(bytes, 0, bytes.Length);
    }

    private static void UpdateDigestIncludingSize(IDigest digest, string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        digest.BlockUpdate(IntToByteArray(bytes.Length), 0, 4);
        digest.BlockUpdate(bytes, 0, bytes.Length);
    }

    public static void ValidateZeroKnowledgeProof(ECPoint generator, ECPoint X, EcSchnorrZkp zkp, BigInteger q, BigInteger n, ECCurve curve, BigInteger coFactor, string userId, IDigest digest)
    {
        var V = zkp.V;
        var r = zkp.R;
        var h = CalculateHashForZeroKnowledgeProof(generator, V, X, userId, digest);

        // Public key validation (Antipa et al., §3).
        if (X.IsInfinity) throw new EcJPakeException("Zero-knowledge proof validation failed: X cannot equal infinity");
        var normalized = X.Normalize();
        var xCoord = normalized.AffineXCoord.ToBigInteger();
        var yCoord = normalized.AffineYCoord.ToBigInteger();
        if (xCoord.SignValue < 0 || xCoord.CompareTo(q) >= 0 || yCoord.SignValue < 0 || yCoord.CompareTo(q) >= 0)
        {
            throw new EcJPakeException("Zero-knowledge proof validation failed: x and y are not in the field");
        }
        try
        {
            curve.DecodePoint(X.GetEncoded(true));
        }
        catch (Exception error)
        {
            throw new EcJPakeException("Zero-knowledge proof validation failed: x does not lie on the curve: " + error.GetType().Name);
        }
        if (X.Multiply(coFactor).IsInfinity) throw new EcJPakeException("Zero-knowledge proof validation failed: Nx cannot be infinity");
        if (!V.Equals(generator.Multiply(r).Add(X.Multiply(h.Mod(n)))))
        {
            throw new EcJPakeException("Zero-knowledge proof validation failed: V must be a point on the curve");
        }
    }

    public static void ValidateParticipantIdsDiffer(string participantId1, string participantId2)
    {
        if (participantId1 == participantId2)
        {
            throw new EcJPakeException($"Both participants are using the same participantId ({participantId1}). This is not allowed. Each participant must use a unique participantId.");
        }
    }

    public static void ValidateParticipantIdsEqual(string expected, string actual)
    {
        if (expected != actual) throw new EcJPakeException($"Received payload from incorrect partner ({actual}). Expected to receive payload from {expected}.");
    }

    /// <summary>The affine x of (B − G4·(x2·s mod n))·x2.</summary>
    public static BigInteger CalculateKeyingMaterial(BigInteger n, ECPoint gx4, BigInteger x2, BigInteger s, ECPoint B)
    {
        var k = B.Subtract(gx4.Multiply(x2.Multiply(s).Mod(n))).Multiply(x2).Normalize();
        return k.AffineXCoord.ToBigInteger();
    }

    public static BigInteger CalculateMacTag(string participantId, string partnerParticipantId, ECPoint gx1, ECPoint gx2, ECPoint gx3, ECPoint gx4, BigInteger keyingMaterial, IDigest digest)
    {
        var macKey = CalculateMacKey(keyingMaterial, digest);
        var mac = new HMac(digest);
        var output = new byte[mac.GetMacSize()];
        mac.Init(new KeyParameter(macKey));
        UpdateMac(mac, "KC_1_U");
        UpdateMac(mac, participantId);
        UpdateMac(mac, partnerParticipantId);
        UpdateMac(mac, gx1);
        UpdateMac(mac, gx2);
        UpdateMac(mac, gx3);
        UpdateMac(mac, gx4);
        mac.DoFinal(output, 0);
        Array.Clear(macKey);
        return new BigInteger(output);
    }

    private static byte[] CalculateMacKey(BigInteger keyingMaterial, IDigest digest)
    {
        digest.Reset();
        var material = BigIntegers.AsUnsignedByteArray(keyingMaterial);
        digest.BlockUpdate(material, 0, material.Length);
        var label = Encoding.UTF8.GetBytes("ECJPAKE_KC");
        digest.BlockUpdate(label, 0, label.Length);
        var output = new byte[digest.GetDigestSize()];
        digest.DoFinal(output, 0);
        return output;
    }

    public static void ValidateMacTag(string participantId, string partnerParticipantId, ECPoint gx1, ECPoint gx2, ECPoint gx3, ECPoint gx4, BigInteger keyingMaterial, IDigest digest, BigInteger partnerMacTag)
    {
        var expected = CalculateMacTag(partnerParticipantId, participantId, gx3, gx4, gx1, gx2, keyingMaterial, digest);
        if (!Arrays.FixedTimeEquals(expected.ToByteArray(), partnerMacTag.ToByteArray()))
        {
            throw new EcJPakeException("Partner MacTag validation failed. Therefore, the password, MAC, or digest algorithm of each participant does not match.");
        }
    }

    private static void UpdateMac(IMac mac, ECPoint point)
    {
        var bytes = point.GetEncoded(true);
        mac.BlockUpdate(bytes, 0, bytes.Length);
    }

    private static void UpdateMac(IMac mac, string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        mac.BlockUpdate(bytes, 0, bytes.Length);
    }

    private static byte[] IntToByteArray(int value) =>
        [(byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte)value];
}

/// <summary>One side of the three-round exchange; states as Bouncy Castle numbers them.</summary>
public sealed class EcJPakeParticipant
{
    public const int StateInitialized = 0;
    public const int StateRound1Created = 10;
    public const int StateRound1Validated = 20;
    public const int StateRound2Created = 30;
    public const int StateRound2Validated = 40;
    public const int StateKeyCalculated = 50;
    public const int StateRound3Created = 60;
    public const int StateRound3Validated = 70;

    private readonly string _participantId;
    private byte[]? _password;
    private readonly IDigest _digest;
    private readonly SecureRandom _random;
    private readonly ECCurve _curve;
    private readonly BigInteger _q;
    private readonly BigInteger _h;
    private readonly BigInteger _n;
    private readonly ECPoint _g;

    private string? _partnerParticipantId;
    private BigInteger? _x1;
    private BigInteger? _x2;
    private ECPoint? _gx1;
    private ECPoint? _gx2;
    private ECPoint? _gx3;
    private ECPoint? _gx4;
    private ECPoint? _b;

    public EcJPakeParticipant(string participantId, string password, EcJPakeCurve? curve = null, IDigest? digest = null, SecureRandom? random = null)
    {
        if (string.IsNullOrEmpty(participantId)) throw new ArgumentException("participantId", nameof(participantId));
        if (string.IsNullOrEmpty(password)) throw new ArgumentException("Password must not be empty.", nameof(password));
        curve ??= EcJPakeCurve.NistP256;
        _participantId = participantId;
        _password = Encoding.UTF8.GetBytes(password);
        _curve = curve.Curve;
        _g = curve.G;
        _h = curve.H;
        _n = curve.N;
        _q = curve.Q;
        _digest = digest ?? new Sha256Digest();
        _random = random ?? new SecureRandom();
        State = StateInitialized;
    }

    public int State { get; private set; }

    public EcJPakeRound1 CreateRound1PayloadToSend()
    {
        if (State >= StateRound1Created) throw new InvalidOperationException("Round1 payload already created for " + _participantId);
        _x1 = EcJPakeUtil.GenerateX1(_n, _random);
        _x2 = EcJPakeUtil.GenerateX1(_n, _random);
        _gx1 = EcJPakeUtil.CalculateGx(_g, _x1);
        _gx2 = EcJPakeUtil.CalculateGx(_g, _x2);
        var proofX1 = EcJPakeUtil.CalculateZeroKnowledgeProof(_g, _n, _x1, _gx1, _digest, _participantId, _random);
        var proofX2 = EcJPakeUtil.CalculateZeroKnowledgeProof(_g, _n, _x2, _gx2, _digest, _participantId, _random);
        State = StateRound1Created;
        return new EcJPakeRound1(_participantId, _gx1, _gx2, proofX1, proofX2);
    }

    public void ValidateRound1PayloadReceived(EcJPakeRound1 received)
    {
        if (State >= StateRound1Validated) throw new InvalidOperationException("Validation already attempted for round1 payload for " + _participantId);
        _partnerParticipantId = received.ParticipantId;
        _gx3 = received.Gx1;
        _gx4 = received.Gx2;
        EcJPakeUtil.ValidateParticipantIdsDiffer(_participantId, received.ParticipantId);
        EcJPakeUtil.ValidateZeroKnowledgeProof(_g, _gx3, received.ProofX1, _q, _n, _curve, _h, received.ParticipantId, _digest);
        EcJPakeUtil.ValidateZeroKnowledgeProof(_g, _gx4, received.ProofX2, _q, _n, _curve, _h, received.ParticipantId, _digest);
        State = StateRound1Validated;
    }

    public EcJPakeRound2 CreateRound2PayloadToSend()
    {
        if (State >= StateRound2Created) throw new InvalidOperationException("Round2 payload already created for " + _participantId);
        if (State < StateRound1Validated) throw new InvalidOperationException("Round1 payload must be validated prior to creating Round2 payload for " + _participantId);
        var gA = EcJPakeUtil.CalculateGA(_gx1!, _gx3!, _gx4!);
        var s = CalculateS();
        var x2s = EcJPakeUtil.CalculateX2s(_n, _x2!, s);
        var A = EcJPakeUtil.CalculateA(gA, x2s);
        var proof = EcJPakeUtil.CalculateZeroKnowledgeProof(gA, _n, x2s, A, _digest, _participantId, _random);
        State = StateRound2Created;
        return new EcJPakeRound2(_participantId, A, proof);
    }

    public void ValidateRound2PayloadReceived(EcJPakeRound2 received)
    {
        if (State >= StateRound2Validated) throw new InvalidOperationException("Validation already attempted for round2 payload for " + _participantId);
        if (State < StateRound1Validated) throw new InvalidOperationException("Round1 payload must be validated prior to validating Round2 payload for " + _participantId);
        var gB = EcJPakeUtil.CalculateGA(_gx3!, _gx1!, _gx2!);
        _b = received.A;
        EcJPakeUtil.ValidateParticipantIdsDiffer(_participantId, received.ParticipantId);
        EcJPakeUtil.ValidateParticipantIdsEqual(_partnerParticipantId!, received.ParticipantId);
        EcJPakeUtil.ValidateZeroKnowledgeProof(gB, _b, received.ProofX2s, _q, _n, _curve, _h, received.ParticipantId, _digest);
        State = StateRound2Validated;
    }

    public BigInteger CalculateKeyingMaterial()
    {
        if (State >= StateKeyCalculated) throw new InvalidOperationException("Key already calculated for " + _participantId);
        if (State < StateRound2Validated) throw new InvalidOperationException("Round2 payload must be validated prior to creating key for " + _participantId);
        var s = CalculateS();
        Array.Clear(_password!);
        _password = null;
        var keyingMaterial = EcJPakeUtil.CalculateKeyingMaterial(_n, _gx4!, _x2!, s, _b!);
        _x1 = null;
        _x2 = null;
        _b = null;
        State = StateKeyCalculated;
        return keyingMaterial;
    }

    public EcJPakeRound3 CreateRound3PayloadToSend(BigInteger keyingMaterial)
    {
        if (State >= StateRound3Created) throw new InvalidOperationException("Round3 payload already created for " + _participantId);
        if (State < StateKeyCalculated) throw new InvalidOperationException("Keying material must be calculated prior to creating Round3 payload for " + _participantId);
        var macTag = EcJPakeUtil.CalculateMacTag(_participantId, _partnerParticipantId!, _gx1!, _gx2!, _gx3!, _gx4!, keyingMaterial, _digest);
        State = StateRound3Created;
        return new EcJPakeRound3(_participantId, macTag);
    }

    public void ValidateRound3PayloadReceived(EcJPakeRound3 received, BigInteger keyingMaterial)
    {
        if (State >= StateRound3Validated) throw new InvalidOperationException("Validation already attempted for round3 payload for " + _participantId);
        if (State < StateKeyCalculated) throw new InvalidOperationException("Keying material must be calculated validated prior to validating Round3 payload for " + _participantId);
        EcJPakeUtil.ValidateParticipantIdsDiffer(_participantId, received.ParticipantId);
        EcJPakeUtil.ValidateParticipantIdsEqual(_partnerParticipantId!, received.ParticipantId);
        EcJPakeUtil.ValidateMacTag(_participantId, _partnerParticipantId!, _gx1!, _gx2!, _gx3!, _gx4!, keyingMaterial, _digest, received.MacTag);
        _gx1 = null;
        _gx2 = null;
        _gx3 = null;
        _gx4 = null;
        State = StateRound3Validated;
    }

    private BigInteger CalculateS() => EcJPakeUtil.CalculateS(_n, _password ?? throw new InvalidOperationException("password already cleared"));
}
