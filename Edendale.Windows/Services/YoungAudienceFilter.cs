// Persisted PG / PG-13 audience preference plus cached TMDB certification
// verification. List responses carry no certification, so verification is a
// deliberately separate, batched, concurrency-limited lookup — never on the
// import fast path. Decisions fail closed: an unverified or unavailable title
// stays hidden while the preference is on. Keyed by "{mediaType}:{id}" because
// Models.MediaRef is a reference type with no value equality. WinUI-free so the
// domain tests link it and inject a fake provider. Ports YoungAudienceFilter.swift.

using System.Collections.Concurrent;
using System.Globalization;
using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

public enum ContentCertificationResult
{
    Found,
    Unrated,
    Unavailable,
}

public readonly record struct ContentCertificationLookup(ContentCertificationResult Result, string? Certification)
{
    public static ContentCertificationLookup Found(string certification) => new(ContentCertificationResult.Found, certification);
    public static readonly ContentCertificationLookup Unrated = new(ContentCertificationResult.Unrated, null);
    public static readonly ContentCertificationLookup Unavailable = new(ContentCertificationResult.Unavailable, null);
}

/// <summary>Looks up a title's certification. The default talks to TMDB; tests fake it.</summary>
public interface IContentCertificationProvider
{
    /// <summary>Region the decisions apply to; a change invalidates the cache.</summary>
    string ContextIdentifier { get; }

    Task<ContentCertificationLookup> CertificationAsync(MediaRef reference);
}

public sealed class YoungAudienceFilter
{
    private const string PreferenceKey = "youngAudienceFriendly";

    private enum Decision
    {
        Allowed,
        Blocked,
        /// <summary>Network/auth failure: fails closed but can be retried later.</summary>
        Unavailable,
    }

    private readonly IContentCertificationProvider _provider;
    private readonly string _preferencePath;
    private readonly object _gate = new();
    private readonly Dictionary<string, Decision> _decisions = new(StringComparer.Ordinal);
    private string _contextIdentifier;
    private bool _isEnabled;

    /// <summary>The preference toggled, or a batch of decisions resolved.</summary>
    public event EventHandler? Changed;

    public YoungAudienceFilter() : this(new TmdbContentCertificationProvider()) { }

    /// <param name="preferencePath">Preference JSON path; defaults to the app data file.</param>
    public YoungAudienceFilter(IContentCertificationProvider provider, string? preferencePath = null)
    {
        _provider = provider;
        _preferencePath = preferencePath ?? AppPaths.AudiencePreferenceFile;
        _contextIdentifier = provider.ContextIdentifier;
        _isEnabled = LoadPreference();
    }

