// The handoff's cryptography (SYNC-PROTOCOL.md §9.3–§9.5): the QR pairing's
// X25519 agreement, HMAC proof and confirmation, HKDF key, and the
// AES-256-GCM channel with direction-and-counter nonces. Only platform or
// audited primitives: .NET's HMAC, HKDF, and AES-GCM, Bouncy Castle's X25519.
// The typed-code path (EC J-PAKE) is in EcJPake.cs and JPakePairing.cs.
// Nothing here logs; secrets are zeroed when a session ends.

using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using Org.BouncyCastle.Crypto.Agreement;
using Org.BouncyCastle.Crypto.Parameters;

namespace Edendale.Windows.Core.Handoff;

public static class HandoffCrypto
{
    /// <summary>§9.3 label, §9.4 HKDF info, and §9.5 additional authenticated data.</summary>
    public const string Label = "edendale-handoff-v2";
    public static readonly byte[] LabelBytes = Encoding.ASCII.GetBytes(Label);

    /// <summary>§9.4: the J-PAKE participant IDs.</summary>
    public const string HostParticipant = "edendale-host";
    public const string GuestParticipant = "edendale-guest";

    public const int CodeLength = 6;
    public const int SecretLength = 16;
    public const int KeyLength = 32;

    /// <summary>§9.4: six digits from a secure generator, zero-padded.</summary>
    public static string NewCode() => RandomNumberGenerator.GetInt32(1_000_000).ToString("D6", System.Globalization.CultureInfo.InvariantCulture);

    public static bool IsCode(string? code) => code is { Length: CodeLength } && code.All(char.IsAsciiDigit);

    /// <summary>§9.3: a 16-byte random secret.</summary>
    public static byte[] NewSecret() => RandomNumberGenerator.GetBytes(SecretLength);

    // ------------------------------------------------------------------
    // X25519 (RFC 7748)
    // ------------------------------------------------------------------

    public sealed class X25519KeyPair : IDisposable
    {
        private readonly X25519PrivateKeyParameters _private;

        private X25519KeyPair(X25519PrivateKeyParameters privateKey)
        {
            _private = privateKey;
            PublicKey = privateKey.GeneratePublicKey().GetEncoded();
        }

        public static X25519KeyPair Generate() => new(new X25519PrivateKeyParameters(new Org.BouncyCastle.Security.SecureRandom()));

        /// <summary>For the test vectors: a pair from its 32-byte private scalar.</summary>
        public static X25519KeyPair FromPrivate(byte[] privateKey) => new(new X25519PrivateKeyParameters(privateKey, 0));

        public byte[] PublicKey { get; }

        /// <summary>The 32-byte shared secret with <paramref name="peerPublicKey"/>.</summary>
        public byte[] Agree(byte[] peerPublicKey)
        {
            if (peerPublicKey.Length != 32) throw new HandoffException(HandoffError.Malformed, "public key length");
            var agreement = new X25519Agreement();
            agreement.Init(_private);
            var shared = new byte[agreement.AgreementSize];
            agreement.CalculateAgreement(new X25519PublicKeyParameters(peerPublicKey, 0), shared, 0);
            // RFC 7748 §6.1: an all-zero result means a low-order point.
            if (shared.All(b => b == 0)) throw new HandoffException(HandoffError.Secret, "low-order point");
            return shared;
        }

        public void Dispose()
        {
            // Bouncy Castle keeps the scalar; nothing more to clear from here.
        }
    }

    // ------------------------------------------------------------------
    // §9.3 key schedule
    // ------------------------------------------------------------------

    /// <summary>`label ‖ pkH ‖ pkG`, 83 bytes.</summary>
    public static byte[] Transcript(byte[] hostPublicKey, byte[] guestPublicKey)
    {
        var transcript = new byte[LabelBytes.Length + hostPublicKey.Length + guestPublicKey.Length];
        LabelBytes.CopyTo(transcript, 0);
        hostPublicKey.CopyTo(transcript, LabelBytes.Length);
        guestPublicKey.CopyTo(transcript, LabelBytes.Length + hostPublicKey.Length);
        return transcript;
    }

