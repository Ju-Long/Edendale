using System.Text;
using System.Text.Json;
using Edendale.Windows.Core.Sync;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>SYNC-PROTOCOL.md §1.2, §5, and §6: what a device file must look like, read strictly and written exactly.</summary>
[TestClass]
public sealed class SyncDeviceFileTests
{
    private const string A = SyncMergeTests.A;
    private const string B = SyncMergeTests.B;

    private static SyncDeviceFile Sample() => new()
    {
        Space = "Family",
        Device = new SyncDevice(A, "Living Room PC", "windows", "27.1"),
        UpdateTime = 1762041600000,
        WatchProgress =
        [
            SyncMergeTests.Movie(603, 1234.5, 1762041600000, A),
            new SyncProgressRecord { Type = "tv", TmdbId = 1399, Season = 1, Episode = 2, Position = 10, Duration = 3000, Completed = true, Device = B, UpdateTime = 1762038000000 },
            new SyncProgressRecord { Type = "tv", TmdbId = 1399, Season = 1, Episode = 3, Cleared = true, Device = A, UpdateTime = 1762038000001 },
        ],
        UserMedia =
        [
            SyncMergeTests.Media("tv", 1399, 1762038000000, B, favourite: true, rating: 8.5),
            SyncMergeTests.Media("movie", 603, 1762038000002, A, watchlist: true, review: "Still holds up."),
        ],
    };

    [TestMethod]
    public void RoundTrips_WithOnlyTheDefinedFields()
    {
        var bytes = SyncDeviceFileCodec.Encode(Sample());
        Assert.IsFalse(bytes[0] == 0xEF, "no byte-order mark");
        var text = Encoding.UTF8.GetString(bytes);
        using var document = JsonDocument.Parse(text);
        var root = document.RootElement;
        Assert.AreEqual(1, root.GetProperty("format").GetInt32());
        Assert.AreEqual("Family", root.GetProperty("space").GetString());
        var movie = root.GetProperty("watchProgress")[0];
        Assert.IsFalse(movie.TryGetProperty("season", out _), "movies carry no season");
        Assert.IsFalse(movie.TryGetProperty("cleared", out _), "live records carry no cleared flag");
        var tombstone = root.GetProperty("watchProgress")[2];
        Assert.IsTrue(tombstone.GetProperty("cleared").GetBoolean());
        Assert.IsFalse(tombstone.TryGetProperty("position", out _));
        var media = root.GetProperty("userMedia")[0];
        Assert.AreEqual(JsonValueKind.Null, media.GetProperty("review").ValueKind);
        Assert.AreEqual(JsonValueKind.False, media.GetProperty("watchlist").ValueKind);

        var decoded = SyncDeviceFileCodec.Decode(bytes, A + ".json", "Family");
        Assert.IsTrue(decoded.IsValid, decoded.Skipped?.ToString());
        var file = decoded.File!;
        Assert.AreEqual(Sample().Device, file.Device);
        Assert.AreEqual(1762041600000, file.UpdateTime);
        CollectionAssert.AreEqual(Sample().WatchProgress.ToList(), file.WatchProgress.ToList());
        CollectionAssert.AreEqual(Sample().UserMedia.ToList(), file.UserMedia.ToList());
    }

    [TestMethod]
    public void Keys_FollowSection6()
    {
        Assert.AreEqual("movie:603", SyncProgressRecord.MovieKey(603));
        Assert.AreEqual("tv:1399:1:2", SyncProgressRecord.EpisodeKey(1399, 1, 2));
        Assert.AreEqual("tv:1399", SyncMergeTests.Media("tv", 1399, 0, A).Key);
    }

    private static SyncDecodeResult DecodeRecord(string progressJson, string mediaJson = "")
    {
        var json = $$"""{"format":1,"space":"Family","device":{"id":"{{B}}","name":"B","platform":"android","app":"27.1"},"updateTime":0,"watchProgress":[{{progressJson}}],"userMedia":[{{mediaJson}}]}""";
        return SyncDeviceFileCodec.Decode(Encoding.UTF8.GetBytes(json), B + ".json", "Family");
    }