    public bool IsEnabled
    {
        get => _isEnabled;
        set
        {
            if (_isEnabled == value) return;
            _isEnabled = value;
            SavePreference(value);
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    public string ContextIdentifier
    {
        get
        {
            Synchronize();
            lock (_gate) return _contextIdentifier;
        }
    }

    /// <summary>Turning the preference off is a synchronous, zero-network bypass.</summary>
    public bool Allows(MediaRef reference)
    {
        Synchronize();
        if (!_isEnabled) return true;
        lock (_gate) return _decisions.TryGetValue(Key(reference), out var decision) && decision == Decision.Allowed;
    }

    /// <summary>Preserves source ordering; returns the list verbatim when off.</summary>
    public List<MediaItem> Visible(IEnumerable<MediaItem> items)
    {
        Synchronize();
        var list = items as IReadOnlyList<MediaItem> ?? items.ToList();
        if (!_isEnabled) return [.. list];
        lock (_gate)
        {
            return [.. list.Where(item =>
                _decisions.TryGetValue(Key(item.Ref), out var decision) && decision == Decision.Allowed)];
        }
    }

    /// <summary>Unknown refs count as verifying, keeping the UI fail-closed.</summary>
    public bool IsVerifying(IEnumerable<MediaRef> refs)
    {
        Synchronize();
        if (!_isEnabled) return false;
        lock (_gate) return refs.Select(Key).Distinct().Any(key => !_decisions.ContainsKey(key));
    }

    /// <summary>Resolves every requested ref before returning. Overlapping callers
    /// are coalesced by the provider's cache and in-flight table.</summary>
    public async Task VerifyAsync(IEnumerable<MediaRef> refs)
    {
        Synchronize();
        if (!_isEnabled) return;

        var unresolved = new Dictionary<string, MediaRef>(StringComparer.Ordinal);
        lock (_gate)
        {
            foreach (var reference in refs)
            {
                var key = Key(reference);
                if (!_decisions.TryGetValue(key, out var decision) || decision == Decision.Unavailable)
                {
                    unresolved[key] = reference;
                }
            }
        }
        if (unresolved.Count == 0) return;

        var results = await Task.WhenAll(unresolved.Values.Select(async reference =>
            (Reference: reference, Lookup: await _provider.CertificationAsync(reference))));

        lock (_gate)
        {
            foreach (var (reference, lookup) in results)
            {
                _decisions[Key(reference)] = lookup.Result switch
                {
                    ContentCertificationResult.Found =>
                        YoungAudienceCertificationPolicy.Allows(lookup.Certification, reference.MediaType)
                            ? Decision.Allowed
                            : Decision.Blocked,
                    ContentCertificationResult.Unrated => Decision.Blocked,
                    _ => Decision.Unavailable,
                };
            }
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private void Synchronize()
    {
        var current = _provider.ContextIdentifier;
        lock (_gate)
        {
            if (current == _contextIdentifier) return;
            _contextIdentifier = current;
            _decisions.Clear();
        }
    }

    private static string Key(MediaRef reference) => $"{reference.MediaType}:{reference.Id}";

    private bool LoadPreference()
    {
        try
        {
            if (!File.Exists(_preferencePath)) return false;
            using var document = JsonDocument.Parse(File.ReadAllText(_preferencePath));
            return document.RootElement.Bool(PreferenceKey) ?? false;
        }
        catch
        {
            return false;
        }
    }

    private void SavePreference(bool value)
    {
        try
        {
            var temporary = _preferencePath + ".tmp";
            File.WriteAllText(
                temporary,
                JsonSerializer.Serialize(new Dictionary<string, bool> { [PreferenceKey] = value }));
            File.Move(temporary, _preferencePath, overwrite: true);
        }
        catch
        {
            // Failing to persist only means the preference resets next launch.
        }
    }
}

/// <summary>
/// Production certification provider. Certifications come from the public
/// /release_dates and /content_ratings endpoints — no account session required —
/// so this stays independent of the TMDB account service. Results are cached per
/// region and concurrent requests are gated and de-duplicated.
/// </summary>
public sealed class TmdbContentCertificationProvider : IContentCertificationProvider
{
    private readonly SemaphoreSlim _gate = new(8, 8);
    private readonly ConcurrentDictionary<string, ContentCertificationLookup> _cache = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, Task<ContentCertificationLookup>> _inFlight = new(StringComparer.Ordinal);

    public string ContextIdentifier
    {
        get
        {
            try
            {
                return RegionInfo.CurrentRegion.TwoLetterISORegionName.ToUpperInvariant();
            }
            catch
            {
                return "";
            }
        }
    }

    public Task<ContentCertificationLookup> CertificationAsync(MediaRef reference)
    {
        var region = ContextIdentifier;
        if (string.IsNullOrEmpty(region)) return Task.FromResult(ContentCertificationLookup.Unrated);

        var key = $"{region}:{reference.MediaType}:{reference.Id}";
        if (_cache.TryGetValue(key, out var cached)) return Task.FromResult(cached);
        return _inFlight.GetOrAdd(key, _ => FetchAsync(key, reference, region));
    }

    private async Task<ContentCertificationLookup> FetchAsync(string key, MediaRef reference, string region)
    {
        try
        {
            await _gate.WaitAsync();
            try
            {
                if (_cache.TryGetValue(key, out var cached)) return cached;

                ContentCertificationLookup result;
                try
                {
                    var certification = await WindowsCore.ContentCertificationAsync(
                        reference.Id, reference.MediaType, region);
                    result = certification is null
                        ? ContentCertificationLookup.Unrated
                        : ContentCertificationLookup.Found(certification);
                }
                catch
                {
                    result = ContentCertificationLookup.Unavailable;
                }

                // Auth/transient failures may recover later, so are not cached.
                if (result.Result != ContentCertificationResult.Unavailable) _cache[key] = result;
                return result;
            }
            finally
            {
                _gate.Release();
            }
        }
        finally
        {
            _inFlight.TryRemove(key, out _);
        }
    }
}