    /// <summary>`HMAC-SHA256(key = secret, msg = "guest" ‖ transcript)`.</summary>
    public static byte[] Proof(byte[] secret, byte[] transcript) => HmacSha256(secret, Concat("guest"u8, transcript));

    /// <summary>`HKDF-SHA256(ikm = shared, salt = secret, info = transcript, L = 32)`.</summary>
    public static byte[] DeriveQrKey(byte[] shared, byte[] secret, byte[] transcript) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, shared, KeyLength, secret, transcript);

    /// <summary>`HMAC-SHA256(key = key, msg = "host" ‖ transcript)`.</summary>
    public static byte[] Confirm(byte[] key, byte[] transcript) => HmacSha256(key, Concat("host"u8, transcript));

    /// <summary>§9.4: `HKDF-SHA256(ikm = J-PAKE shared key, salt = empty, info = label, L = 32)`.</summary>
    public static byte[] DeriveCodeKey(byte[] keyingMaterial) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, keyingMaterial, KeyLength, salt: null, info: LabelBytes);

    public static byte[] HmacSha256(byte[] key, byte[] message) => HMACSHA256.HashData(key, message);

    public static bool FixedTimeEquals(ReadOnlySpan<byte> a, ReadOnlySpan<byte> b) => CryptographicOperations.FixedTimeEquals(a, b);

    private static byte[] Concat(ReadOnlySpan<byte> prefix, byte[] rest)
    {
        var result = new byte[prefix.Length + rest.Length];
        prefix.CopyTo(result);
        rest.CopyTo(result, prefix.Length);
        return result;
    }

    // ------------------------------------------------------------------
    // base64url (RFC 4648 §5, no padding)
    // ------------------------------------------------------------------

    public static string Base64Url(byte[] data) =>
        Convert.ToBase64String(data).Replace('+', '-').Replace('/', '_').TrimEnd('=');

    public static byte[] FromBase64Url(string? text)
    {
        if (text is null) throw new HandoffException(HandoffError.Malformed, "missing value");
        var padded = text.Replace('-', '+').Replace('_', '/');
        padded += ((4 - padded.Length % 4) % 4) switch { 0 => "", 1 => "=", 2 => "==", _ => "===" };
        try
        {
            return Convert.FromBase64String(padded);
        }
        catch (FormatException)
        {
            throw new HandoffException(HandoffError.Malformed, "not base64url");
        }
    }
}

/// <summary>The QR code of §9.3: `edendale://handoff?v=2&amp;n=&lt;name&gt;&amp;h=&lt;host&gt;&amp;p=&lt;port&gt;&amp;k=&lt;b64u(pkH)&gt;&amp;s=&lt;b64u(secret)&gt;`.</summary>
public sealed record HandoffQrCode(string Name, string? Host, int? Port, byte[] PublicKey, byte[] Secret)
{
    public const string Scheme = "edendale";
    public const string Path = "handoff";

    /// <summary>Never the secret, nor the address: this can reach a log line.</summary>
    public override string ToString() => nameof(HandoffQrCode);

    public string ToUri()
    {
        var query = new List<string> { "v=2", "n=" + Uri.EscapeDataString(Name) };
        if (Host is { Length: > 0 } host) query.Add("h=" + Uri.EscapeDataString(host));
        if (Port is int port) query.Add("p=" + port.ToString(System.Globalization.CultureInfo.InvariantCulture));
        query.Add("k=" + HandoffCrypto.Base64Url(PublicKey));
        query.Add("s=" + HandoffCrypto.Base64Url(Secret));
        return $"{Scheme}://{Path}?{string.Join("&", query)}";
    }

