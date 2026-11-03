// The two ends of one handoff over a connected duplex stream
// (SYNC-PROTOCOL.md §9.3–§9.6). The host pairs by whichever the guest sends
// first, a QR `hello` or a J-PAKE `round1`; the guest pairs by the code it
// scanned or typed. Then both exchange roles, the sender waits for its user
// and sends the offer, the receiver stores it and answers with the result.
// Which side sends is independent of which side showed the code. Transport
// (TCP, DNS-SD) is the caller's; tests run both ends over an in-memory pipe.

using System.Security.Cryptography;

namespace Edendale.Windows.Core.Handoff;

/// <summary>What the host shows: a QR code and a typed code, each valid for 2 minutes and one attempt.</summary>
public sealed class HandoffHostPairing : IDisposable
{
    private readonly object _gate = new();
    private readonly Func<DateTimeOffset> _now;
    private readonly HandoffCrypto.X25519KeyPair _keys;
    private readonly byte[] _secret;
    private bool _qrUsed;
    private bool _codeUsed;

    public HandoffHostPairing(string deviceName, Func<DateTimeOffset>? now = null, bool offerCode = true)
    {
        DeviceName = deviceName;
        _now = now ?? (() => DateTimeOffset.UtcNow);
        Created = _now();
        _keys = HandoffCrypto.X25519KeyPair.Generate();
        _secret = HandoffCrypto.NewSecret();
        Code = offerCode ? HandoffCrypto.NewCode() : null;
    }

    public string DeviceName { get; }
    public DateTimeOffset Created { get; }

    /// <summary>The 6-digit code (§9.4), or null where typed pairing is not offered.</summary>
    public string? Code { get; }

    public bool IsExpired => _now() - Created > HandoffFrames.PairingLifetime;

    /// <summary>The QR code, with the host's address when known so the guest can connect without browsing.</summary>
    public HandoffQrCode QrCode(string? host, int? port) => new(DeviceName, host, port, _keys.PublicKey, _secret);

    /// <summary>§9.3: one `hello` per code. Returns the secret for this attempt, or null when it was used or expired.</summary>
    internal (HandoffCrypto.X25519KeyPair Keys, byte[] Secret)? TakeQr()
    {
        lock (_gate)
        {
            if (_qrUsed || IsExpired) return null;
            _qrUsed = true;
            return (_keys, _secret);
        }
    }

    /// <summary>§9.4: one attempt per code.</summary>
    internal string? TakeCode()
    {
        lock (_gate)
        {
            if (Code is null || _codeUsed || IsExpired) return null;
            _codeUsed = true;
            return Code;
        }
    }

    public void Dispose()
    {
        CryptographicOperations.ZeroMemory(_secret);
        _keys.Dispose();
    }
}

/// <summary>How the guest pairs: a scanned QR code, or a typed code.</summary>
public abstract record HandoffGuestPairing
{
    public sealed record Qr(HandoffQrCode Scanned) : HandoffGuestPairing;
    public sealed record Typed(string Digits) : HandoffGuestPairing;
}

/// <summary>What one side brings to the exchange after pairing.</summary>
public sealed class HandoffParty
{
    public required string DeviceName { get; init; }
    public string Platform { get; init; } = "windows";
    public required HandoffRole Role { get; init; }

    /// <summary>Sender: builds the offer for the named receiver, after the user approves (may take minutes). Return Declined on no.</summary>
    public Func<HandoffRoleMessage, CancellationToken, Task<HandoffOffer>>? ProvideOffer { get; init; }

    /// <summary>Receiver: validates and stores what was offered, and says what it kept.</summary>
    public Func<HandoffOffer, HandoffRoleMessage, CancellationToken, Task<HandoffResult>>? StoreOffer { get; init; }

    /// <summary>Progress for the UI: "paired", "role", "offer", "result".</summary>
    public Action<string>? Progress { get; init; }
}

/// <summary>What a finished handoff produced.</summary>
public sealed record HandoffExchange(HandoffRoleMessage Peer, HandoffOffer Offer, HandoffResult Result, string PairedBy);

