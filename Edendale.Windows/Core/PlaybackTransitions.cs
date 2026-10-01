namespace Edendale.Windows.Core;

/// <summary>A queued automatic advance, valid only for the presentation that requested it.</summary>
public readonly record struct AdvanceTicket(long Generation);

/// <summary>
/// Orders what the player presents (DIFF.md §3.4): a newer manual play
/// request cancels a pending automatic advance, closing the player cancels it
/// too, and duplicate advance requests from one ending move on only once.
/// It also keeps a completed item from being rewritten by a late progress
/// write once the player has moved on. Ports the rules PlayerSessionTransitionTests
/// covers; the player calls it from the UI thread.
/// </summary>
public sealed class PlaybackTransitions
{
    private long _generation;

    /// <summary>Incremented by every presentation and by closing the player.</summary>
    public long Generation => _generation;

    /// <summary>True once the current item was marked complete; its progress is final.</summary>
    public bool CurrentCompleted { get; private set; }

    /// <summary>Whether a periodic or closing progress write may still touch the current item.</summary>
    public bool ShouldWriteProgress => !CurrentCompleted;

    /// <summary>A new item is presented, whether chosen by the reader or advanced to.</summary>
    public long Present()
    {
        CurrentCompleted = false;
        return ++_generation;
    }

    /// <summary>The player closed; any queued advance is void.</summary>
    public void End()
    {
        CurrentCompleted = false;
        _generation++;
    }

    /// <summary>The current item reached its natural end or a terminal credits skip.</summary>
    public void MarkCurrentCompleted() => CurrentCompleted = true;

    /// <summary>Queues an advance for the item presented now.</summary>
    public AdvanceTicket RequestAdvance() => new(_generation);

    /// <summary>
    /// True for the first claim of a ticket still belonging to the current
    /// presentation. The caller then presents the next item (or loops or
    /// closes); later duplicate claims and claims made stale by a manual
    /// request or a close return false.
    /// </summary>
    public bool Claim(AdvanceTicket ticket)
    {
        if (ticket.Generation != _generation) return false;
        _generation++;
        return true;
    }
}
