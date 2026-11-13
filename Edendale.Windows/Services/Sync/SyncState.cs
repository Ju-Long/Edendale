// The device-local sync state (FEATURES-27.1.md §5, SYNC-PROTOCOL.md §3, §4,
// §8): this device's sync ID and name, the one sync location and space, the
// high-water stamp, the stamps of the other devices' files last read, and the
// devices seen. It lives in %LOCALAPPDATA%\Edendale\sync-state.json, outside
// every synced folder and OneDrive's reach, because a device ID restored onto
// another PC would break the tie-break (§5.5). Logins are never in here: a
// cloud location names its account key, a server location its saved login's
// host, and the credentials stay in their DPAPI stores.

using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.Json.Serialization;
using Edendale.Windows.Core;
using Edendale.Windows.Core.Sync;

namespace Edendale.Windows.Services.Sync;

/// <summary>Where the sync files live (§4).</summary>
public enum SyncLocationKind
{
    /// <summary>A picked folder: a plain one, or the OneDrive or iCloud for Windows folder on this PC.</summary>
    Folder,
    /// <summary>An SMB share, as a UNC path, with the saved SMB login.</summary>
    Smb,
    GoogleDrive,
    OneDrive,
    Dropbox,
    WebDav,
    Sftp,
}

/// <summary>The chosen sync location and space, without credentials.</summary>
public sealed record SyncLocationSettings
{
    public required SyncLocationKind Kind { get; init; }

    /// <summary>§3: the space folder's name.</summary>
    public required string Space { get; init; }

    /// <summary>Folder and SMB: the root folder. WebDAV and SFTP: the canonical base URL (davs://…/, sftp://…/).</summary>
    public string? Path { get; init; }

    /// <summary>Folder: the future-access-list token for the picked folder, when the app has package identity.</summary>
    public string? FutureAccessToken { get; init; }

    /// <summary>Cloud providers: the account key the login is stored under (never a token).</summary>
    public string? AccountKey { get; init; }

    /// <summary>Google Drive: the space folder's Drive id, once created or received through a handoff (§9.6).</summary>
    public string? GoogleFolderId { get; init; }

    /// <summary>Google Drive: the Edendale root folder's id, once created.</summary>
    public string? GoogleRootFolderId { get; init; }

    /// <summary>
    /// The `provider` value of the handoff `offer` (§9.6), spelled as the
    /// Apple and Android branches send it: `folder`, `googleDrive`,
    /// `oneDrive`, `dropbox`, `webdav`, `smb`, `sftp`.
    /// </summary>
    [JsonIgnore]
    public string ProviderName => ProviderNameOf(Kind);

    public static string ProviderNameOf(SyncLocationKind kind) => kind switch
    {
        SyncLocationKind.Smb => "smb",
        SyncLocationKind.GoogleDrive => "googleDrive",
        SyncLocationKind.OneDrive => "oneDrive",
        SyncLocationKind.Dropbox => "dropbox",
        SyncLocationKind.WebDav => "webdav",
        SyncLocationKind.Sftp => "sftp",
        _ => "folder",
    };

    /// <summary>
    /// The kind for a handoff `provider` value, or null for one this build
    /// does not know. Apple's `icloudDrive` is a folder here: Windows reaches
    /// iCloud Drive through the iCloud for Windows folder.
    /// </summary>
    public static SyncLocationKind? KindFromProvider(string? provider) => provider switch
    {
        "folder" or "icloudDrive" => SyncLocationKind.Folder,
        "smb" => SyncLocationKind.Smb,
        "googleDrive" => SyncLocationKind.GoogleDrive,
        "oneDrive" => SyncLocationKind.OneDrive,
        "dropbox" => SyncLocationKind.Dropbox,
        "webdav" => SyncLocationKind.WebDav,
        "sftp" => SyncLocationKind.Sftp,
        _ => null,
    };

    /// <summary>The MediaSourceKind behind a provider location; null for a folder.</summary>
    [JsonIgnore]
    public MediaSourceKind? SourceKind => Kind switch
    {
        SyncLocationKind.Smb => MediaSourceKind.Smb,
        SyncLocationKind.GoogleDrive => MediaSourceKind.GoogleDrive,
        SyncLocationKind.OneDrive => MediaSourceKind.OneDrive,
        SyncLocationKind.Dropbox => MediaSourceKind.Dropbox,
        SyncLocationKind.WebDav => MediaSourceKind.WebDav,
        SyncLocationKind.Sftp => MediaSourceKind.Sftp,
        _ => null,
    };
}

/// <summary>Another device whose file this device has read (§8.4 "for the UI").</summary>
public sealed record SyncPeer
{
    public required string Id { get; init; }
    public string Name { get; init; } = "";
    public string Platform { get; init; } = "";
    public long FileUpdateTime { get; init; }
    public long LastReadMillis { get; init; }

