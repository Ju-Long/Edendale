package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerControlPreferencesTest {

    @Test
    fun defaultsSkipTenSecondsAndHoldAtHalfAndDoubleSpeed() {
        val store = InMemoryPlayerPreferencesStore()
        val controls = PlayerPreferences(store)

        assertEquals(SkipInterval.TEN, controls.skipBackwardInterval)
        assertEquals(SkipInterval.TEN, controls.skipForwardInterval)
        assertEquals(0.5f, controls.holdRate(HoldSide.LEFT))
        assertEquals(2.0f, controls.holdRate(HoldSide.RIGHT))
    }

    @Test
    fun choicesPersistForTheNextLaunch() {
        val store = InMemoryPlayerPreferencesStore()
        val controls = PlayerPreferences(store)
        controls.skipBackwardInterval = SkipInterval.THIRTY
        controls.skipForwardInterval = SkipInterval.FIFTEEN
        controls.setHoldRate(0.75f, HoldSide.LEFT)
        controls.setHoldRate(2.5f, HoldSide.RIGHT)

        val relaunched = PlayerPreferences(store)
        assertEquals(SkipInterval.THIRTY, relaunched.skipBackwardInterval)
        assertEquals(SkipInterval.FIFTEEN, relaunched.skipForwardInterval)
        assertEquals(0.75f, relaunched.holdRate(HoldSide.LEFT))
        assertEquals(2.5f, relaunched.holdRate(HoldSide.RIGHT))
    }

    @Test
    fun skipIntervalRoundTrip() {
        val store = InMemoryPlayerPreferencesStore()
        val controls = PlayerPreferences(store)

        controls.skipBackwardInterval = SkipInterval.TEN
        assertEquals(SkipInterval.TEN, controls.skipBackwardInterval)

        controls.skipBackwardInterval = SkipInterval.FIFTEEN
        assertEquals(SkipInterval.FIFTEEN, controls.skipBackwardInterval)

        controls.skipBackwardInterval = SkipInterval.THIRTY
        assertEquals(SkipInterval.THIRTY, controls.skipBackwardInterval)
    }

    @Test
    fun skipOffsetsAreSignedByDirection() {
        val store = InMemoryPlayerPreferencesStore()
        val controls = PlayerPreferences(store)
        controls.skipBackwardInterval = SkipInterval.FIFTEEN
        controls.skipForwardInterval = SkipInterval.THIRTY

        assertEquals(-15, controls.skipOffset(SkipDirection.BACKWARD))
        assertEquals(30, controls.skipOffset(SkipDirection.FORWARD))
    }

    @Test
    fun holdRatesSnapToQuarterStepsWithinTheSpeedRange() {
        val store = InMemoryPlayerPreferencesStore()
        val controls = PlayerPreferences(store)

        controls.setHoldRate(0.6f, HoldSide.LEFT)
        assertEquals(0.5f, controls.holdRate(HoldSide.LEFT))

        controls.setHoldRate(1.4f, HoldSide.RIGHT)
        assertEquals(1.5f, controls.holdRate(HoldSide.RIGHT))

        controls.setHoldRate(0.3f, HoldSide.LEFT)
        assertEquals(0.25f, controls.holdRate(HoldSide.LEFT))

        controls.setHoldRate(0.4f, HoldSide.RIGHT)
        assertEquals(0.5f, controls.holdRate(HoldSide.RIGHT))

        controls.setHoldRate(2.6f, HoldSide.RIGHT)
        assertEquals(2.5f, controls.holdRate(HoldSide.RIGHT))

        // Clamping bounds
        controls.setHoldRate(9f, HoldSide.RIGHT)
        assertEquals(PlayerPreferencesRules.HOLD_RATE_MAX, controls.holdRate(HoldSide.RIGHT))

        controls.setHoldRate(5f, HoldSide.RIGHT)
        assertEquals(3.0f, controls.holdRate(HoldSide.RIGHT))

        controls.setHoldRate(0.05f, HoldSide.LEFT)
        assertEquals(PlayerPreferencesRules.HOLD_RATE_MIN, controls.holdRate(HoldSide.LEFT))

        controls.setHoldRate(0.1f, HoldSide.LEFT)
        assertEquals(0.25f, controls.holdRate(HoldSide.LEFT))

        // Non-finite values
        controls.setHoldRate(Float.NaN, HoldSide.LEFT)
        assertEquals(PlayerPreferencesRules.HOLD_RATE_MIN, controls.holdRate(HoldSide.LEFT))

        controls.setHoldRate(Float.POSITIVE_INFINITY, HoldSide.LEFT)
        assertEquals(PlayerPreferencesRules.HOLD_RATE_MIN, controls.holdRate(HoldSide.LEFT))

        controls.setHoldRate(Float.NEGATIVE_INFINITY, HoldSide.LEFT)
        assertEquals(PlayerPreferencesRules.HOLD_RATE_MIN, controls.holdRate(HoldSide.LEFT))
    }

    @Test
    fun unrecognizedStoredValuesFallBack() {
        val store = InMemoryPlayerPreferencesStore()
        store.putInt(PlayerPreferencesRules.KEY_SKIP_FORWARD_SECONDS, 12)
        store.putFloat(PlayerPreferencesRules.KEY_HOLD_RIGHT_RATE, 7.3f)

        val controls = PlayerPreferences(store)
        assertEquals(PlayerPreferencesRules.DEFAULT_SKIP_INTERVAL, controls.skipForwardInterval)
        assertEquals(PlayerPreferencesRules.HOLD_RATE_MAX, controls.holdRate(HoldSide.RIGHT))

        // Unknown integer checks: 0, 20, -10
        listOf(0, 20, -10).forEach { raw ->
            store.putInt(PlayerPreferencesRules.KEY_SKIP_BACKWARD_SECONDS, raw)
            assertEquals(SkipInterval.TEN, controls.skipBackwardInterval)
        }
    }

    @Test
    fun missingKeysFallBackToDefaults() {
        val store = InMemoryPlayerPreferencesStore()
        val controls = PlayerPreferences(store)

        assertEquals(0.5f, controls.holdRate(HoldSide.LEFT))
        assertEquals(2.0f, controls.holdRate(HoldSide.RIGHT))
    }

    @Test
    fun changeListenersAreNotified() {
        val store = InMemoryPlayerPreferencesStore()
        val controls = PlayerPreferences(store)
        var changes = 0
        val subscription = controls.addChangeListener { changes++ }

        controls.skipBackwardInterval = SkipInterval.FIFTEEN
        controls.skipForwardInterval = SkipInterval.THIRTY
        controls.setHoldRate(1.0f, HoldSide.LEFT)

        assertEquals(3, changes)
        subscription.close()

        controls.setHoldRate(1.25f, HoldSide.LEFT)
        assertEquals(3, changes)
    }

    @Test
    fun legacyAutoSkipKeysAreRemovedAndSkipPromptsRemainsOff() {
        val store = InMemoryPlayerPreferencesStore()
        store.putBoolean("player.skipRecap", true)
        store.putBoolean("player.skipCredits", true)

        val prefs = PlayerPreferences(store)

        // C.4.T1: With player.skipRecap = true and player.skipCredits = true stored,
        // Skip Prompts still reads as off.
        assertFalse(prefs.segmentPromptsEnabled)

        // C.4.2: Stored keys player.skipRecap and player.skipCredits are removed.
        assertFalse(store.contains("player.skipRecap"))
        assertFalse(store.contains("player.skipCredits"))
    }
}
