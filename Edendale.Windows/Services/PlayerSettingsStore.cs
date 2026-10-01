// One device-local store for every player preference introduced in 27.0:
// App Controls, Loop and Fit/Fill, skip prompts, audio enhancement, picture
// adjustments, subtitle appearance, video enhancement, and the per-title
// track memory. Keys are Apple's (`player.skipBackwardSeconds`, …) so the two
// platforms describe the same settings the same way, but nothing is shared or
// synced: the file lives in %LOCALAPPDATA%\Edendale and never enters the
// OneDrive replica. The typed rules that interpret each key (snapping,
// clamping, defaults) live with their feature in Core/; this class only loads,
// answers, and writes values, falling back whenever a value is missing or of
// the wrong shape.

using System.Text.Json;
using System.Text.Json.Nodes;

namespace Edendale.Windows.Services;

public sealed class PlayerSettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly string _path;
    private readonly object _gate = new();
    private readonly JsonObject _values;

    /// <summary>
    /// Raised after a value changes, with the key that changed. The player
    /// reads preferences at each gesture, so most listeners only need this to
    /// refresh labels; nothing has to restart playback.
    /// </summary>
    public event EventHandler<string>? Changed;

    /// <param name="path">The JSON file; defaults to <see cref="AppPaths.PlayerSettingsFile"/>.</param>
    public PlayerSettingsStore(string? path = null)
    {
        _path = path ?? AppPaths.PlayerSettingsFile;
        _values = Load(_path);
    }

    /// <summary>An unreadable or malformed file starts empty, so every key reads its default.</summary>
    private static JsonObject Load(string path)
    {
        try
        {
            if (File.Exists(path) && JsonNode.Parse(File.ReadAllText(path)) is JsonObject stored)
            {
                return stored;
            }
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
            // Preferences are convenience data; a damaged file never blocks playback.
        }
        return [];
    }

    public bool Contains(string key)
    {
        lock (_gate) return _values.ContainsKey(key);
    }

    public bool GetBool(string key, bool fallback)
    {
        lock (_gate)
        {
            return _values[key] is JsonValue value && value.TryGetValue(out bool result) ? result : fallback;
        }
    }

    /// <summary>The stored number, or null when it is missing or not a number.</summary>
    public double? GetDouble(string key)
    {
        lock (_gate)
        {
            return _values[key] is JsonValue value && value.TryGetValue(out double result) ? result : null;
        }
    }

    /// <summary>The stored whole number, or null when it is missing, fractional, or not a number.</summary>
    public int? GetInt(string key)
    {
        lock (_gate)
        {
            if (_values[key] is not JsonValue value) return null;
            if (value.TryGetValue(out int whole)) return whole;
            return value.TryGetValue(out double number) && number == Math.Floor(number)
                && number is >= int.MinValue and <= int.MaxValue
                ? (int)number
                : null;
        }
    }

    public string? GetString(string key)
    {
        lock (_gate)
        {
            return _values[key] is JsonValue value && value.TryGetValue(out string? result) ? result : null;
        }
    }

    /// <summary>The stored numbers, or null when the value is not an array of numbers.</summary>
    public double[]? GetDoubleArray(string key)
    {
        lock (_gate)
        {
            if (_values[key] is not JsonArray array) return null;
            var result = new double[array.Count];
            for (var index = 0; index < array.Count; index++)
            {
                if (array[index] is not JsonValue item || !item.TryGetValue(out double number)) return null;
                result[index] = number;
            }
            return result;
        }
    }

    /// <summary>A stored object decoded as <typeparamref name="T"/>, or null when it does not decode.</summary>
    public T? GetObject<T>(string key) where T : class
    {
        lock (_gate)
        {
            if (_values[key] is not JsonObject node) return null;
            try
            {
                return node.Deserialize<T>(JsonOptions);
            }
            catch (Exception error) when (error is JsonException or NotSupportedException or InvalidOperationException)
            {
                return null;
            }
        }
    }

    public void SetBool(string key, bool value) => Set(key, JsonValue.Create(value));

    public void SetDouble(string key, double value)
    {
        // JSON has no NaN or infinity; a non-finite value is simply not stored,
        // so the reader falls back to its default.
        if (!double.IsFinite(value))
        {
            Remove(key);
            return;
        }
        Set(key, JsonValue.Create(value));
    }

    public void SetInt(string key, int value) => Set(key, JsonValue.Create(value));

    public void SetString(string key, string value) => Set(key, JsonValue.Create(value));

    public void SetDoubleArray(string key, IEnumerable<double> values) =>
        Set(key, new JsonArray([.. values.Select(value => (JsonNode?)JsonValue.Create(double.IsFinite(value) ? value : 0))]));

    public void SetObject<T>(string key, T value) where T : class =>
        Set(key, JsonSerializer.SerializeToNode(value, JsonOptions));

    public void Remove(string key)
    {
        bool removed;
        lock (_gate) removed = _values.Remove(key);
        if (!removed) return;
        Save();
        Changed?.Invoke(this, key);
    }

    private void Set(string key, JsonNode? value)
    {
        lock (_gate)
        {
            if (JsonNode.DeepEquals(_values[key], value) && _values.ContainsKey(key)) return;
            _values[key] = value;
        }
        Save();
        Changed?.Invoke(this, key);
    }

    /// <summary>Writes through a sibling temporary file and a rename, so a crash never leaves half a file.</summary>
    private void Save()
    {
        try
        {
            string json;
            lock (_gate) json = _values.ToJsonString(JsonOptions);
            var directory = Path.GetDirectoryName(_path);
            if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
            var temporary = _path + ".tmp";
            File.WriteAllText(temporary, json);
            File.Move(temporary, _path, overwrite: true);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            // A failed write keeps the in-memory value for this session.
        }
    }
}
