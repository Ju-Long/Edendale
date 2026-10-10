// The skip-prompt state for the item on screen (DIFF.md §3.5), ported from
// Apple's PlayerSegmentController: the lookup starts once the duration is
// known and the setting is on, never more than once per item, and its answer
// is dropped if the item, the setting, or the session changed meanwhile.
// Results live in memory only (12 entries, cleared on close or when the
// setting is turned off). Playback never waits for the lookup, and a prompt
// never skips by itself. UI-thread only.

using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

/// <summary>What pressing the prompt does: seek to the end of the range, or finish the item.</summary>
public abstract record SegmentSkipAction
{
    public sealed record Seek(long TargetMilliseconds) : SegmentSkipAction;

    public sealed record Finish : SegmentSkipAction;
}

public sealed class SegmentPrompts
{
    public const int CacheCapacity = 12;

    public delegate Task<IReadOnlyList<MediaSegment>> Lookup(IntroDbRequest request, CancellationToken cancellation);

    private readonly PlayerSettingsStore _store;
    private readonly Lookup _lookup;
    private readonly Dictionary<IntroDbRequest, IReadOnlyList<MediaSegment>> _cache = [];
    private readonly LinkedList<IntroDbRequest> _cacheOrder = new();

    private long _generation;
    private IntroDbMedia? _media;
    private bool _lookupAttempted;
    private CancellationTokenSource? _work;
    private MediaSegment? _suppressed;

    /// <summary>Raised when segments arrive or the active segment changes.</summary>
    public event EventHandler? Changed;

    public SegmentPrompts(PlayerSettingsStore store, Lookup lookup)
    {
        _store = store;
        _lookup = lookup;
        _store.Changed += (_, key) =>
        {
            if (key == PlayerPreferences.SegmentPromptsEnabledKey && !IsEnabled) Disable();
        };
    }

    /// <summary>Opt-in, off by default; mirrors <see cref="PlayerPreferences.SegmentPromptsEnabled"/>.</summary>
    public bool IsEnabled
    {
        get => _store.GetBool(PlayerPreferences.SegmentPromptsEnabledKey, fallback: false);
        set => _store.SetBool(PlayerPreferences.SegmentPromptsEnabledKey, value);
    }

    public IReadOnlyList<MediaSegment> Segments { get; private set; } = [];

    /// <summary>The range the prompt offers now, or null.</summary>
    public MediaSegment? ActiveSegment { get; private set; }

    public bool IsLoading => _work is not null;

    /// <summary>A new item is on screen. <paramref name="media"/> is null for an unidentified file.</summary>
    public void Begin(IntroDbMedia? media)
    {
        CancelWork();
        _generation++;
        _media = media;
        _lookupAttempted = false;
        _suppressed = null;
        Segments = [];
        SetActive(null);
    }

    /// <summary>The session ended: nothing in flight survives, and the cache is cleared.</summary>
    public void End()
    {
        Begin(null);
        ClearCache();
    }

    /// <summary>
    /// A playback tick. Starts the lookup once there is a usable duration;
    /// recomputes the active range, keeping a pressed prompt hidden until
    /// playback leaves that range.
    /// </summary>
    public void Update(long timeMilliseconds, long? durationMilliseconds, bool isSeekable)
    {
        if (!IsEnabled)
        {
            SetActive(null);
            return;
        }

        if (!_lookupAttempted && _media is { } media && durationMilliseconds is long duration
            && IntroDbRequest.Create(media, duration / 1000.0) is { } request)
        {
            _lookupAttempted = true;
            StartLookup(request);
        }

        if (_suppressed is { } suppressed && !suppressed.Contains(timeMilliseconds)) _suppressed = null;

        var current = isSeekable
            ? Segments.FirstOrDefault(segment => segment.Contains(timeMilliseconds))
            : null;
        SetActive(current is not null && current != _suppressed ? current : null);
    }

    /// <summary>
    /// Re-checks the time and returns what the press should do, then hides the
    /// prompt until playback leaves the range. Null when nothing applies.
    /// </summary>
    public SegmentSkipAction? ConsumeSkip(long timeMilliseconds, long? durationMilliseconds, bool isSeekable)
    {
        if (!IsEnabled || !isSeekable || durationMilliseconds is null) return null;
        var segment = Segments.FirstOrDefault(candidate => candidate.Contains(timeMilliseconds));
        if (segment is null || segment == _suppressed) return null;

        _suppressed = segment;
        SetActive(null);
        return segment.ReachesEnd
            ? new SegmentSkipAction.Finish()
            : new SegmentSkipAction.Seek(segment.EndMilliseconds);
    }

    private async void StartLookup(IntroDbRequest request)
    {
        if (_cache.TryGetValue(request, out var cached))
        {
            Segments = cached;
            Changed?.Invoke(this, EventArgs.Empty);
            return;
        }

        var generation = _generation;
        var work = new CancellationTokenSource();
        _work = work;
        try
        {
            var segments = await _lookup(request, work.Token);
            if (generation != _generation || !IsEnabled || work.IsCancellationRequested) return;
            Remember(request, segments);
            Segments = segments;
            Changed?.Invoke(this, EventArgs.Empty);
        }
        catch (Exception error) when (error is OperationCanceledException or IntroDbException or HttpRequestException or TaskCanceledException)
        {
            // A failed lookup leaves no prompts and isn't retried for this item.
        }
        finally
        {
            if (ReferenceEquals(_work, work)) _work = null;
            work.Dispose();
        }
    }

    private void Remember(IntroDbRequest request, IReadOnlyList<MediaSegment> segments)
    {
        _cache[request] = segments;
        _cacheOrder.AddLast(request);
        while (_cacheOrder.Count > CacheCapacity)
        {
            _cache.Remove(_cacheOrder.First!.Value);
            _cacheOrder.RemoveFirst();
        }
    }

    private void Disable()
    {
        CancelWork();
        _generation++;
        _lookupAttempted = false;
        Segments = [];
        SetActive(null);
        ClearCache();
    }

    private void ClearCache()
    {
        _cache.Clear();
        _cacheOrder.Clear();
    }

    private void CancelWork()
    {
        _work?.Cancel();
        _work = null;
    }

    private void SetActive(MediaSegment? segment)
    {
        if (segment == ActiveSegment) return;
        ActiveSegment = segment;
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
