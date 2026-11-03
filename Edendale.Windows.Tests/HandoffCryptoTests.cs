using System.Security.Cryptography;
using System.Text;
using Edendale.Windows.Core.Handoff;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// SYNC-PROTOCOL.md Appendix A.2–A.4 (main 915b40d): the QR pairing key
/// schedule on RFC 7748's X25519 vectors, the QR code URI, the channel
/// nonces and AAD, and the external vectors every client passes first
/// (X25519, HKDF-SHA256 RFC 5869 A.1, HMAC-SHA256 RFC 4231 1–4).
/// </summary>
[TestClass]
public sealed class HandoffCryptoTests
{
    private static byte[] Hex(string hex) => Convert.FromHexString(hex.Replace(" ", ""));

    // RFC 7748 §6.1: Alice is the host, Bob the guest.
    private static readonly byte[] AlicePrivate = Hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    private static readonly byte[] AlicePublic = Hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");
    private static readonly byte[] BobPrivate = Hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
    private static readonly byte[] BobPublic = Hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");
    private static readonly byte[] Shared = Hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
    private static readonly byte[] Secret = Hex("000102030405060708090a0b0c0d0e0f");

    [TestMethod]
    public void A4_X25519_Rfc7748()
    {
        using var alice = HandoffCrypto.X25519KeyPair.FromPrivate(AlicePrivate);
        using var bob = HandoffCrypto.X25519KeyPair.FromPrivate(BobPrivate);
        CollectionAssert.AreEqual(AlicePublic, alice.PublicKey);
        CollectionAssert.AreEqual(BobPublic, bob.PublicKey);
        CollectionAssert.AreEqual(Shared, alice.Agree(BobPublic));
        CollectionAssert.AreEqual(Shared, bob.Agree(AlicePublic));
    }