    /// <summary>Set when the file was skipped at its last read (§5 "Validation").</summary>
    public SyncSkipReason? Skipped { get; init; }
}

/// <summary>The persisted shape.</summary>
public sealed class SyncStateData
{
    public string? DeviceId { get; set; }
    public string? DeviceName { get; set; }
    public long HighWater { get; set; }
    public SyncLocationSettings? Location { get; set; }
    public Dictionary<string, string> ReadStamps { get; set; } = [];
    public Dictionary<string, SyncPeer> Peers { get; set; } = [];
    public long LastSyncMillis { get; set; }
    public long LastWriteMillis { get; set; }

    /// <summary>The file stamp of the last device file written, so a sync knows whether anything is newer.</summary>
    public long WrittenHighWater { get; set; }

    /// <summary>§8.4: the OneDrive replica of builds before 27.1 was read once.</summary>
    public bool ReplicaImported { get; set; }

    /// <summary>The one-time offer of the OneDrive folder as the default location was shown.</summary>
    public bool DefaultLocationOffered { get; set; }

    /// <summary>
    /// Records stamped more than a day ahead were left out of the last
    /// device file (K.7); the file is written again once the clock passes
    /// this, the earliest one's stamp less the day's allowance. 0 when
    /// nothing was held back.
    /// </summary>
    public long HeldBackUntil { get; set; }

    /// <summary>The watchlist's launch reconcile (K.7) has completed on this PC.</summary>
    public bool WatchlistSeeded { get; set; }
}

