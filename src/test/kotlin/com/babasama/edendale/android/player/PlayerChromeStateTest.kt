package com.babasama.edendale.android.player

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerChromeStateTest {

    @Test
    fun skipLengthsFollowAppControlsWhileThePlayerIsOpen() {
        val store = InMemoryPlayerPreferencesStore()
        val chrome = PlayerChromeState(PlayerPreferences(store))

        // Settings writes through its own preferences object, as it does
        // while the player stays open or floats in PiP.
        val settings = PlayerPreferences(store)
        settings.skipBackwardInterval = SkipInterval.THIRTY
        settings.skipForwardInterval = SkipInterval.FIFTEEN

        assertEquals(SkipInterval.THIRTY, chrome.skipBackwardInterval)
        assertEquals(SkipInterval.FIFTEEN, chrome.skipForwardInterval)
    }

    @Test
    fun skipLengthsAreSnapshotStateSoTheGlyphsRedraw() {
        val chrome = PlayerChromeState(PlayerPreferences(InMemoryPlayerPreferencesStore()))

        // Compose redraws only for values it saw read as snapshot state.
        assertTrue(readsSnapshotState { chrome.skipBackwardInterval })
        assertTrue(readsSnapshotState { chrome.skipForwardInterval })
    }

    @Test
    fun rotationLockIsSessionStateThatShowsTheControlsAndSavesNothing() {
        val store = InMemoryPlayerPreferencesStore()
        val chrome = PlayerChromeState(PlayerPreferences(store))
        val writes = mutableListOf<String>()
        store.addListener { writes += it }

        assertFalse(chrome.orientationLocked)
        assertTrue(readsSnapshotState { chrome.orientationLocked })

        chrome.hideControls()
        chrome.setOrientationLocked(true)
        assertTrue(chrome.orientationLocked)
        assertTrue(chrome.controlsVisible)

        chrome.hideControls()
        chrome.setOrientationLocked(false)
        assertFalse(chrome.orientationLocked)
        assertTrue(chrome.controlsVisible)

        assertTrue(writes.isEmpty(), "wrote $writes")
        // A new player session starts unlocked.
        assertFalse(PlayerChromeState(PlayerPreferences(store)).orientationLocked)
    }

    @Test
    fun aRepeatedHudUpdateStillRestartsTheFade() {
        val chrome = PlayerChromeState(PlayerPreferences(InMemoryPlayerPreferencesStore()))
        assertTrue(readsSnapshotState { chrome.hudTick })

        // A volume swipe inside one device step shows the same level again and again.
        chrome.showHud(PlayerHud.Volume(0.4f))
        val first = chrome.hudTick
        chrome.showHud(PlayerHud.Volume(0.4f))
        assertEquals(first + 1, chrome.hudTick)
        assertEquals(PlayerHud.Volume(0.4f), chrome.hud)

        chrome.dismissHud()
        assertEquals(null, chrome.hud)
    }

    private fun readsSnapshotState(read: () -> Unit): Boolean {
        var observed = false
        Snapshot.observe(readObserver = { observed = true }) { read() }
        return observed
    }
}
