// The sync format of SYNC-PROTOCOL.md on `main` (version 1, 2026-11-02),
// implemented natively for Windows: one device file per device inside
// Edendale/<space-name>/, records keyed by TMDB identity, and one
// `updateTime` per record. Nothing here reaches the network or the UI; the
// stores and the sync engine map Windows' own models onto these records.

using System.Text;

namespace Edendale.Windows.Core.Sync;

/// <summary>The constants of SYNC-PROTOCOL.md §1–§7.</summary>
public static class SyncFormat
{
    /// <summary>The `format` field this build writes and the highest it reads.</summary>
    public const int Version = 1;

    /// <summary>§7.3: a stamp below 2000-01-01T00:00:00Z counts as 0.</summary>
    public const long MinimumValidStamp = 946_684_800_000;

    /// <summary>§7.1: a record stamped further ahead than this is not applied.</summary>
    public const long FarFutureMillis = 86_400_000;

    /// <summary>§5: files over this size are skipped with a warning.</summary>
    public const long MaxFileBytes = 8 * 1024 * 1024;

    /// <summary>§5: files with more records than this are skipped with a warning.</summary>
    public const int MaxRecords = 100_000;

    /// <summary>§6.2: a review is at most this many Unicode scalar values.</summary>
    public const int MaxReviewScalars = 10_000;

    /// <summary>
    /// §6.3: a device may omit tombstones older than a year from its own
    /// file. 366 days, as on the Apple and Android branches: a year plus the
    /// far-future day, so the M3 vector's tombstone (365.48 days old at the
    /// vector's clock) is still written.
    /// </summary>
    public const long TombstoneRetentionMillis = 366L * 86_400_000;

    /// <summary>§3: the `device.platform` value Windows writes.</summary>
    public const string WindowsPlatform = "windows";

    /// <summary>§3: every platform value the format defines.</summary>
    public static readonly IReadOnlyList<string> KnownPlatforms =
        ["ios", "ipados", "macos", "tvos", "visionos", "android", "androidtv", "horizon", "windows", "xbox"];

    /// <summary>The file name of a device's file inside the space folder.</summary>
    public static string FileName(string deviceId) => deviceId + ".json";

    /// <summary>§4: the folder every location holds the spaces in.</summary>
    public const string RootFolderName = "Edendale";

    /// <summary>§3: a version-4 UUID in lowercase hyphenated form.</summary>
    public static string NewDeviceId() => Guid.NewGuid().ToString("D").ToLowerInvariant();

    /// <summary>Whether a file name is `&lt;lowercase uuid&gt;.json` (§8.2 reads only those).</summary>
    public static bool IsDeviceFileName(string name)
    {
        if (!name.EndsWith(".json", StringComparison.Ordinal)) return false;
        var id = name[..^5];
        return IsDeviceId(id);
    }

    public static bool IsDeviceId(string id)
    {
        if (id.Length != 36) return false;
        for (var i = 0; i < id.Length; i++)
        {
            var c = id[i];
            if (i is 8 or 13 or 18 or 23)
            {
                if (c != '-') return false;
            }
            else if (!(c is >= '0' and <= '9' or >= 'a' and <= 'f'))
            {
                return false;
            }
        }
        return true;
    }
}

/// <summary>§3: the space name is a folder name, compared exactly.</summary>
public static class SyncSpaceName
{
    public const int MaxLength = 64;

    /// <summary>The trimmed name, or null when the rules of §3 reject it.</summary>
    public static string? Normalize(string? raw)
    {
        if (raw is null) return null;
        var name = raw.Trim();
        if (name.Length == 0 || name is "." or "..") return null;
        var scalars = 0;
        foreach (var rune in name.EnumerateRunes())
        {
            scalars++;
            if (rune.Value < 0x20 || rune.Value == 0x7F) return null;
            if (rune.Value < 0x80 && "/\\:*?\"<>|".Contains((char)rune.Value)) return null;
        }
        return scalars <= MaxLength ? name : null;
    }

    public static bool IsValid(string? raw) => Normalize(raw) is not null;
}

/// <summary>The device block of a device file (§5).</summary>
public sealed record SyncDevice(string Id, string Name, string Platform, string App)
{
    /// <summary>Whether <see cref="Platform"/> is one the format defines; unknown ones read as "another device".</summary>
    public bool IsKnownPlatform => SyncFormat.KnownPlatforms.Contains(Platform, StringComparer.Ordinal);
}

