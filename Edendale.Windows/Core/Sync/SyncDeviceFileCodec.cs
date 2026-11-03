// Reading and writing device files (SYNC-PROTOCOL.md §1.2, §5, §6). Writing
// produces exactly the fields the format defines, UTF-8 without a byte-order
// mark. Reading is strict: a file that breaks any rule is skipped whole with
// a reason the UI can name, and skipping never touches local data.

using System.Globalization;
using System.Text;
using System.Text.Json;

namespace Edendale.Windows.Core.Sync;

/// <summary>Why a device file was skipped (§5 "Validation").</summary>
public enum SyncSkipReason
{
    /// <summary>Not valid JSON, or not an object.</summary>
    Malformed,
    /// <summary>Over 8 MiB or more than 100,000 records.</summary>
    TooLarge,
    /// <summary>`format` is higher than this build reads: "update Edendale to sync with &lt;device&gt;".</summary>
    NewerFormat,
    /// <summary>`space` differs from the folder's space name.</summary>
    WrongSpace,
    /// <summary>`device.id` differs from the file name.</summary>
    WrongDeviceId,
    /// <summary>A field or record breaks §5 or §6.</summary>
    InvalidField,
}

/// <summary>The outcome of decoding one file: the file, or why it was skipped.</summary>
public sealed record SyncDecodeResult(SyncDeviceFile? File, SyncSkipReason? Skipped, string? DeviceName)
{
    public bool IsValid => File is not null;

    public static SyncDecodeResult Ok(SyncDeviceFile file) => new(file, null, file.Device.Name);
    public static SyncDecodeResult Skip(SyncSkipReason reason, string? deviceName = null) => new(null, reason, deviceName);
}

