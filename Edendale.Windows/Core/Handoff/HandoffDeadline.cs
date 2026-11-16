// Deadlines that end a handoff wait even when the network never answers
// (HandoffV2Session.swift `withDeadline`, ENHANCEMENT.md K.7 on apple-27.1).
// A cancellation token only ends a read the stream honours it in: a WinRT
// socket stream may sit in a read the token never reaches. So when the
// timeout passes first, `expire` closes whatever the work waits on (the
// stream), and the wait ends as a timeout whatever the work then throws.
// Whichever of the two finishes first claims the outcome, so a read that
// completes as the deadline passes is never disposed under its caller.

namespace Edendale.Windows.Core.Handoff;

public static class HandoffDeadline
{
    /// <summary>
    /// Runs <paramref name="work"/> with a token that is cancelled after
    /// <paramref name="timeout"/> or with <paramref name="cancellation"/>. If
    /// the timeout passes before the work ends, <paramref name="expire"/>
    /// runs once and the call throws <see cref="HandoffError.Timeout"/>. A
    /// cancellation by the caller ends as an OperationCanceledException,
    /// whatever the work threw; any other failure is rethrown as is.
    /// </summary>
    public static async Task<T> RunAsync<T>(TimeSpan timeout, Action expire, Func<CancellationToken, Task<T>> work, CancellationToken cancellation)
    {
        var claim = new FirstClaim();
        using var deadline = new CancellationTokenSource();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellation, deadline.Token);
        using var watchdog = deadline.Token.Register(() =>
        {
            if (!claim.Claim()) return;
            try
            {
                expire();
            }
            catch (Exception)
            {
                // Closing a stream a read is finishing on can throw; the wait ends either way.
            }
        });
        deadline.CancelAfter(timeout);
        try
        {
            var value = await work(linked.Token).ConfigureAwait(false);
            if (claim.Claim()) return value;
        }
        catch (Exception) when (cancellation.IsCancellationRequested)
        {
            // The caller gave up; what the abandoned wait threw doesn't matter.
            throw new OperationCanceledException(cancellation);
        }
        catch (Exception) when (deadline.IsCancellationRequested || !claim.Claim())
        {
            // The deadline passed: the work failed because its wait was ended.
        }
        throw new HandoffException(HandoffError.Timeout);
    }

    /// <summary>Whichever of two racing paths claims first wins; the other learns it lost.</summary>
    private sealed class FirstClaim
    {
        private int _claimed;

        public bool Claim() => Interlocked.Exchange(ref _claimed, 1) == 0;
    }
}
