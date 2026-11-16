// The rules of the library's TMDB sweep (apple-27.1 94a8b4f,
// LibraryController.unmatchedRetryInterval, isDue, and haltsEnrichment): when
// a title TMDB had no match for is asked about again, and which failed
// lookups end a sweep instead of counting as "no match". The sweep itself is
// Services/MetadataSweep.cs.

namespace Edendale.Windows.Core;

public static class LibraryEnrichment
{
    /// <summary>
    /// A title TMDB had no match for is asked about again after this long by
    /// the automatic sweep; a forced rescan (Ctrl+R, F5, a source's Rescan)
    /// asks at once.
    /// </summary>
    public static readonly TimeSpan UnmatchedRetryInterval = TimeSpan.FromDays(7);

    /// <summary>
    /// Whether a title without a TMDB match is looked up now: one never
    /// marked unmatched always is, one marked is after
    /// <see cref="UnmatchedRetryInterval"/>, and a forced rescan
    /// (<paramref name="retryingUnmatched"/>) asks about every one.
    /// </summary>
    public static bool IsDue(DateTimeOffset? checkedAt, DateTimeOffset now, bool retryingUnmatched) =>
        checkedAt is not { } at || retryingUnmatched || now - at >= UnmatchedRetryInterval;

    /// <summary>
    /// Whether a failed lookup means TMDB can't be reached or refuses the
    /// request, so every lookup after it would fail the same way: the sweep
    /// ends and marks nothing, and the next one resumes there. Anything else
    /// (a 404, another refusal of this one request, a payload that isn't
    /// JSON, episode numbers TMDB can't hold such as S01E00) is an answer
    /// without a usable match.
    /// </summary>
    public static bool HaltsEnrichment(Exception error) => error switch
    {
        // Cancelled, or HttpClient's timeout (a TaskCanceledException).
        OperationCanceledException => true,
        HttpRequestException { StatusCode: { } status } => HaltsOnStatus((int)status),
        // DNS, a refused or reset connection, TLS: Apple's URLError.
        HttpRequestException => true,
        // The connection dropped while the answer was read (HttpIOException).
        IOException => true,
        WindowsCoreException { Failure: TmdbFailure.MissingCredentials } => true,
        WindowsCoreException { Failure: TmdbFailure.HttpStatus, StatusCode: int status } => HaltsOnStatus(status),
        _ => false,
    };

    /// <summary>A refused credential (401, 403), a rate limit (429), or TMDB itself failing (5xx).</summary>
    private static bool HaltsOnStatus(int status) => status is 401 or 403 or 429 or >= 500;
}
