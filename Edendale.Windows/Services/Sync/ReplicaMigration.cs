// The one-time import of the OneDrive replica that builds before 27.1 kept in
// %OneDrive%\Apps\Edendale\ (SYNC-PROTOCOL.md §8.4, FEATURES-27.1.md §5.5):
// user-media.json and watch-progress.json are read once, each record is
// stamped with its existing timestamp and this device's ID, merged into the
// local store by the §7 rule, and the files are left where they are (§10,
// decision 3, recommended answer). Nothing writes them any more.

using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services.Sync;

public static class ReplicaMigration
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    /// <summary>What one import did, for the log and the launch notice.</summary>
    public sealed record Outcome(int UserMediaRead, int ProgressRead, bool FoundFiles);

    /// <summary>
    /// Imports the replica in <paramref name="replicaDirectory"/> into the
    /// stores. Safe to call when the folder or files are missing: nothing
    /// happens and the outcome says so.
    /// </summary>
    public static Outcome Import(string replicaDirectory, WatchProgressStore progress, UserMediaStore media, string deviceId)
    {
        var replicaMedia = ReadList<UserMediaRecord>(Path.Combine(replicaDirectory, "user-media.json"));
        var replicaProgress = ReadList<WatchProgress>(Path.Combine(replicaDirectory, "watch-progress.json"));
        var found = File.Exists(Path.Combine(replicaDirectory, "user-media.json")) || File.Exists(Path.Combine(replicaDirectory, "watch-progress.json"));
        if (replicaMedia.Count == 0 && replicaProgress.Count == 0) return new Outcome(0, 0, found);

        // §8.4: the existing timestamp becomes the stamp, the device is this one.
        foreach (var record in replicaMedia)
        {
            if (record.UpdateTime == 0) record.UpdateTime = record.EffectiveUpdateTime;
            record.Device ??= deviceId;
        }
        foreach (var record in replicaProgress) record.Device ??= deviceId;

        // Local records from before sync carry no device either; signing them
        // with this device's ID makes the tie-break deterministic.
        var localMedia = media.All.Select(record =>
        {
            if (record.UpdateTime == 0) record.UpdateTime = record.EffectiveUpdateTime;
            record.Device ??= deviceId;
            return record;
        }).ToList();
        var localProgress = progress.AllIncludingCleared.Select(record =>
        {
            record.Device ??= deviceId;
            return record;
        }).ToList();

        media.ReplaceAll(WindowsCore.MergeUserMedia(localMedia, replicaMedia));
        progress.ReplaceAll(WindowsCore.MergeWatchProgress(localProgress, replicaProgress));
        SyncLog.Info("migration", $"imported replica: {replicaMedia.Count} user-media, {replicaProgress.Count} progress records");
        return new Outcome(replicaMedia.Count, replicaProgress.Count, found);
    }

    private static List<T> ReadList<T>(string path)
    {
        try
        {
            if (!File.Exists(path)) return [];
            return JsonSerializer.Deserialize<List<T>>(File.ReadAllText(path), JsonOptions) ?? [];
        }
        catch
        {
            return [];
        }
    }
}
