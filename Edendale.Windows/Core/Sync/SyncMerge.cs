// The merge rule of SYNC-PROTOCOL.md §7: per key, the highest updateTime wins
// as a whole record, ties go to the lexically smaller device ID, stamps more
// than a day ahead are refused, and a device never stamps below what it has
// applied. Deterministic, idempotent, and independent of read order (§7.4).

namespace Edendale.Windows.Core.Sync;

/// <summary>
/// The monotonic stamp of §7.2 and the high-water mark it rests on. One per
/// device, shared by every store that writes records, and persisted with the
/// sync state.
/// </summary>
public sealed class SyncClock
{
    private readonly Func<long> _now;
    private readonly object _gate = new();
    private long _highWater;

    public SyncClock(Func<long>? now = null, long highWater = 0)
    {
        _now = now ?? (() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        _highWater = highWater;
    }

    /// <summary>The highest stamp this device has applied or written.</summary>
    public long HighWater
    {
        get { lock (_gate) return _highWater; }
    }

    /// <summary>Raised when <see cref="HighWater"/> moves, so it can be persisted.</summary>
    public event EventHandler? HighWaterChanged;

    /// <summary>The device's UTC clock in milliseconds.</summary>
    public long Now => _now();

    /// <summary>§7.2: `max(now, highWater + 1)`, which becomes the new high-water mark.</summary>
    public long Stamp()
    {
        long stamp;
        lock (_gate)
        {
            stamp = Math.Max(_now(), _highWater + 1);
            _highWater = stamp;
        }
        HighWaterChanged?.Invoke(this, EventArgs.Empty);
        return stamp;
    }

    /// <summary>§7.1: a stamp that was applied raises the high-water mark.</summary>
    public void Observe(long stamp)
    {
        var moved = false;
        lock (_gate)
        {
            if (stamp > _highWater)
            {
                _highWater = stamp;
                moved = true;
            }
        }
        if (moved) HighWaterChanged?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Restores a persisted mark without raising an event; the stores' records may be higher still.</summary>
    public void Restore(long highWater)
    {
        lock (_gate) _highWater = Math.Max(_highWater, highWater);
    }
}

/// <summary>What applying one file to the local store did.</summary>
public sealed class SyncMergeOutcome
{
    /// <summary>Records that replaced the local copy or were added.</summary>
    public int Applied { get; set; }

    /// <summary>Records that lost to the local copy.</summary>
    public int Ignored { get; set; }

    /// <summary>Records refused by the far-future guard; the file's device is flagged.</summary>
    public int FarFuture { get; set; }

    /// <summary>The keys whose local copy changed, so the stores and UI can follow.</summary>
    public List<string> ChangedKeys { get; } = [];

    public bool Changed => Applied > 0;
}

public static class SyncMerge
{
    /// <summary>
    /// §7.1: whether <paramref name="incoming"/> replaces <paramref name="local"/>.
    /// Equal stamps are broken by the lexically smaller device string, bytewise
    /// on the lowercase UUID; the same device at the same stamp is the same write.
    /// </summary>
    public static bool Wins(SyncRecord incoming, SyncRecord? local)
    {
        if (local is null) return true;
        if (incoming.UpdateTime != local.UpdateTime) return incoming.UpdateTime > local.UpdateTime;
        return string.CompareOrdinal(incoming.Device, local.Device) < 0;
    }

    /// <summary>§7.1: whether a stamp is more than 24 hours ahead of <paramref name="now"/>.</summary>
    public static bool IsFarFuture(long updateTime, long now) => updateTime > now + SyncFormat.FarFutureMillis;

    /// <summary>
    /// Applies the records of another device's file to <paramref name="store"/>,
    /// verbatim (device and stamp included), raising the clock's high-water mark
    /// with every stamp that was not refused.
    /// </summary>
    public static SyncMergeOutcome Apply<T>(IDictionary<string, T> store, IEnumerable<T> incoming, SyncClock clock) where T : SyncRecord
    {
        var outcome = new SyncMergeOutcome();
        var now = clock.Now;
        foreach (var record in incoming)
        {
            if (IsFarFuture(record.UpdateTime, now))
            {
                outcome.FarFuture++;
                continue;
            }
            store.TryGetValue(record.Key, out var local);
            if (Wins(record, local))
            {
                store[record.Key] = record;
                outcome.Applied++;
                outcome.ChangedKeys.Add(record.Key);
            }
            else
            {
                outcome.Ignored++;
            }
            clock.Observe(record.UpdateTime);
        }
        return outcome;
    }

    /// <summary>§6.3: whether a tombstone is old enough to leave out of this device's file.</summary>
    public static bool IsExpiredTombstone(SyncRecord record, long now) =>
        record.IsTombstone && record.UpdateTime < now - SyncFormat.TombstoneRetentionMillis;
}