    /// <summary>Parses a scanned code; null when it is not a version-2 handoff code.</summary>
    public static HandoffQrCode? Parse(string text)
    {
        if (!Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri)) return null;
        if (!uri.Scheme.Equals(Scheme, StringComparison.OrdinalIgnoreCase) || !uri.Host.Equals(Path, StringComparison.OrdinalIgnoreCase)) return null;
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var equals = pair.IndexOf('=');
            var name = Uri.UnescapeDataString(equals < 0 ? pair : pair[..equals]);
            var value = equals < 0 ? "" : Uri.UnescapeDataString(pair[(equals + 1)..]);
            values.TryAdd(name, value);
        }
        if (values.GetValueOrDefault("v") != "2") return null;
        if (!values.TryGetValue("k", out var key) || !values.TryGetValue("s", out var secret)) return null;
        try
        {
            var publicKey = HandoffCrypto.FromBase64Url(key);
            var secretBytes = HandoffCrypto.FromBase64Url(secret);
            if (publicKey.Length != 32 || secretBytes.Length != HandoffCrypto.SecretLength) return null;
            int? port = values.TryGetValue("p", out var p) && int.TryParse(p, out var parsed) && parsed is > 0 and <= 65535 ? parsed : null;
            return new HandoffQrCode(values.GetValueOrDefault("n") ?? "", values.GetValueOrDefault("h"), port, publicKey, secretBytes);
        }
        catch (HandoffException)
        {
            return null;
        }
    }
}

/// <summary>
/// §9.5: AES-256-GCM under the agreed key. The 12-byte nonce is a 4-byte
/// big-endian direction tag (1 guest → host, 2 host → guest) and an 8-byte
/// big-endian counter from 0; the AAD is the ASCII label. A frame that fails
/// to open closes the connection.
/// </summary>
public sealed class HandoffChannel : IDisposable
{
    public const uint GuestToHost = 1;
    public const uint HostToGuest = 2;
    private const int TagLength = 16;

    private readonly AesGcm _aes;
    private readonly byte[] _key;
    private readonly uint _send;
    private readonly uint _receive;
    private readonly object _gate = new();
    private ulong _sent;
    private ulong _received;

    public HandoffChannel(byte[] key, uint sendDirection, uint receiveDirection)
    {
        if (key.Length != HandoffCrypto.KeyLength) throw new ArgumentException("AES-256 needs a 32-byte key", nameof(key));
        _key = (byte[])key.Clone();
        _aes = new AesGcm(_key, TagLength);
        _send = sendDirection;
        _receive = receiveDirection;
    }

    public static HandoffChannel ForHost(byte[] key) => new(key, HostToGuest, GuestToHost);
    public static HandoffChannel ForGuest(byte[] key) => new(key, GuestToHost, HostToGuest);

    public static byte[] Nonce(uint direction, ulong counter)
    {
        var nonce = new byte[12];
        BinaryPrimitives.WriteUInt32BigEndian(nonce, direction);
        BinaryPrimitives.WriteUInt64BigEndian(nonce.AsSpan(4), counter);
        return nonce;
    }

    /// <summary>Ciphertext followed by the 16-byte tag (the layout Java's GCM produces, which the Android handoff reads).</summary>
    public byte[] Seal(byte[] plaintext)
    {
        lock (_gate)
        {
            var sealed_ = new byte[plaintext.Length + TagLength];
            _aes.Encrypt(Nonce(_send, _sent), plaintext, sealed_.AsSpan(0, plaintext.Length), sealed_.AsSpan(plaintext.Length, TagLength), HandoffCrypto.LabelBytes);
            _sent++;
            return sealed_;
        }
    }

    public byte[] Open(byte[] frame)
    {
        lock (_gate)
        {
            if (frame.Length < TagLength) throw new HandoffException(HandoffError.Malformed, "short ciphertext");
            var plaintext = new byte[frame.Length - TagLength];
            try
            {
                _aes.Decrypt(Nonce(_receive, _received), frame.AsSpan(0, plaintext.Length), frame.AsSpan(plaintext.Length, TagLength), plaintext, HandoffCrypto.LabelBytes);
            }
            catch (CryptographicException)
            {
                throw new HandoffException(HandoffError.Malformed, "frame failed to open");
            }
            _received++;
            return plaintext;
        }
    }

    public void Dispose()
    {
        _aes.Dispose();
        CryptographicOperations.ZeroMemory(_key);
    }
}
