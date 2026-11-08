package com.babasama.edendale.parental

import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParentalGateRulesTest {
    private val locks = ParentalPolicy(lockSettings = true, lockLibraryEdits = true)

    @Test
    fun nothingIsGatedWithoutAPinOrInsideTheUnlockWindow() {
        GatedAction.entries.forEach { action ->
            assertFalse(ParentalGateRules.requiresPin(action, locks, hasPin = false, unlocked = false), "$action without a PIN")
            assertFalse(ParentalGateRules.requiresPin(action, locks, hasPin = true, unlocked = true), "$action while unlocked")
        }
    }

    @Test
    fun settingsAndLibraryEditsFollowTheirSwitches() {
        val off = ParentalPolicy()
        assertFalse(ParentalGateRules.requiresPin(GatedAction.SETTINGS, off, hasPin = true, unlocked = false))
        assertFalse(ParentalGateRules.requiresPin(GatedAction.LIBRARY_EDIT, off, hasPin = true, unlocked = false))
        assertTrue(ParentalGateRules.requiresPin(GatedAction.SETTINGS, ParentalPolicy(lockSettings = true), hasPin = true, unlocked = false))
        assertFalse(ParentalGateRules.requiresPin(GatedAction.LIBRARY_EDIT, ParentalPolicy(lockSettings = true), hasPin = true, unlocked = false))
        assertTrue(ParentalGateRules.requiresPin(GatedAction.LIBRARY_EDIT, ParentalPolicy(lockLibraryEdits = true), hasPin = true, unlocked = false))
        assertFalse(ParentalGateRules.requiresPin(GatedAction.SETTINGS, ParentalPolicy(lockLibraryEdits = true), hasPin = true, unlocked = false))
    }

    @Test
    fun syncPairingLinksAndTheParentalSectionAlwaysAskOnceAPinIsSet() {
        val off = ParentalPolicy()
        listOf(GatedAction.SYNC, GatedAction.PAIRING_LINK, GatedAction.PARENTAL_CHANGE).forEach { action ->
            assertTrue(ParentalGateRules.requiresPin(action, off, hasPin = true, unlocked = false), "$action with both switches off")
        }
    }

    @Test
    fun onlyAPairingLinkIsDroppedOnAWrongPin() {
        assertTrue(ParentalGateRules.dropsOnWrongPin(GatedAction.PAIRING_LINK))
        GatedAction.entries.filter { it != GatedAction.PAIRING_LINK }.forEach { action ->
            assertFalse(ParentalGateRules.dropsOnWrongPin(action), "$action")
        }
    }

    @Test
    fun anEpisodeIsJudgedByItsShowAndAnIdLessFileByNothing() {
        assertEquals(MediaRef(603, MediaType.MOVIE), ParentalGateRules.playbackRef(603, isEpisode = false, showTmdbId = null))
        assertEquals(MediaRef(1399, MediaType.TV), ParentalGateRules.playbackRef(63056, isEpisode = true, showTmdbId = 1399))
        // The episode's own id is not a certified title.
        assertNull(ParentalGateRules.playbackRef(63056, isEpisode = true, showTmdbId = null))
        assertNull(ParentalGateRules.playbackRef(null, isEpisode = false, showTmdbId = null))
        assertNull(ParentalGateRules.playbackRef(0, isEpisode = false, showTmdbId = null))
        assertNull(ParentalGateRules.playbackRef(5, isEpisode = true, showTmdbId = -1))
    }

    @Test
    fun playbackIsOpenWithoutARuleAndFailsClosedWithOne() {
        val allowed = MediaRef(1, MediaType.MOVIE)
        val blocked = MediaRef(2, MediaType.MOVIE)
        val allows: (MediaRef) -> Boolean = { it == allowed }
        assertTrue(ParentalGateRules.permitsPlayback(policyActive = false, ref = null, allows = allows))
        assertTrue(ParentalGateRules.permitsPlayback(policyActive = false, ref = blocked, allows = allows))
        assertTrue(ParentalGateRules.permitsPlayback(policyActive = true, ref = allowed, allows = allows))
        assertFalse(ParentalGateRules.permitsPlayback(policyActive = true, ref = blocked, allows = allows))
        assertFalse(ParentalGateRules.permitsPlayback(policyActive = true, ref = null, allows = allows), "no id, no playback (O-D8)")
    }

    @Test
    fun trailersAreBlockedByTheSwitchAndWhileThePolicyIsUnknown() {
        assertTrue(ParentalGateRules.allowsTrailers(ParentalPolicy()))
        assertTrue(ParentalGateRules.allowsTrailers(ParentalPolicy(youngAudienceFriendly = true)))
        assertFalse(ParentalGateRules.allowsTrailers(ParentalPolicy(blockTrailersAndLinks = true)))
        assertFalse(ParentalGateRules.allowsTrailers(null))
    }
}