public static class HandoffSession
{
    /// <summary>
    /// The host's side: pairs by whatever the guest sends first, then runs
    /// the exchange. Throws a HandoffException with the reason; error frames
    /// are sent before throwing where §9.3 and §9.4 say so.
    /// </summary>
    public static async Task<HandoffExchange> RunHostAsync(Stream stream, HandoffHostPairing pairing, HandoffParty party, CancellationToken cancellation)
    {
        byte[]? key = null;
        string pairedBy;
        try
        {
            var first = await HandoffFrames.ReadAsync(stream, HandoffFrames.HandshakeTimeout, cancellation).ConfigureAwait(false);
            System.Text.Json.Nodes.JsonObject envelope;
            try
            {
                envelope = HandoffFrames.Parse(first, requireVersion: true);
            }
            catch (HandoffException error) when (error.Reason == HandoffError.Version)
            {
                await TrySendAsync(stream, HandoffFrames.ErrorFrame(HandoffError.Version), cancellation).ConfigureAwait(false);
                throw;
            }

            switch (HandoffFrames.Type(envelope))
            {
                case "hello":
                {
                    pairedBy = "qr";
                    var qr = pairing.TakeQr();
                    if (qr is null)
                    {
                        await TrySendAsync(stream, HandoffFrames.ErrorFrame(HandoffError.Secret), cancellation).ConfigureAwait(false);
                        throw new HandoffException(HandoffError.Expired);
                    }
                    var guestPublic = HandoffCrypto.FromBase64Url(HandoffFrames.String(envelope, "pk"));
                    var proof = HandoffCrypto.FromBase64Url(HandoffFrames.String(envelope, "proof"));
                    if (guestPublic.Length != 32) throw new HandoffException(HandoffError.Malformed, "pk");
                    var transcript = HandoffCrypto.Transcript(qr.Value.Keys.PublicKey, guestPublic);
                    var expected = HandoffCrypto.Proof(qr.Value.Secret, transcript);
                    if (!HandoffCrypto.FixedTimeEquals(expected, proof))
                    {
                        await TrySendAsync(stream, HandoffFrames.ErrorFrame(HandoffError.Secret), cancellation).ConfigureAwait(false);
                        throw new HandoffException(HandoffError.Secret);
                    }
                    var shared = qr.Value.Keys.Agree(guestPublic);
                    key = HandoffCrypto.DeriveQrKey(shared, qr.Value.Secret, transcript);
                    CryptographicOperations.ZeroMemory(shared);
                    var confirm = HandoffCrypto.Confirm(key, transcript);
                    await HandoffFrames.WriteAsync(stream, HandoffFrames.Message("confirm", writer => writer.WriteString("mac", HandoffCrypto.Base64Url(confirm))), cancellation).ConfigureAwait(false);
                    break;
                }
                case "round1":
                {
                    pairedBy = "code";
                    var code = pairing.TakeCode();
                    if (code is null)
                    {
                        await TrySendAsync(stream, HandoffFrames.ErrorFrame(HandoffError.Code), cancellation).ConfigureAwait(false);
                        throw new HandoffException(HandoffError.Expired);
                    }
                    var pake = JPakeSession.ForHost(code);
                    pake.ReceiveRound1(first);
                    await HandoffFrames.WriteAsync(stream, pake.Round1(), cancellation).ConfigureAwait(false);
                    pake.ReceiveRound2(await HandoffFrames.ReadAsync(stream, HandoffFrames.HandshakeTimeout, cancellation).ConfigureAwait(false));
                    await HandoffFrames.WriteAsync(stream, pake.Round2(), cancellation).ConfigureAwait(false);
                    var confirmation = await HandoffFrames.ReadAsync(stream, HandoffFrames.HandshakeTimeout, cancellation).ConfigureAwait(false);
                    try
                    {
                        pake.ReceiveRound3(confirmation);
                    }
                    catch (HandoffException error) when (error.Reason == HandoffError.Code)
                    {
                        await TrySendAsync(stream, HandoffFrames.ErrorFrame(HandoffError.Code), cancellation).ConfigureAwait(false);
                        throw;
                    }
                    await HandoffFrames.WriteAsync(stream, pake.Round3(), cancellation).ConfigureAwait(false);
                    key = pake.SessionKey();
                    break;
                }
                default:
                    await TrySendAsync(stream, HandoffFrames.ErrorFrame(HandoffError.Malformed), cancellation).ConfigureAwait(false);
                    throw new HandoffException(HandoffError.Malformed, "first frame");
            }
            party.Progress?.Invoke("paired");

            using var channel = HandoffChannel.ForHost(key);
            return await ExchangeAsync(stream, channel, party, pairedBy, cancellation).ConfigureAwait(false);
        }
        finally
        {
            if (key is not null) CryptographicOperations.ZeroMemory(key);
        }
    }

