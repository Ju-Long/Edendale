package com.babasama.edendale.android.player

/**
 * A queued automatic advance ticket, valid only for the presentation that requested it.
 */
data class AdvanceTicket(val generation: Long)

/**
 * The action to take when playback ends naturally or via terminal credits skip (DIFF §3.4).
 */
sealed interface NaturalEndAction {
    /** Restart playback from position 0 because Loop is enabled. */
    data object LoopRestart : NaturalEndAction

    /** Advance to the next episode in place. */
    data class Advance(val nextEpisode: EpisodeCandidate, val ticket: AdvanceTicket) : NaturalEndAction

    /** Finish playback because there is no successor or this is not an episode. */
    data object Finish : NaturalEndAction
}

/**
 * Orders playback transitions (DIFF §3.4, ENHANCEMENT C.2):
 * - A newer manual play request cancels a pending automatic advance.
 * - Closing the player cancels any pending advance.
 * - Duplicate advance requests from one ending move on only once.
 * - Once an item is marked completed, late progress writes won't overwrite it with partial progress.
 */
class PlaybackTransitions(
    initialGeneration: Long = 0L,
) {
    var generation: Long = initialGeneration
        private set

    /** True once the current item was marked complete; its progress is final. */
    var currentCompleted: Boolean = false
        private set

    /** Whether a periodic or closing progress write may still touch the current item. */
    val shouldWriteProgress: Boolean
        get() = !currentCompleted

    /** A new item is presented, whether chosen manually or advanced to. */
    fun present(): Long {
        currentCompleted = false
        return ++generation
    }

    /** The player ended or closed; any queued advance is void. */
    fun end() {
        currentCompleted = false
        generation++
    }

    /** The current item reached its natural end or a terminal credits skip. */
    fun markCurrentCompleted() {
        currentCompleted = true
    }

    /** Queues an advance ticket for the item presented now. */
    fun requestAdvance(): AdvanceTicket = AdvanceTicket(generation)

    /**
     * True for the first claim of a ticket still belonging to the current presentation.
     * Duplicate claims and claims made stale by a manual request or a close return false.
     */
    fun claim(ticket: AdvanceTicket): Boolean {
        if (ticket.generation != generation) return false
        generation++
        return true
    }

    /**
     * Pure decision logic for a natural end of media or terminal credits skip.
     * Marks the current item completed if it wasn't already.
     */
    fun onNaturalEnd(
        loopEnabled: Boolean,
        currentEpisode: EpisodeCandidate?,
        episodes: List<EpisodeCandidate>?,
    ): NaturalEndAction {
        markCurrentCompleted()
        if (loopEnabled) {
            return NaturalEndAction.LoopRestart
        }
        val next = if (currentEpisode != null && episodes != null) {
            EpisodeProgression.nextEpisode(currentEpisode, episodes)
        } else {
            null
        }
        return if (next != null) {
            NaturalEndAction.Advance(next, requestAdvance())
        } else {
            NaturalEndAction.Finish
        }
    }
}
