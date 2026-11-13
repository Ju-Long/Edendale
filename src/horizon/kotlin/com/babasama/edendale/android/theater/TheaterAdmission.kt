package com.babasama.edendale.android.theater

import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.parental.ParentalGateRules
import java.util.UUID

/**
 * The theater's own parental check (HORIZON.md G.15). `TheaterActivity` is
 * exported (Meta's hybrid sample; G.10 tries `exported="false"`), so another
 * app or `adb` can start it with any title. The 2D player only hands over a
 * title its parental check (O.5) has passed, so the theater trusts a
 * handover that carries a ticket the player just issued, and runs the
 * player's own check on anything else: an intent from outside, a task the
 * system restores after the process died, and the next episode (Up Next).
 */
object TheaterAdmission {
    enum class FirstLook {
        /** Play at once: the player already ruled on this handover, or no rule is in force. */
        PLAY,

        /** Wait for the controls, verify the title with TMDB, and decide with [permits]. */
        CHECK,
    }

    /**
     * What to do before anything is known about the title: play a handover
     * whose [ticketRedeemed], or any title once the controls are ready with
     * no rule in force (the player's own fast path); otherwise check.
     */
    fun firstLook(ticketRedeemed: Boolean, controlsReady: Boolean, policyActive: Boolean): FirstLook =
        if (ticketRedeemed || (controlsReady && !policyActive)) FirstLook.PLAY else FirstLook.CHECK

    /** The title a handover is judged by: the movie, or an episode's show (O-D8: none for a file without a TMDB id). */
    fun ref(handover: TheaterHandover): MediaRef? =
        ParentalGateRules.playbackRef(handover.tmdbId, handover.isEpisode, handover.showTmdbId)

    /**
     * The player's verdict: everything while no rule is in force, otherwise
     * only a title the filter allows. A title without an id, unrated, or not
     * yet verified is refused (fail closed, as the player).
     */
    fun permits(policyActive: Boolean, ref: MediaRef?, allows: (MediaRef) -> Boolean): Boolean =
        ParentalGateRules.permitsPlayback(policyActive, ref, allows)
}

/**
 * One-use tickets the player puts on its handover, so the theater knows the
 * player has just ruled on that title. They live only in this process's
 * memory: no other app can read or mint one, and a process that dies takes
 * them along, so a restored theater checks again. Each is bound to the
 * handover's identity (the file and its TMDB ids) and works once.
 */
object TheaterTickets {
    /** Enough for a handover that was issued and never redeemed (a failed start) not to pile up. */
    private const val MAX_LIVE = 8

    private val live = LinkedHashMap<String, String>()

    /** A new ticket for [handover]; the oldest unredeemed one goes once [MAX_LIVE] are waiting. */
    @Synchronized
    fun issue(handover: TheaterHandover): String {
        val ticket = UUID.randomUUID().toString()
        live[ticket] = identity(handover)
        while (live.size > MAX_LIVE) live.remove(live.keys.first())
        return ticket
    }

    /** True once for a [ticket] issued for this same [handover]; false for null, unknown, spent, or another title's. */
    @Synchronized
    fun redeem(ticket: String?, handover: TheaterHandover): Boolean {
        if (ticket == null) return false
        val bound = live[ticket] ?: return false
        // A ticket shown with the wrong title is spent anyway: it was already exposed.
        live.remove(ticket)
        return bound == identity(handover)
    }

    /** Forgets every ticket (the tests). */
    @Synchronized
    internal fun clear() = live.clear()

    private fun identity(handover: TheaterHandover): String =
        listOf(handover.uri, handover.tmdbId, handover.isEpisode, handover.showTmdbId).joinToString("\u0000")
}
