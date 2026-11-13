// Framing and envelopes of the handoff (SYNC-PROTOCOL.md §9.2, §9.9): each
// frame is a 4-byte big-endian length, at most 64 KiB, then UTF-8 JSON with
// `"v": 2` and a `"type"` (plaintext before the channel is up, AES-GCM
// plaintext after). Version 1 of the Android handoff uses the same framing.

using System.Buffers.Binary;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Edendale.Windows.Core.Handoff;

/// <summary>Why a handoff ended early. The first six are the `reason` values of §9.9.</summary>
public enum HandoffError
{
    /// <summary>The other side's `v` is not supported.</summary>
    Version,
    /// <summary>The QR proof did not verify.</summary>
    Secret,
    /// <summary>The typed code's key confirmation failed.</summary>
    Code,
    /// <summary>Both sides chose the same role.</summary>
    Role,
    /// <summary>The host already has a handoff in progress.</summary>
    Busy,
    /// <summary>A frame was not valid for its position in the protocol.</summary>
    Malformed,
    /// <summary>The other side did not answer in time (§9.2 timeouts).</summary>
    Timeout,
    /// <summary>The sender's user declined on their device.</summary>
    Declined,
    /// <summary>The other device could not be reached.</summary>
    ConnectionFailed,
    /// <summary>The QR code or typed code is older than two minutes, or was used already.</summary>
    Expired,
}

public sealed class HandoffException(HandoffError reason, string? message = null) : Exception(message ?? reason.ToString())
{
    public HandoffError Reason { get; } = reason;

    /// <summary>The wire value of §9.9 for an error frame; null for errors that are never sent.</summary>
    public static string? WireReason(HandoffError reason) => reason switch
    {
        HandoffError.Version => "version",
        HandoffError.Secret => "secret",
        HandoffError.Code => "code",
        HandoffError.Role => "role",
        HandoffError.Busy => "busy",
        HandoffError.Malformed => "malformed",
        _ => null,
    };

    public static HandoffError FromWireReason(string? reason) => reason switch
    {
        "version" => HandoffError.Version,
        "secret" => HandoffError.Secret,
        "code" => HandoffError.Code,
        "role" => HandoffError.Role,
        "busy" => HandoffError.Busy,
        _ => HandoffError.Malformed,
    };
}

public static class HandoffFrames
{
    public const int Version = 2;
    public const int MaxFrameBytes = 65_536;

    /// <summary>§9.2: 30 s per handshake read.</summary>
    public static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(30);

    /// <summary>§9.2: 10 minutes for the receiver's wait for the offer (the sender may be signing in).</summary>
    public static readonly TimeSpan OfferTimeout = TimeSpan.FromMinutes(10);

    /// <summary>§9.2: 2 minutes for the sender's wait for the result.</summary>
    public static readonly TimeSpan ResultTimeout = TimeSpan.FromMinutes(2);

    /// <summary>§9.3, §9.4: a QR code or typed code is valid for 2 minutes.</summary>
    public static readonly TimeSpan PairingLifetime = TimeSpan.FromMinutes(2);

    /// <summary>A length-prefixed frame around <paramref name="body"/>.</summary>
    public static byte[] Frame(ReadOnlySpan<byte> body)
    {
        if (body.Length == 0 || body.Length > MaxFrameBytes) throw new HandoffException(HandoffError.Malformed, "frame size");
        var frame = new byte[4 + body.Length];
        BinaryPrimitives.WriteInt32BigEndian(frame, body.Length);
        body.CopyTo(frame.AsSpan(4));
        return frame;
    }

    /// <summary>The body length a header announces; a zero or oversized length closes the connection.</summary>
    public static int BodyLength(ReadOnlySpan<byte> header)
    {
        if (header.Length != 4) throw new HandoffException(HandoffError.Malformed, "frame header");
        var length = BinaryPrimitives.ReadUInt32BigEndian(header);
        if (length == 0 || length > MaxFrameBytes) throw new HandoffException(HandoffError.Malformed, "frame size");
        return (int)length;
    }

    public static async Task WriteAsync(Stream stream, byte[] body, CancellationToken cancellation)
    {
        var frame = Frame(body);
        await stream.WriteAsync(frame, cancellation).ConfigureAwait(false);
        await stream.FlushAsync(cancellation).ConfigureAwait(false);
    }

