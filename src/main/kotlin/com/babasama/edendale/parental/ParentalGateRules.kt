package com.babasama.edendale.parental

import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType

/** An action the parental PIN can stand in front of (O.5). */
enum class GatedAction {
    /** Opening the Settings sheet, on TV and handhelds alike (O-D1). */
    SETTINGS,

    /** Adding, linking, rescanning, or removing a source or a title; the automatic rescan is not one. */
    LIBRARY_EDIT,

    /** Any button in Settings → Sync: the section stays readable, every change asks (O-D14). */
    SYNC,

    /** An `edendale://handoff` link from the camera or another app (O-D13). */
    PAIRING_LINK,

    /** A change in Settings → Parental Controls: the preset, the ceiling, a switch, or the PIN itself. */
    PARENTAL_CHANGE,
}

/**
 * When the PIN is asked, what a playable item is judged by, and the trailer
 * rule: the pure half of the gates, with no Android or Compose code, so the
 * hermetic suite covers every branch. The controls enforce the same rules
 * underneath (`updatePolicy` refuses while locked); these decide what the
 * UI asks before it tries.
 */
object ParentalGateRules {
    /**
     * Whether [action] has to ask for the PIN now. Without a PIN nothing is
     * gated, and inside the unlock window (O-D4) nothing asks twice. Sync, a
     * pairing link, and the parental section need the PIN whenever one is set
     * (FEATURES-27.1 §4: a restricted profile cannot change the ceiling, the
     * PIN, or sync settings); Settings and library edits only when their own
     * switch is on.
     */
    fun requiresPin(action: GatedAction, policy: ParentalPolicy, hasPin: Boolean, unlocked: Boolean): Boolean {
        if (!hasPin || unlocked) return false
        return when (action) {
            GatedAction.SETTINGS -> policy.lockSettings
            GatedAction.LIBRARY_EDIT -> policy.lockLibraryEdits
            GatedAction.SYNC, GatedAction.PAIRING_LINK, GatedAction.PARENTAL_CHANGE -> true
        }
    }

    /**
     * O-D13: a pairing link gets one attempt. A wrong PIN drops the link
     * instead of offering another try, so a link cannot be used to probe the
     * PIN; everything else keeps the dialog open with an error line.
     */
    fun dropsOnWrongPin(action: GatedAction): Boolean = action == GatedAction.PAIRING_LINK

    /**
     * The title a playable item is judged by: an episode follows its show, as
     * the library's lists do; a movie is itself; a file with no TMDB id has
     * no title to judge, and is restricted while a rule is active (O-D8).
     */
    fun playbackRef(tmdbId: Int?, isEpisode: Boolean, showTmdbId: Int?): MediaRef? =
        if (isEpisode) {
            showTmdbId?.takeIf { it > 0 }?.let { MediaRef(it, MediaType.TV) }
        } else {
            tmdbId?.takeIf { it > 0 }?.let { MediaRef(it, MediaType.MOVIE) }
        }

    /**
     * Whether playback may start or continue to [ref]: always while no title
     * rule is active; otherwise only a title the filter has verified as
     * allowed, so an unverified or id-less item fails closed.
     */
    fun permitsPlayback(policyActive: Boolean, ref: MediaRef?, allows: (MediaRef) -> Boolean): Boolean =
        !policyActive || (ref != null && allows(ref))

    /**
     * O-D10: with the switch on the detail page hides Watch Trailer and never
     * asks TMDB for `/videos`. An unknown policy (the store not read yet)
     * allows nothing either, so a cold start cannot fetch a trailer the
     * policy forbids.
     */
    fun allowsTrailers(policy: ParentalPolicy?): Boolean = policy != null && !policy.blockTrailersAndLinks
}