public sealed class SyncState
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) },
    };

    private readonly string _path;
    private readonly object _gate = new();
    private readonly SyncStateData _data;
    private int _generation;

    public SyncState(string? path = null)
    {
        _path = path ?? AppPaths.SyncStateFile;
        _data = Load(_path);
    }

    public event EventHandler? Changed;

    /// <summary>§3: made on first use, kept for the life of this installation.</summary>
    public string DeviceId
    {
        get
        {
            lock (_gate)
            {
                if (_data.DeviceId is { Length: 36 } id && SyncFormat.IsDeviceId(id)) return id;
                _data.DeviceId = SyncFormat.NewDeviceId();
                SaveLocked();
                return _data.DeviceId;
            }
        }
    }

    /// <summary>Whether a device ID exists yet, without making one.</summary>
    public bool HasDeviceId
    {
        get { lock (_gate) return _data.DeviceId is { Length: 36 }; }
    }

    /// <summary>The name shown to other devices; the PC's name until the user changes it.</summary>
    public string DeviceName
    {
        get
        {
            lock (_gate)
            {
                var name = _data.DeviceName;
                if (string.IsNullOrWhiteSpace(name)) name = HostName();
                return Truncate(name);
            }
        }
        set
        {
            lock (_gate)
            {
                _data.DeviceName = Truncate(value);
                SaveLocked();
            }
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>
    /// The PC's name as the person set it. MachineName is only the fallback:
    /// it is the NetBIOS name, upper-cased and cut to 15 characters.
    /// </summary>
    private static string HostName()
    {
        try
        {
            var host = Dns.GetHostName();
            if (!string.IsNullOrWhiteSpace(host)) return host;
        }
        catch (SocketException)
        {
            // No name from the network stack; the NetBIOS name will do.
        }
        return Environment.MachineName;
    }

    private static string Truncate(string name)
    {
        name = name.Trim();
        if (name.Length == 0) name = "Windows PC";
        return name.Length <= 64 ? name : name[..64];
    }

    public long HighWater
    {
        get { lock (_gate) return _data.HighWater; }
        set
        {
            lock (_gate)
            {
                if (value <= _data.HighWater) return;
                _data.HighWater = value;
                SaveLocked();
            }
        }
    }

    /// <summary>
    /// Lowers a high-water mark that ran more than a day ahead (K.7), which
    /// <see cref="HighWater"/>'s setter, rising only, can't do.
    /// </summary>
    public void ResetHighWater(long value)
    {
        lock (_gate)
        {
            _data.HighWater = value;
            SaveLocked();
        }
    }

    /// <summary>
    /// Raised by every <see cref="SetLocation"/>, so a sync that began at the
    /// old location records nothing against the new one. Not persisted.
    /// </summary>
    public int Generation
    {
        get { lock (_gate) return _generation; }
    }

    public SyncLocationSettings? Location
    {
        get { lock (_gate) return _data.Location; }
    }

    public bool IsOn => Location is not null;

    /// <summary>
    /// Replaces the location (§4 "One location per device"). The read stamps
    /// and peers of the old location go; the local store is untouched.
    /// </summary>
    public void SetLocation(SyncLocationSettings? location)
    {
        lock (_gate)
        {
            _data.Location = location;
            _data.ReadStamps.Clear();
            _data.Peers.Clear();
            _data.LastSyncMillis = 0;
            _data.LastWriteMillis = 0;
            _data.WrittenHighWater = 0;
            _data.HeldBackUntil = 0;
            _generation++;
            SaveLocked();
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Updates a location's provider ids (Google folder ids) without resetting anything.</summary>
    public void UpdateLocation(SyncLocationSettings location)
    {
        lock (_gate)
        {
            _data.Location = location;
            SaveLocked();
        }
    }

    public string? ReadStamp(string fileName)
    {
        lock (_gate) return _data.ReadStamps.GetValueOrDefault(fileName);
    }

    public void RecordRead(string fileName, string stamp, SyncPeer peer)
    {
        lock (_gate)
        {
            _data.ReadStamps[fileName] = stamp;
            _data.Peers[peer.Id] = peer;
            SaveLocked();
        }
    }

    public void ForgetPeer(string deviceId)
    {
        lock (_gate)
        {
            _data.ReadStamps.Remove(SyncFormat.FileName(deviceId));
            _data.Peers.Remove(deviceId);
            SaveLocked();
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>
    /// §8.2 and §8.3 after a listing: the read stamps of files no longer
    /// listed go, so a file that comes back is read again, and, when the
    /// listing isn't empty, so do the devices whose file is gone (an empty
    /// listing clears nothing). Returns whether anything went.
    /// </summary>
    public bool ForgetMissing(IReadOnlyCollection<string> listedNames)
    {
        var removed = false;
        lock (_gate)
        {
            var listed = new HashSet<string>(listedNames, StringComparer.Ordinal);
            foreach (var name in _data.ReadStamps.Keys.Where(name => !listed.Contains(name)).ToList())
            {
                removed |= _data.ReadStamps.Remove(name);
            }
            if (listed.Count > 0)
            {
                foreach (var id in _data.Peers.Keys.Where(id => !listed.Contains(SyncFormat.FileName(id))).ToList())
                {
                    removed |= _data.Peers.Remove(id);
                }
            }
            if (removed) SaveLocked();
        }
        if (removed) Changed?.Invoke(this, EventArgs.Empty);
        return removed;
    }

    public IReadOnlyList<SyncPeer> Peers
    {
        get { lock (_gate) return [.. _data.Peers.Values.OrderBy(peer => peer.Name, StringComparer.CurrentCultureIgnoreCase)]; }
    }

    public long LastSyncMillis
    {
        get { lock (_gate) return _data.LastSyncMillis; }
    }

    public long LastWriteMillis
    {
        get { lock (_gate) return _data.LastWriteMillis; }
    }

    public long WrittenHighWater
    {
        get { lock (_gate) return _data.WrittenHighWater; }
        set
        {
            lock (_gate)
            {
                _data.WrittenHighWater = value;
                SaveLocked();
            }
        }
    }

    public void RecordSync(long now, bool wrote)
    {
        lock (_gate)
        {
            _data.LastSyncMillis = now;
            if (wrote) _data.LastWriteMillis = now;
            SaveLocked();
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public bool ReplicaImported
    {
        get { lock (_gate) return _data.ReplicaImported; }
        set
        {
            lock (_gate)
            {
                _data.ReplicaImported = value;
                SaveLocked();
            }
        }
    }

    public bool DefaultLocationOffered
    {
        get { lock (_gate) return _data.DefaultLocationOffered; }
        set
        {
            lock (_gate)
            {
                _data.DefaultLocationOffered = value;
                SaveLocked();
            }
        }
    }

    /// <summary>See <see cref="SyncStateData.HeldBackUntil"/>.</summary>
    public long HeldBackUntil
    {
        get { lock (_gate) return _data.HeldBackUntil; }
        set
        {
            lock (_gate)
            {
                if (_data.HeldBackUntil == value) return;
                _data.HeldBackUntil = value;
                SaveLocked();
            }
        }
    }

    /// <summary>See <see cref="SyncStateData.WatchlistSeeded"/>.</summary>
    public bool WatchlistSeeded
    {
        get { lock (_gate) return _data.WatchlistSeeded; }
        set
        {
            lock (_gate)
            {
                _data.WatchlistSeeded = value;
                SaveLocked();
            }
        }
    }

    private static SyncStateData Load(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                return JsonSerializer.Deserialize<SyncStateData>(File.ReadAllText(path), JsonOptions) ?? new SyncStateData();
            }
        }
        catch
        {
            // A corrupt state starts fresh: a new device ID and no location.
        }
        return new SyncStateData();
    }

    private void SaveLocked()
    {
        try
        {
            var temporary = _path + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(_data, JsonOptions));
            File.Move(temporary, _path, overwrite: true);
        }
        catch
        {
            // The state stays usable in memory for this session.
        }
    }
}
