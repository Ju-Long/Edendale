namespace Edendale.Windows.Core;

/// <summary>
/// Runs a pass when asked, one pass at a time, on a task of its own, without
/// dropping a request: any number of requests made while a pass runs yield
/// exactly one more pass after it, and a flag any of them set reaches that
/// pass. A failed pass doesn't stop the passes asked for after it. The
/// library's TMDB sweep runs through this (apple-27.1
/// LibraryController.enrichPendingMetadata and runEnrichmentPasses,
/// android-27.1 CoalescingRunner.kt).
/// </summary>
public sealed class CoalescingRunner
{
    private readonly object _gate = new();
    private readonly Func<bool, Task> _pass;
    private readonly Func<bool, bool> _hasWork;
    private volatile Task? _running;
    private bool _queued;
    private bool _flag;

    /// <param name="pass">One pass, given the merged flag of the requests it answers.</param>
    /// <param name="hasWork">
    /// Whether a pass asked for with this flag would do anything. A request
    /// while nothing runs and nothing is to be done starts no pass, so
    /// IsRunning doesn't flicker; a request while a pass runs always queues
    /// one, which then sees what changed meanwhile. Called under the runner's
    /// lock: it must not wait on anything that calls back into this runner.
    /// </param>
    public CoalescingRunner(Func<bool, Task> pass, Func<bool, bool>? hasWork = null)
    {
        _pass = pass;
        _hasWork = hasWork ?? (_ => true);
    }

    /// <summary>
    /// A run started or ended. Raised outside the lock, on the thread that
    /// started the run or on the run's own; read <see cref="IsRunning"/> for
    /// the state.
    /// </summary>
    public event EventHandler? RunningChanged;

    /// <summary>Whether passes are running or queued.</summary>
    public bool IsRunning => _running is not null;

    /// <summary>
    /// Asks for a pass. Returns the running task, which ends once every pass
    /// asked for so far has run, or a completed task when there was nothing
    /// to do.
    /// </summary>
    public Task Request(bool flag = false)
    {
        Task started;
        lock (_gate)
        {
            if (_running is { } running)
            {
                _queued = true;
                _flag |= flag;
                return running;
            }
            if (!HasWork(flag)) return Task.CompletedTask;
            _queued = true;
            _flag = flag;
            // RunAsync takes the lock first, so it waits until _running is set.
            started = _running = Task.Run(RunAsync);
        }
        RunningChanged?.Invoke(this, EventArgs.Empty);
        return started;
    }

    private bool HasWork(bool flag)
    {
        try
        {
            return _hasWork(flag);
        }
        catch
        {
            // When the check itself fails, the pass decides.
            return true;
        }
    }

    private async Task RunAsync()
    {
        while (true)
        {
            bool flag;
            lock (_gate)
            {
                if (!_queued)
                {
                    _running = null;
                    break;
                }
                _queued = false;
                flag = _flag;
                _flag = false;
            }
            try
            {
                await _pass(flag).ConfigureAwait(false);
            }
            catch
            {
                // A failed pass doesn't stop the passes asked for after it.
            }
        }
        RunningChanged?.Invoke(this, EventArgs.Empty);
    }
}