    /// <summary>
    /// One frame's body within <paramref name="timeout"/>; a closed connection
    /// reads as malformed. The deadline closes the stream when it passes
    /// (<see cref="HandoffDeadline"/>), so a read the stream never ends for a
    /// cancelled token still ends; the session is over after a timeout anyway.
    /// </summary>
    public static async Task<byte[]> ReadAsync(Stream stream, TimeSpan timeout, CancellationToken cancellation)
    {
        try
        {
            return await HandoffDeadline.RunAsync(timeout, stream.Dispose, async token =>
            {
                var header = new byte[4];
                await ReadExactlyAsync(stream, header, token).ConfigureAwait(false);
                var body = new byte[BodyLength(header)];
                await ReadExactlyAsync(stream, body, token).ConfigureAwait(false);
                return body;
            }, cancellation).ConfigureAwait(false);
        }
        catch (EndOfStreamException)
        {
            throw new HandoffException(HandoffError.Malformed, "connection closed");
        }
        catch (IOException error)
        {
            throw new HandoffException(HandoffError.ConnectionFailed, error.GetType().Name);
        }
    }

    private static async Task ReadExactlyAsync(Stream stream, byte[] buffer, CancellationToken cancellation)
    {
        var read = 0;
        while (read < buffer.Length)
        {
            var count = await stream.ReadAsync(buffer.AsMemory(read), cancellation).ConfigureAwait(false);
            if (count == 0) throw new EndOfStreamException();
            read += count;
        }
    }

    // ------------------------------------------------------------------
    // Envelopes
    // ------------------------------------------------------------------

    /// <summary>A `{"v":2,"type":…}` body with the fields <paramref name="write"/> adds.</summary>
    public static byte[] Message(string type, Action<Utf8JsonWriter>? write = null)
    {
        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", Version);
            writer.WriteString("type", type);
            write?.Invoke(writer);
            writer.WriteEndObject();
        }
        return buffer.ToArray();
    }

    public static byte[] ErrorFrame(HandoffError reason) =>
        Message("error", writer => writer.WriteString("reason", HandoffException.WireReason(reason) ?? "malformed"));

    /// <summary>
    /// Parses a frame body. The version is checked when the frame is plaintext
    /// (<paramref name="requireVersion"/>); inside the channel a missing `v`
    /// is tolerated. An `error` frame throws its reason.
    /// </summary>
    public static JsonObject Parse(byte[] body, bool requireVersion)
    {
        JsonNode? node;
        try
        {
            node = JsonNode.Parse(body);
        }
        catch (JsonException)
        {
            throw new HandoffException(HandoffError.Malformed, "not JSON");
        }
        if (node is not JsonObject envelope) throw new HandoffException(HandoffError.Malformed, "not an object");
        var version = envelope["v"] is JsonValue v && v.TryGetValue<int>(out var number) ? number : (int?)null;
        if (requireVersion && version is null) throw new HandoffException(HandoffError.Malformed, "no version");
        if (version is int given && given != Version) throw new HandoffException(HandoffError.Version, $"version {given}");
        if (Type(envelope) == "error")
        {
            throw new HandoffException(HandoffException.FromWireReason(String(envelope, "reason")), "the other device reported an error");
        }
        return envelope;
    }

    public static string? Type(JsonObject envelope) => String(envelope, "type");

    public static string? String(JsonObject envelope, string name) =>
        envelope[name] is JsonValue value && value.TryGetValue<string>(out var text) ? text : null;

    public static string Require(JsonObject envelope, string name) =>
        String(envelope, name) ?? throw new HandoffException(HandoffError.Malformed, $"missing {name}");

    /// <summary>Expects a frame of <paramref name="type"/>; anything else is malformed for this position.</summary>
    public static JsonObject Expect(byte[] body, string type, bool requireVersion)
    {
        var envelope = Parse(body, requireVersion);
        if (Type(envelope) != type) throw new HandoffException(HandoffError.Malformed, $"expected {type}");
        return envelope;
    }

    public static string Utf8(byte[] body) => Encoding.UTF8.GetString(body);
}