    [TestMethod]
    public void Rejects_FractionsOnIntegers_NumbersAsBooleans_AndBadRanges()
    {
        var okProgress = $$"""{"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":false,"device":"{{B}}","updateTime":1762000000000}""";
        Assert.IsTrue(DecodeRecord(okProgress).IsValid);
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress.Replace("\"tmdbId\":603", "\"tmdbId\":603.0")).Skipped, "tmdbId with a fraction");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress.Replace("\"completed\":false", "\"completed\":0")).Skipped, "0 is not false");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress.Replace("\"duration\":2", "\"duration\":0")).Skipped, "duration must be > 0");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress.Replace("\"position\":1", "\"position\":-1")).Skipped, "position must be >= 0");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress.Replace("\"tmdbId\":603", "\"tmdbId\":0")).Skipped, "tmdbId >= 1");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress.Replace("\"type\":\"movie\"", "\"type\":\"episode\"")).Skipped, "type is movie or tv");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress.Replace("\"type\":\"movie\"", "\"type\":\"movie\",\"season\":1,\"episode\":1")).Skipped, "movies have no season");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress.Replace("\"type\":\"movie\"", "\"type\":\"tv\",\"season\":1")).Skipped, "season and episode come together");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress.Replace($"\"device\":\"{B}\",", "")).Skipped, "device is required");
        Assert.IsTrue(DecodeRecord(okProgress.Replace(",\"updateTime\":1762000000000", "")).IsValid, "a missing updateTime counts as 0, not invalid");
        Assert.IsTrue(DecodeRecord(okProgress + "," + okProgress.Replace("603", "604")).IsValid);
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress + "," + okProgress).Skipped, "keys are unique within the file");

        var okMedia = $$"""{"type":"tv","tmdbId":1399,"favourite":true,"watchlist":false,"rating":8.5,"review":null,"device":"{{B}}","updateTime":1762000000000}""";
        Assert.IsTrue(DecodeRecord(okProgress, okMedia).IsValid);
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress, okMedia.Replace("8.5", "8.3")).Skipped, "ratings step by 0.5");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress, okMedia.Replace("8.5", "10.5")).Skipped, "ratings top out at 10");
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress, okMedia.Replace("\"favourite\":true", "\"favourite\":1")).Skipped);
        Assert.AreEqual(SyncSkipReason.InvalidField, DecodeRecord(okProgress, okMedia.Replace("\"review\":null", "\"review\":\"" + new string('x', 10_001) + "\"")).Skipped, "reviews are at most 10,000 scalars");
        Assert.IsTrue(DecodeRecord(okProgress, okMedia.Replace("\"review\":null", "\"review\":\"" + new string('x', 10_000) + "\"")).IsValid);
    }

    [TestMethod]
    public void Tolerates_UnknownFields_AndATombstoneWithStrayValues()
    {
        var progress = $$"""{"type":"tv","tmdbId":1399,"season":1,"episode":2,"cleared":true,"position":5,"somethingNew":{"a":1},"device":"{{B}}","updateTime":1762000000000}""";
        var decoded = DecodeRecord(progress);
        Assert.IsTrue(decoded.IsValid, decoded.Skipped?.ToString());
        Assert.IsTrue(decoded.File!.WatchProgress[0].Cleared);
        Assert.IsNull(decoded.File.WatchProgress[0].Position);
    }

    [TestMethod]
    public void Rejects_MalformedJson_MissingFormat_AndOversizedRecordCounts()
    {
        Assert.AreEqual(SyncSkipReason.Malformed, SyncDeviceFileCodec.Decode("not json"u8.ToArray(), B + ".json", "Family").Skipped);
        Assert.AreEqual(SyncSkipReason.Malformed, SyncDeviceFileCodec.Decode("[]"u8.ToArray(), B + ".json", "Family").Skipped);
        var noFormat = $$"""{"space":"Family","device":{"id":"{{B}}","name":"B"},"watchProgress":[],"userMedia":[]}""";
        Assert.AreEqual(SyncSkipReason.InvalidField, SyncDeviceFileCodec.Decode(Encoding.UTF8.GetBytes(noFormat), B + ".json", "Family").Skipped);

        // 100,001 short tombstones fit under 8 MiB, so this trips the record limit, not the size limit.
        var builder = new StringBuilder($$"""{"format":1,"space":"Family","device":{"id":"{{B}}","name":"B","platform":"android","app":"27.1"},"updateTime":0,"watchProgress":[""");
        for (var i = 0; i < 100_001; i++)
        {
            if (i > 0) builder.Append(',');
            builder.Append($$"""{"type":"tv","tmdbId":{{i + 1}},"season":0,"episode":0,"cleared":true,"device":"x"}""");
        }
        builder.Append("],\"userMedia\":[]}");
        var bytes = Encoding.UTF8.GetBytes(builder.ToString());
        Assert.IsTrue(bytes.Length < SyncFormat.MaxFileBytes, $"{bytes.Length} bytes");
        Assert.AreEqual(SyncSkipReason.TooLarge, SyncDeviceFileCodec.Decode(bytes, B + ".json", "Family").Skipped);
    }

    [TestMethod]
    public void DeviceFileNames_AreLowercaseUuids()
    {
        Assert.IsTrue(SyncFormat.IsDeviceFileName(A + ".json"));
        Assert.IsFalse(SyncFormat.IsDeviceFileName(A.ToUpperInvariant() + ".json"));
        Assert.IsFalse(SyncFormat.IsDeviceFileName(A + ".json.tmp"));
        Assert.IsFalse(SyncFormat.IsDeviceFileName("user-media.json"));
        Assert.IsTrue(SyncFormat.IsDeviceId(SyncFormat.NewDeviceId()));
    }

    [TestMethod]
    public void UnknownPlatforms_ReadAsAnotherDevice()
    {
        Assert.IsTrue(new SyncDevice(A, "x", "windows", "27.1").IsKnownPlatform);
        Assert.IsFalse(new SyncDevice(A, "x", "fridge", "27.1").IsKnownPlatform);
    }
}
