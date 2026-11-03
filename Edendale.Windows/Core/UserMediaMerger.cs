// The merge of two record sets, as FEATURES-27.1.md §5.3 and
// SYNC-PROTOCOL.md §7.1 define it: per key, the newest record wins as a
// whole; equal stamps go to the lexically smaller device ID; cleared records
// are tombstones and are kept. Used by the one-time import of the OneDrive
// replica (§8.4) and by the tests; the live sync applies the same rule
// through Core/Sync/SyncMerge.

using Edendale.Windows.Models;

namespace Edendale.Windows.Core;

internal static class UserMediaMerger
{
    public static List<WatchProgress> MergeWatchProgress(
        IReadOnlyList<WatchProgress> first,
        IReadOnlyList<WatchProgress> second)
    {
        var byKey = new Dictionary<string, WatchProgress>(StringComparer.Ordinal);
        var order = new List<string>();
        foreach (var record in first.Concat(second))
        {
            var key = record.SyncKey;
            if (!byKey.TryGetValue(key, out var current))
            {
                order.Add(key);
                byKey[key] = record;
            }
            else if (Wins(record.LastWatchedEpochMillis, record.Device, current.LastWatchedEpochMillis, current.Device))
            {
                byKey[key] = record;
            }
        }
        return order.Select(key => byKey[key]).ToList();
    }

    public static List<UserMediaRecord> MergeUserMedia(
        IReadOnlyList<UserMediaRecord> first,
        IReadOnlyList<UserMediaRecord> second)
    {
        var byKey = new Dictionary<string, UserMediaRecord>(StringComparer.Ordinal);
        var order = new List<string>();
        foreach (var record in first.Concat(second))
        {
            var key = record.StorageKey;
            if (!byKey.TryGetValue(key, out var current))
            {
                order.Add(key);
                byKey[key] = record;
            }
            else if (Wins(record.EffectiveUpdateTime, record.Device, current.EffectiveUpdateTime, current.Device))
            {
                byKey[key] = record;
            }
        }
        // Tombstones stay: dropping one would discard the time of the clear.
        return order.Select(key => byKey[key]).ToList();
    }

    /// <summary>§7.1: newer wins; on a tie the lexically smaller device; the same write never replaces itself.</summary>
    internal static bool Wins(long incomingTime, string? incomingDevice, long currentTime, string? currentDevice)
    {
        if (incomingTime != currentTime) return incomingTime > currentTime;
        return string.CompareOrdinal(incomingDevice ?? "", currentDevice ?? "") < 0;
    }
}
