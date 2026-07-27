// Cloud replication over OneDrive — Windows' default cloud storage. A replica
// of user-media.json + watch-progress.json lives in %OneDrive%\Apps\Edendale\
// so a second PC signed into the same OneDrive converges: at launch the
// replica is merged through Windows' deterministic merge rules
// (field-wise newest-wins for user media, newest write per title for watch
// progress), then every local change is written through, debounced. Without
// OneDrive the app stays local-only.
//
// The TMDB session is deliberately NOT replicated — it is a credential and
// stays DPAPI-protected on this device.

using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

public sealed class CloudSyncService
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
    };

    private readonly UserMediaStore _userMedia;
    private readonly WatchProgressStore _watchProgress;
    private readonly object _gate = new();
    private Timer? _writeDebounce;
    private bool _initialized;

    public CloudSyncService(UserMediaStore userMedia, WatchProgressStore watchProgress)
    {
        _userMedia = userMedia;
        _watchProgress = watchProgress;
    }

    /// <summary>Null when OneDrive is not set up on this machine.</summary>
    public string? ReplicaDirectory => AppPaths.CloudReplicaDirectory;

    public bool IsActive => _initialized && ReplicaDirectory is not null;

    /// <summary>
    /// Merges the OneDrive replica into the local stores, then starts
    /// write-through replication. Safe to call once at launch; a missing
    /// OneDrive downgrades gracefully to local-only.
    /// </summary>
    public void Initialize()
    {
        if (_initialized) return;
        _initialized = true;
        var directory = ReplicaDirectory;
        if (directory is null) return;

        Task.Run(() =>
        {
            try
            {
                MergeReplicaIntoLocal(directory);
            }
            catch
            {
                // A broken replica must never block launch; write-through
                // below will repair it from local state.
            }
            _userMedia.Changed += (_, _) => ScheduleWrite();
            _watchProgress.Changed += (_, _) => ScheduleWrite();
            ScheduleWrite();
        });
    }

    private void MergeReplicaIntoLocal(string directory)
    {
        var replicaUserMedia = ReadList<UserMediaRecord>(Path.Combine(directory, "user-media.json"));
        var replicaProgress = ReadList<WatchProgress>(Path.Combine(directory, "watch-progress.json"));
        if (replicaUserMedia.Count == 0 && replicaProgress.Count == 0) return;

        var mergedUserMedia = WindowsCore.MergeUserMedia(_userMedia.All, replicaUserMedia);
        var mergedProgress = WindowsCore.MergeWatchProgress(_watchProgress.All, replicaProgress);
        _userMedia.ReplaceAll(mergedUserMedia);
        _watchProgress.ReplaceAll(mergedProgress);
    }

    /// <summary>Debounced so bursts (scrubbing, sync results) coalesce into one write.</summary>
    private void ScheduleWrite()
    {
        lock (_gate)
        {
            _writeDebounce?.Dispose();
            _writeDebounce = new Timer(_ => WriteReplica(), null, TimeSpan.FromSeconds(2), Timeout.InfiniteTimeSpan);
        }
    }

    private void WriteReplica()
    {
        var directory = ReplicaDirectory;
        if (directory is null) return;
        try
        {
            Directory.CreateDirectory(directory);
            WriteAtomic(Path.Combine(directory, "user-media.json"), _userMedia.All);
            WriteAtomic(Path.Combine(directory, "watch-progress.json"), _watchProgress.All);
        }
        catch
        {
            // OneDrive may be paused or the folder locked; the next change retries.
        }
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

    private static void WriteAtomic<T>(string path, T payload)
    {
        var temporary = path + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(payload, JsonOptions));
        File.Move(temporary, path, overwrite: true);
    }
}