public static class SyncDeviceFileCodec
{
    private static readonly UTF8Encoding Utf8NoBom = new(encoderShouldEmitUTF8Identifier: false);

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /// <summary>The file's bytes: compact JSON, camelCase, only the fields of §5 and §6.</summary>
    public static byte[] Encode(SyncDeviceFile file)
    {
        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer, new JsonWriterOptions { Indented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }))
        {
            writer.WriteStartObject();
            writer.WriteNumber("format", file.Format);
            writer.WriteString("space", file.Space);
            writer.WriteStartObject("device");
            writer.WriteString("id", file.Device.Id);
            writer.WriteString("name", file.Device.Name);
            writer.WriteString("platform", file.Device.Platform);
            writer.WriteString("app", file.Device.App);
            writer.WriteEndObject();
            writer.WriteNumber("updateTime", file.UpdateTime);

            writer.WriteStartArray("watchProgress");
            foreach (var record in file.WatchProgress)
            {
                writer.WriteStartObject();
                writer.WriteString("type", record.Type);
                writer.WriteNumber("tmdbId", record.TmdbId);
                if (record.Type == "tv")
                {
                    writer.WriteNumber("season", record.Season ?? 0);
                    writer.WriteNumber("episode", record.Episode ?? 0);
                }
                if (record.Cleared)
                {
                    writer.WriteBoolean("cleared", true);
                }
                else
                {
                    writer.WriteNumber("position", record.Position ?? 0);
                    writer.WriteNumber("duration", record.Duration ?? 1);
                    writer.WriteBoolean("completed", record.Completed ?? false);
                }
                writer.WriteString("device", record.Device);
                writer.WriteNumber("updateTime", record.UpdateTime);
                writer.WriteEndObject();
            }
            writer.WriteEndArray();

            writer.WriteStartArray("userMedia");
            foreach (var record in file.UserMedia)
            {
                writer.WriteStartObject();
                writer.WriteString("type", record.Type);
                writer.WriteNumber("tmdbId", record.TmdbId);
                writer.WriteBoolean("favourite", record.Favourite);
                writer.WriteBoolean("watchlist", record.Watchlist);
                if (record.Rating is double rating) writer.WriteNumber("rating", rating); else writer.WriteNull("rating");
                if (record.Review is string review) writer.WriteString("review", review); else writer.WriteNull("review");
                writer.WriteString("device", record.Device);
                writer.WriteNumber("updateTime", record.UpdateTime);
                writer.WriteEndObject();
            }
            writer.WriteEndArray();
            writer.WriteEndObject();
        }
        return buffer.ToArray();
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /// <summary>
    /// Decodes and validates a device file read from the space folder
    /// <paramref name="space"/> under the name <paramref name="fileName"/>.
    /// </summary>
    public static SyncDecodeResult Decode(ReadOnlyMemory<byte> bytes, string fileName, string space)
    {
        if (bytes.Length > SyncFormat.MaxFileBytes) return SyncDecodeResult.Skip(SyncSkipReason.TooLarge);
        if (!fileName.EndsWith(".json", StringComparison.Ordinal)) return SyncDecodeResult.Skip(SyncSkipReason.WrongDeviceId);
        var expectedId = fileName[..^5];

        // Writers never emit a byte-order mark, but a file saved by hand may carry one.
        var span = bytes;
        if (span.Length >= 3 && span.Span[0] == 0xEF && span.Span[1] == 0xBB && span.Span[2] == 0xBF) span = span[3..];

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(span, new JsonDocumentOptions { AllowTrailingCommas = false, CommentHandling = JsonCommentHandling.Disallow });
        }
        catch (JsonException)
        {
            return SyncDecodeResult.Skip(SyncSkipReason.Malformed);
        }

        using (document)
        {
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return SyncDecodeResult.Skip(SyncSkipReason.Malformed);

            // The device name is read first so a skip can still name the device.
            string? deviceName = null;
            if (Member(root, "device") is { ValueKind: JsonValueKind.Object } deviceElement
                && Member(deviceElement, "name") is { ValueKind: JsonValueKind.String } nameElement)
            {
                deviceName = nameElement.GetString();
            }

            if (Integer(root, "format") is not int format || format < 1) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
            if (format > SyncFormat.Version) return SyncDecodeResult.Skip(SyncSkipReason.NewerFormat, deviceName);

            if (Text(root, "space") is not string fileSpace) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
            if (!string.Equals(fileSpace, space, StringComparison.Ordinal)) return SyncDecodeResult.Skip(SyncSkipReason.WrongSpace, deviceName);

            if (Member(root, "device") is not { ValueKind: JsonValueKind.Object } device) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
            if (Text(device, "id") is not string id) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
            if (!string.Equals(id, expectedId, StringComparison.Ordinal)) return SyncDecodeResult.Skip(SyncSkipReason.WrongDeviceId, deviceName);
            if (deviceName is null || deviceName.Length == 0 || deviceName.Length > 64) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
            var platform = Text(device, "platform") ?? "";
            var app = Text(device, "app") ?? "";

            long fileStamp = 0;
            if (Member(root, "updateTime") is { } stampElement && stampElement.ValueKind != JsonValueKind.Null)
            {
                if (Long(stampElement) is not long stamp) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
                fileStamp = stamp;
            }

            var progress = new List<SyncProgressRecord>();
            var media = new List<SyncUserMediaRecord>();
            if (Member(root, "watchProgress") is { } progressElement && progressElement.ValueKind != JsonValueKind.Null)
            {
                if (progressElement.ValueKind != JsonValueKind.Array) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
                foreach (var element in progressElement.EnumerateArray())
                {
                    if (ReadProgress(element) is not { } record) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
                    progress.Add(record);
                }
            }
            if (Member(root, "userMedia") is { } mediaElement && mediaElement.ValueKind != JsonValueKind.Null)
            {
                if (mediaElement.ValueKind != JsonValueKind.Array) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
                foreach (var element in mediaElement.EnumerateArray())
                {
                    if (ReadUserMedia(element) is not { } record) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
                    media.Add(record);
                }
            }
            if (progress.Count + media.Count > SyncFormat.MaxRecords) return SyncDecodeResult.Skip(SyncSkipReason.TooLarge, deviceName);

            // Keys are unique within each array.
            var seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (var record in progress)
            {
                if (!seen.Add(record.Key)) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
            }
            seen.Clear();
            foreach (var record in media)
            {
                if (!seen.Add(record.Key)) return SyncDecodeResult.Skip(SyncSkipReason.InvalidField, deviceName);
            }

            return SyncDecodeResult.Ok(new SyncDeviceFile
            {
                Format = format,
                Space = fileSpace,
                Device = new SyncDevice(id, deviceName, platform, app),
                UpdateTime = fileStamp,
                WatchProgress = progress,
                UserMedia = media,
            });
        }
    }

    private static SyncProgressRecord? ReadProgress(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return null;
        if (Text(element, "type") is not string type || type is not ("movie" or "tv")) return null;
        if (Integer(element, "tmdbId") is not int tmdbId || tmdbId < 1) return null;
        if (!ReadStampAndDevice(element, out var stamp, out var device)) return null;

        int? season = null, episode = null;
        var hasSeason = Member(element, "season") is { ValueKind: not JsonValueKind.Null };
        var hasEpisode = Member(element, "episode") is { ValueKind: not JsonValueKind.Null };
        if (type == "tv")
        {
            if (!hasSeason || !hasEpisode) return null;
            season = Integer(element, "season");
            episode = Integer(element, "episode");
            if (season is null or < 0 || episode is null or < 0) return null;
        }
        else if (hasSeason || hasEpisode)
        {
            return null;
        }

        var cleared = false;
        if (Member(element, "cleared") is { ValueKind: not JsonValueKind.Null } clearedElement)
        {
            if (clearedElement.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) return null;
            cleared = clearedElement.ValueKind == JsonValueKind.True;
        }
        if (cleared)
        {
            // A tombstone's values are absent; anything a writer left behind is ignored.
            return new SyncProgressRecord { Type = type, TmdbId = tmdbId, Season = season, Episode = episode, Cleared = true, Device = device, UpdateTime = stamp };
        }

        if (Number(element, "position") is not double position || position < 0) return null;
        if (Number(element, "duration") is not double duration || duration <= 0) return null;
        if (Member(element, "completed") is not { ValueKind: JsonValueKind.True or JsonValueKind.False } completedElement) return null;
        return new SyncProgressRecord
        {
            Type = type,
            TmdbId = tmdbId,
            Season = season,
            Episode = episode,
            Position = position,
            Duration = duration,
            Completed = completedElement.ValueKind == JsonValueKind.True,
            Device = device,
            UpdateTime = stamp,
        };
    }

    private static SyncUserMediaRecord? ReadUserMedia(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return null;
        if (Text(element, "type") is not string type || type is not ("movie" or "tv")) return null;
        if (Integer(element, "tmdbId") is not int tmdbId || tmdbId < 1) return null;
        if (!ReadStampAndDevice(element, out var stamp, out var device)) return null;
        if (Boolean(element, "favourite") is not bool favourite) return null;
        if (Boolean(element, "watchlist") is not bool watchlist) return null;

        double? rating = null;
        if (Member(element, "rating") is { ValueKind: not JsonValueKind.Null } ratingElement)
        {
            if (ratingElement.ValueKind != JsonValueKind.Number || !ratingElement.TryGetDouble(out var value) || !SyncUserMediaRecord.IsValidRating(value)) return null;
            rating = value;
        }
        string? review = null;
        if (Member(element, "review") is { ValueKind: not JsonValueKind.Null } reviewElement)
        {
            if (reviewElement.ValueKind != JsonValueKind.String) return null;
            review = reviewElement.GetString();
            if (review is null || !SyncUserMediaRecord.IsValidReview(review)) return null;
        }
        return new SyncUserMediaRecord
        {
            Type = type,
            TmdbId = tmdbId,
            Favourite = favourite,
            Watchlist = watchlist,
            Rating = rating,
            Review = review,
            Device = device,
            UpdateTime = stamp,
        };
    }

    /// <summary>§6: `device` is required; §7.3: a missing or invalid `updateTime` counts as 0.</summary>
    private static bool ReadStampAndDevice(JsonElement element, out long stamp, out string device)
    {
        stamp = 0;
        device = "";
        if (Text(element, "device") is not { Length: > 0 } writer) return false;
        device = writer;
        if (Member(element, "updateTime") is { } stampElement && Long(stampElement) is long value && value >= SyncFormat.MinimumValidStamp)
        {
            stamp = value;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // JSON helpers (strict: integers carry no fraction, booleans are never 0/1)
    // ------------------------------------------------------------------

    private static JsonElement? Member(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) ? value : null;

    private static string? Text(JsonElement element, string name) =>
        Member(element, name) is { ValueKind: JsonValueKind.String } value ? value.GetString() : null;

    private static bool? Boolean(JsonElement element, string name) => Member(element, name)?.ValueKind switch
    {
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        _ => null,
    };

    private static double? Number(JsonElement element, string name) =>
        Member(element, name) is { ValueKind: JsonValueKind.Number } value && value.TryGetDouble(out var number) && double.IsFinite(number)
            ? number
            : null;

    private static int? Integer(JsonElement element, string name) =>
        Member(element, name) is { } value && Long(value) is long number && number is >= int.MinValue and <= int.MaxValue ? (int)number : null;

    /// <summary>An integer-valued number; `1.0` is rejected because the text carries a fraction.</summary>
    private static long? Long(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Number) return null;
        var text = element.GetRawText();
        if (text.Contains('.') || text.Contains('e') || text.Contains('E')) return null;
        return long.TryParse(text, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var value) ? value : null;
    }
}
