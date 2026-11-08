package com.babasama.edendale.parental

/**
 * Parental controls (ENHANCEMENT.md Section O; FEATURES-27.1.md §4). Pure
 * Kotlin: the rules, the PIN hash, and the back-off live here with no Android
 * import, so the hermetic JVM suite reaches every decision. Everything in this
 * package is device-local and never synced, handed off, or logged.
 */

/** One certification in a region's ordered list (TMDB `/certification/{movie,tv}/list`); a higher [order] is more restrictive. */
data class CertificationEntry(val certification: String, val order: Int)

/**
 * The two ordered lists TMDB publishes for one [region]. Orders are only
 * comparable inside one list, so a movie label is looked up in [movie] and a
 * TV label in [tv].
 */
data class CertificationLists(
    val region: String,
    val movie: List<CertificationEntry>,
    val tv: List<CertificationEntry>,
)

/**
 * The parent's ceiling: a label per media type inside [region]'s lists, or
 * null for no limit on that type. Labels, never orders, so a revised TMDB list
 * cannot silently move the ceiling.
 */
data class RatingCeiling(val region: String, val movie: String?, val tv: String?) {
    /** True when neither type has a limit. */
    val isEmpty: Boolean get() = movie == null && tv == null
}

/**
 * Everything the controls decide for titles and gates. [youngAudienceFriendly]
 * is the PG / PG-13 preset the app has had since 27.0 (O-D6); with it and a
 * [ceiling] both on, a title must pass both.
 */
data class ParentalPolicy(
    val youngAudienceFriendly: Boolean = false,
    val ceiling: RatingCeiling? = null,
    val hideRestrictedTitles: Boolean = true,
    val blockTrailersAndLinks: Boolean = false,
    val lockSettings: Boolean = false,
    val lockLibraryEdits: Boolean = false,
) {
    /** True while a title rule is in force: the preset, or a ceiling on at least one type. */
    val isActive: Boolean get() = youngAudienceFriendly || (ceiling != null && !ceiling.isEmpty)

    /**
     * The parts of the policy a title decision depends on, as a stable string.
     * The filter folds it into its cache key, so a ceiling or preset change
     * clears cached decisions the way a region change does; the switches that
     * only gate the UI are left out so toggling them costs no re-verification.
     */
    val key: String
        get() = buildString {
            append("ya=").append(if (youngAudienceFriendly) '1' else '0')
            append(";c=")
            ceiling?.let { append(it.region).append(':').append(it.movie.orEmpty()).append(':').append(it.tv.orEmpty()) }
        }

    companion object {
        /** No preset, no ceiling, nothing locked. */
        val OFF = ParentalPolicy()
    }
}

/** The three verification outcomes the filter caches per title. */
enum class AudienceDecision { ALLOWED, BLOCKED, UNAVAILABLE }

/** A region's lists as the store keeps them: with the device region they were fetched for and when (O.3). */
data class CachedCertificationLists(
    val lists: CertificationLists,
    /** The device region at fetch time; a different one means the lists must be fetched again. */
    val deviceRegion: String,
    /** Wall-clock millis of the fetch; the lists are refreshed after a day. */
    val fetchedAt: Long,
)

/** The outcome of a PIN attempt. Carries counts and deadlines only, never the PIN. */
sealed interface PinResult {
    /** The PIN matched (or a new one was stored); the controls are unlocked until [untilMillis], wall-clock. */
    data class Unlocked(val untilMillis: Long) : PinResult

    /** The PIN did not match; [retryAfterMillis] is 0 while attempts are still free (O-D3). */
    data class Wrong(val failures: Int, val retryAfterMillis: Long) : PinResult

    /** The back-off is running; nothing was checked. */
    data class LockedOut(val retryAfterMillis: Long) : PinResult

    /** No PIN is set, so there is nothing to verify or remove. */
    data object NoPin : PinResult

    /** A PIN is set and the current one was not supplied. */
    data object PinRequired : PinResult

    /** The new PIN is not 4–8 digits (O-D2). */
    data object InvalidPin : PinResult
}

/**
 * The two clocks the back-off persists (O-D3): wall time, which a user can
 * change, and time since boot, which a reboot restarts. Both are read so
 * neither trick shortens a lockout.
 */
interface ParentalClock {
    fun wallMillis(): Long

    fun elapsedMillis(): Long
}

/** The JVM's clocks; Android passes `SystemClock.elapsedRealtime()` instead, which keeps counting through deep sleep. */
object SystemParentalClock : ParentalClock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L
}