/// <summary>What every record carries (§6, §7): a key, a stamp, and the device that wrote it.</summary>
public abstract record SyncRecord
{
    /// <summary>`movie` or `tv`.</summary>
    public required string Type { get; init; }

    public required int TmdbId { get; init; }

    /// <summary>The ID of the device that wrote this version; kept verbatim when the record is stored or re-written.</summary>
    public required string Device { get; init; }

    /// <summary>UTC milliseconds; 0 for an invalid stamp (§7.3).</summary>
    public required long UpdateTime { get; init; }

    public abstract string Key { get; }

    public abstract bool IsTombstone { get; }

    /// <summary>§7.3: whether the stamp was valid when read.</summary>
    public bool HasValidStamp => UpdateTime >= SyncFormat.MinimumValidStamp;
}

/// <summary>A `watchProgress` record (§6.1).</summary>
public sealed record SyncProgressRecord : SyncRecord
{
    /// <summary>Present together for `tv`; absent for `movie`.</summary>
    public int? Season { get; init; }
    public int? Episode { get; init; }

    /// <summary>Seconds; absent in a tombstone.</summary>
    public double? Position { get; init; }
    public double? Duration { get; init; }
    public bool? Completed { get; init; }

    /// <summary>`true` marks a tombstone.</summary>
    public bool Cleared { get; init; }

    public override string Key => Type == "tv" ? $"tv:{TmdbId}:{Season}:{Episode}" : $"movie:{TmdbId}";

    public override bool IsTombstone => Cleared;

    public static string MovieKey(int tmdbId) => $"movie:{tmdbId}";
    public static string EpisodeKey(int showTmdbId, int season, int episode) => $"tv:{showTmdbId}:{season}:{episode}";
}

/// <summary>A `userMedia` record (§6.2). All four values at their defaults is the tombstone.</summary>
public sealed record SyncUserMediaRecord : SyncRecord
{
    public bool Favourite { get; init; }
    public bool Watchlist { get; init; }

    /// <summary>0.5 to 10.0 in steps of 0.5, or null.</summary>
    public double? Rating { get; init; }

    /// <summary>At most 10,000 scalar values, or null.</summary>
    public string? Review { get; init; }

    public override string Key => $"{Type}:{TmdbId}";

    public override bool IsTombstone => !Favourite && !Watchlist && Rating is null && Review is null;

    /// <summary>Whether a rating is on TMDB's half-step scale (§6.2).</summary>
    public static bool IsValidRating(double rating)
    {
        if (double.IsNaN(rating) || double.IsInfinity(rating) || rating < 0.5 || rating > 10.0) return false;
        var doubled = rating * 2;
        return Math.Abs(doubled - Math.Round(doubled)) < 1e-9;
    }

    /// <summary>Whether a review is within §6.2's length limit.</summary>
    public static bool IsValidReview(string review)
    {
        var scalars = 0;
        foreach (var _ in review.EnumerateRunes())
        {
            if (++scalars > SyncFormat.MaxReviewScalars) return false;
        }
        return true;
    }
}

/// <summary>One device's whole merged watch state (§5).</summary>
public sealed class SyncDeviceFile
{
    public int Format { get; init; } = SyncFormat.Version;
    public required string Space { get; init; }
    public required SyncDevice Device { get; init; }

    /// <summary>The highest `updateTime` of any record in the file, or 0.</summary>
    public long UpdateTime { get; init; }

    public IReadOnlyList<SyncProgressRecord> WatchProgress { get; init; } = [];
    public IReadOnlyList<SyncUserMediaRecord> UserMedia { get; init; } = [];

    public int RecordCount => WatchProgress.Count + UserMedia.Count;

    public IEnumerable<SyncRecord> AllRecords => WatchProgress.Cast<SyncRecord>().Concat(UserMedia);

    /// <summary>The file's top-level stamp as §5 defines it, from its records.</summary>
    public static long HighestStamp(IEnumerable<SyncRecord> records)
    {
        long highest = 0;
        foreach (var record in records) highest = Math.Max(highest, record.UpdateTime);
        return highest;
    }
}