    /// <summary>The guest's side: pairs with the scanned or typed code, then runs the exchange.</summary>
    public static async Task<HandoffExchange> RunGuestAsync(Stream stream, HandoffGuestPairing pairing, HandoffParty party, CancellationToken cancellation)
    {
        byte[]? key = null;
        string pairedBy;
        try
        {
            switch (pairing)
            {
                case HandoffGuestPairing.Qr qr:
                {
                    pairedBy = "qr";
                    using var keys = HandoffCrypto.X25519KeyPair.Generate();
                    var transcript = HandoffCrypto.Transcript(qr.Scanned.PublicKey, keys.PublicKey);
                    var proof = HandoffCrypto.Proof(qr.Scanned.Secret, transcript);
                    await HandoffFrames.WriteAsync(stream, HandoffFrames.Message("hello", writer =>
                    {
                        writer.WriteString("pk", HandoffCrypto.Base64Url(keys.PublicKey));
                        writer.WriteString("proof", HandoffCrypto.Base64Url(proof));
                    }), cancellation).ConfigureAwait(false);
                    var confirmFrame = await HandoffFrames.ReadAsync(stream, HandoffFrames.HandshakeTimeout, cancellation).ConfigureAwait(false);
                    var confirm = HandoffFrames.Expect(confirmFrame, "confirm", requireVersion: true);
                    var shared = keys.Agree(qr.Scanned.PublicKey);
                    key = HandoffCrypto.DeriveQrKey(shared, qr.Scanned.Secret, transcript);
                    CryptographicOperations.ZeroMemory(shared);
                    var expected = HandoffCrypto.Confirm(key, transcript);
                    if (!HandoffCrypto.FixedTimeEquals(expected, HandoffCrypto.FromBase64Url(HandoffFrames.String(confirm, "mac"))))
                    {
                        throw new HandoffException(HandoffError.Secret);
                    }
                    break;
                }
                case HandoffGuestPairing.Typed typed:
                {
                    pairedBy = "code";
                    var pake = JPakeSession.ForGuest(typed.Digits);
                    await HandoffFrames.WriteAsync(stream, pake.Round1(), cancellation).ConfigureAwait(false);
                    pake.ReceiveRound1(await HandoffFrames.ReadAsync(stream, HandoffFrames.HandshakeTimeout, cancellation).ConfigureAwait(false));
                    await HandoffFrames.WriteAsync(stream, pake.Round2(), cancellation).ConfigureAwait(false);
                    pake.ReceiveRound2(await HandoffFrames.ReadAsync(stream, HandoffFrames.HandshakeTimeout, cancellation).ConfigureAwait(false));
                    await HandoffFrames.WriteAsync(stream, pake.Round3(), cancellation).ConfigureAwait(false);
                    pake.ReceiveRound3(await HandoffFrames.ReadAsync(stream, HandoffFrames.HandshakeTimeout, cancellation).ConfigureAwait(false));
                    key = pake.SessionKey();
                    break;
                }
                default:
                    throw new ArgumentException("pairing", nameof(pairing));
            }
            party.Progress?.Invoke("paired");

            using var channel = HandoffChannel.ForGuest(key);
            return await ExchangeAsync(stream, channel, party, pairedBy, cancellation).ConfigureAwait(false);
        }
        finally
        {
            if (key is not null) CryptographicOperations.ZeroMemory(key);
        }
    }