    [TestMethod]
    public void A4_HkdfSha256_Rfc5869_TestCase1()
    {
        var ikm = Hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        var salt = Hex("000102030405060708090a0b0c");
        var info = Hex("f0f1f2f3f4f5f6f7f8f9");
        var okm = HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm, 42, salt, info);
        CollectionAssert.AreEqual(Hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm);
    }

    [TestMethod]
    public void A4_HmacSha256_Rfc4231_TestCases1To4()
    {
        CollectionAssert.AreEqual(
            Hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            HandoffCrypto.HmacSha256(Hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), Encoding.ASCII.GetBytes("Hi There")));
        CollectionAssert.AreEqual(
            Hex("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            HandoffCrypto.HmacSha256(Encoding.ASCII.GetBytes("Jefe"), Encoding.ASCII.GetBytes("what do ya want for nothing?")));
        CollectionAssert.AreEqual(
            Hex("773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
            HandoffCrypto.HmacSha256(Hex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), Enumerable.Repeat((byte)0xdd, 50).ToArray()));
        CollectionAssert.AreEqual(
            Hex("82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"),
            HandoffCrypto.HmacSha256(Hex("0102030405060708090a0b0c0d0e0f10111213141516171819"), Enumerable.Repeat((byte)0xcd, 50).ToArray()));
    }

    [TestMethod]
    public void A2_KeySchedule()
    {
        var transcript = HandoffCrypto.Transcript(AlicePublic, BobPublic);
        Assert.AreEqual(83, transcript.Length);
        CollectionAssert.AreEqual(Encoding.ASCII.GetBytes("edendale-handoff-v2"), transcript[..19]);

        CollectionAssert.AreEqual(Hex("d0a69fb97e78208a179410b52ab43f2ad6805e7c5eb2354d112f40ac678db12a"), HandoffCrypto.Proof(Secret, transcript));
        var key = HandoffCrypto.DeriveQrKey(Shared, Secret, transcript);
        CollectionAssert.AreEqual(Hex("2e53bf75ce5fd3de9d5ce33c5b245c12a7bb7da14d0857d12bca26fbed33fa01"), key);
        CollectionAssert.AreEqual(Hex("0c68fa3fa1adfdef7c07066855502ea9ace5610b06ece6a4494a7824d1cca7e1"), HandoffCrypto.Confirm(key, transcript));
    }

    [TestMethod]
    public void A2_WrongSecretGivesTheOtherProof()
    {
        var wrong = Hex("ff0102030405060708090a0b0c0d0e0f");
        var transcript = HandoffCrypto.Transcript(AlicePublic, BobPublic);
        CollectionAssert.AreEqual(Hex("c53f739e04e85fcbe125d5a78c6692b04957d131b6e5f0f01fc32cb8b2ca297e"), HandoffCrypto.Proof(wrong, transcript));
        Assert.IsFalse(HandoffCrypto.FixedTimeEquals(HandoffCrypto.Proof(wrong, transcript), HandoffCrypto.Proof(Secret, transcript)));
    }

    [TestMethod]
    public void A2_QrCodeUri()
    {
        var code = new HandoffQrCode("Living Room TV", null, null, AlicePublic, Secret);
        Assert.AreEqual(
            "edendale://handoff?v=2&n=Living%20Room%20TV&k=hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo&s=AAECAwQFBgcICQoLDA0ODw",
            code.ToUri());
        var parsed = HandoffQrCode.Parse(code.ToUri());
        Assert.IsNotNull(parsed);
        Assert.AreEqual("Living Room TV", parsed!.Name);
        CollectionAssert.AreEqual(AlicePublic, parsed.PublicKey);
        CollectionAssert.AreEqual(Secret, parsed.Secret);
        Assert.IsNull(parsed.Host);

        var withAddress = new HandoffQrCode("PC", "192.168.1.10", 54321, AlicePublic, Secret);
        var back = HandoffQrCode.Parse(withAddress.ToUri())!;
        Assert.AreEqual("192.168.1.10", back.Host);
        Assert.AreEqual(54321, back.Port);

        Assert.AreEqual("3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08", HandoffCrypto.Base64Url(BobPublic), "the guest hello's pk");
        Assert.IsNull(HandoffQrCode.Parse("edendale://handoff?v=1&k=x&s=y"), "version 1 is not a QR code");
        Assert.IsNull(HandoffQrCode.Parse("https://example.com/?v=2"));
        Assert.IsNull(HandoffQrCode.Parse(code.ToUri().Replace("&s=AAECAwQFBgcICQoLDA0ODw", "&s=AAEC")), "a short secret is refused");
    }

    [TestMethod]
    public void A3_Nonces_AndAad()
    {
        CollectionAssert.AreEqual(Hex("000000010000000000000000"), HandoffChannel.Nonce(HandoffChannel.GuestToHost, 0));
        CollectionAssert.AreEqual(Hex("000000010000000000000001"), HandoffChannel.Nonce(HandoffChannel.GuestToHost, 1));
        CollectionAssert.AreEqual(Hex("000000020000000000000002"), HandoffChannel.Nonce(HandoffChannel.HostToGuest, 2));
        CollectionAssert.AreEqual(Hex("65 64 65 6e 64 61 6c 65 2d 68 61 6e 64 6f 66 66 2d 76 32"), HandoffCrypto.LabelBytes);
    }

    [TestMethod]
    public void Channel_RoundTrips_RefusesTampering_AndReplay()
    {
        var key = RandomNumberGenerator.GetBytes(32);
        using var host = HandoffChannel.ForHost(key);
        using var guest = HandoffChannel.ForGuest(key);

        var first = guest.Seal("one"u8.ToArray());
        var second = guest.Seal("two"u8.ToArray());
        Assert.AreEqual("one", Encoding.UTF8.GetString(host.Open(first)));
        Assert.AreEqual("two", Encoding.UTF8.GetString(host.Open(second)));
        Assert.AreEqual(3 + 16, first.Length, "ciphertext then a 16-byte tag");

        var tampered = (byte[])guest.Seal("three"u8.ToArray()).Clone();
        tampered[0] ^= 1;
        Assert.ThrowsException<HandoffException>(() => host.Open(tampered));

        // A frame replayed, or sent back in its own direction, fails to open.
        using var host2 = HandoffChannel.ForHost(key);
        host2.Open(first);
        Assert.ThrowsException<HandoffException>(() => host2.Open(first));
        using var guest2 = HandoffChannel.ForGuest(key);
        Assert.ThrowsException<HandoffException>(() => guest2.Open(first));
    }

    [TestMethod]
    public void Frames_AreLengthPrefixed_AndCapped()
    {
        var frame = HandoffFrames.Frame("abc"u8);
        CollectionAssert.AreEqual(new byte[] { 0, 0, 0, 3, (byte)'a', (byte)'b', (byte)'c' }, frame);
        Assert.AreEqual(65_536, HandoffFrames.BodyLength(new byte[] { 0, 1, 0, 0 }));
        Assert.AreEqual(HandoffError.Malformed, Assert.ThrowsException<HandoffException>(() => HandoffFrames.BodyLength(new byte[] { 0, 1, 0, 1 })).Reason);
        Assert.AreEqual(HandoffError.Malformed, Assert.ThrowsException<HandoffException>(() => HandoffFrames.BodyLength(new byte[] { 0, 0, 0, 0 })).Reason);
        Assert.ThrowsException<HandoffException>(() => HandoffFrames.Frame(new byte[65_537]));
    }

    [TestMethod]
    public void Envelopes_CheckTheVersion_AndSurfaceErrors()
    {
        var hello = HandoffFrames.Message("hello", writer => writer.WriteString("pk", "x"));
        Assert.AreEqual("hello", HandoffFrames.Type(HandoffFrames.Parse(hello, requireVersion: true)));
        Assert.IsTrue(HandoffFrames.Utf8(hello).Contains("\"v\":2"));

        var v1 = Encoding.UTF8.GetBytes("""{"v":1,"type":"round1"}""");
        Assert.AreEqual(HandoffError.Version, Assert.ThrowsException<HandoffException>(() => HandoffFrames.Parse(v1, requireVersion: true)).Reason);
        var noVersion = Encoding.UTF8.GetBytes("""{"type":"role","role":"sender","name":"PC"}""");
        Assert.AreEqual(HandoffError.Malformed, Assert.ThrowsException<HandoffException>(() => HandoffFrames.Parse(noVersion, requireVersion: true)).Reason);
        Assert.AreEqual("role", HandoffFrames.Type(HandoffFrames.Parse(noVersion, requireVersion: false)), "inside the channel a missing v is tolerated");

        var error = HandoffFrames.ErrorFrame(HandoffError.Secret);
        Assert.IsTrue(HandoffFrames.Utf8(error).Contains("\"reason\":\"secret\""));
        Assert.AreEqual(HandoffError.Secret, Assert.ThrowsException<HandoffException>(() => HandoffFrames.Parse(error, requireVersion: true)).Reason);
    }

    [TestMethod]
    public void Codes_AreSixDigits()
    {
        for (var i = 0; i < 50; i++) Assert.IsTrue(HandoffCrypto.IsCode(HandoffCrypto.NewCode()));
        Assert.IsFalse(HandoffCrypto.IsCode("12345"));
        Assert.IsFalse(HandoffCrypto.IsCode("12345a"));
        Assert.AreEqual(16, HandoffCrypto.NewSecret().Length);
    }
}
