package com.babasama.edendale.android.xr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrTheaterRulesTest {

    @Test
    fun onlyAnXrDeviceWithTheSplitAndAVideoGetsTheChip() {
        assertTrue(XrTheaterRules.offers(hasSpatialFeature = true, installedSplits = listOf("xr"), hasVideo = true))
        assertTrue(XrTheaterRules.offers(true, listOf("config.arm64_v8a", "xr"), true))
    }

    @Test
    fun aPhoneOrATvNeverDoes() {
        assertFalse(XrTheaterRules.offers(hasSpatialFeature = false, installedSplits = listOf("xr"), hasVideo = true))
    }

    @Test
    fun theBaseApkAloneOnAHeadsetDoesNot() {
        assertFalse(XrTheaterRules.offers(true, null, true))
        assertFalse(XrTheaterRules.offers(true, emptyList(), true))
        assertFalse(XrTheaterRules.offers(true, listOf("config.en"), true))
    }

    @Test
    fun nothingLoadedMeansNoChip() {
        assertFalse(XrTheaterRules.offers(true, listOf("xr"), hasVideo = false))
    }

    @Test
    fun theNamesAreTheOnesTheSystemUses() {
        assertEquals("android.software.xr.api.spatial", XrTheaterRules.FEATURE_SPATIAL)
        assertEquals("xr", XrTheaterRules.SPLIT_NAME)
        assertEquals("com.babasama.edendale.android.xr.theater.XrTheaterActivity", XrTheaterRules.ACTIVITY_CLASS)
    }
}