    // ------------------------------------------------------------------
    // §9.6 after the channel is up
    // ------------------------------------------------------------------

    private static async Task<HandoffExchange> ExchangeAsync(Stream stream, HandoffChannel channel, HandoffParty party, string pairedBy, CancellationToken cancellation)
    {
        // 1. Roles, both ways.
        var mine = new HandoffRoleMessage(party.Role, party.DeviceName, party.Platform);
        await SendAsync(stream, channel, HandoffMessages.EncodeRole(mine), cancellation).ConfigureAwait(false);
        var peer = HandoffMessages.DecodeRole(await ReceiveAsync(stream, channel, HandoffFrames.HandshakeTimeout, cancellation).ConfigureAwait(false));
        if (peer.Role == party.Role)
        {
            await TrySendSealedAsync(stream, channel, HandoffFrames.ErrorFrame(HandoffError.Role), cancellation).ConfigureAwait(false);
            throw new HandoffException(HandoffError.Role);
        }
        party.Progress?.Invoke("role");

        if (party.Role == HandoffRole.Sender)
        {
            // 2–3. The user approves; the offer goes out.
            var provide = party.ProvideOffer ?? throw new InvalidOperationException("A sender needs ProvideOffer");
            var offer = await provide(peer, cancellation).ConfigureAwait(false);
            await SendAsync(stream, channel, HandoffMessages.EncodeOffer(offer), cancellation).ConfigureAwait(false);
            party.Progress?.Invoke("offer");
            if (!offer.Approved) return new HandoffExchange(peer, offer, HandoffResult.Empty, pairedBy);
            // 4. The receiver's result.
            var result = HandoffMessages.DecodeResult(await ReceiveAsync(stream, channel, HandoffFrames.ResultTimeout, cancellation).ConfigureAwait(false));
            party.Progress?.Invoke("result");
            return new HandoffExchange(peer, offer, result, pairedBy);
        }
        else
        {
            var store = party.StoreOffer ?? throw new InvalidOperationException("A receiver needs StoreOffer");
            var offer = HandoffMessages.DecodeOffer(await ReceiveAsync(stream, channel, HandoffFrames.OfferTimeout, cancellation).ConfigureAwait(false));
            party.Progress?.Invoke("offer");
            if (!offer.Approved) throw new HandoffException(HandoffError.Declined);
            var result = await store(offer, peer, cancellation).ConfigureAwait(false);
            await SendAsync(stream, channel, HandoffMessages.EncodeResult(result), cancellation).ConfigureAwait(false);
            party.Progress?.Invoke("result");
            return new HandoffExchange(peer, offer, result, pairedBy);
        }
    }

    private static Task SendAsync(Stream stream, HandoffChannel channel, byte[] plaintext, CancellationToken cancellation) =>
        HandoffFrames.WriteAsync(stream, channel.Seal(plaintext), cancellation);

    private static async Task<byte[]> ReceiveAsync(Stream stream, HandoffChannel channel, TimeSpan timeout, CancellationToken cancellation) =>
        channel.Open(await HandoffFrames.ReadAsync(stream, timeout, cancellation).ConfigureAwait(false));

    private static async Task TrySendAsync(Stream stream, byte[] body, CancellationToken cancellation)
    {
        try
        {
            await HandoffFrames.WriteAsync(stream, body, cancellation).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // The connection is closing either way.
        }
    }

    private static async Task TrySendSealedAsync(Stream stream, HandoffChannel channel, byte[] plaintext, CancellationToken cancellation)
    {
        try
        {
            await SendAsync(stream, channel, plaintext, cancellation).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // The connection is closing either way.
        }
    }
}
