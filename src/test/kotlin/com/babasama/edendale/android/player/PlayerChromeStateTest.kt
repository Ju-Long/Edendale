package com.babasama.edendale.android.player

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun readsSnapshotState(read: () -> Unit): Boolean {
        var observed = false
        Snapshot.observe(readObserver = { observed = true }) { read() }
        return observed
    }
}
