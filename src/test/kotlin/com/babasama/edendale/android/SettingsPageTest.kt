package com.babasama.edendale.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The categories Settings' first page lists, in iOS 27.1's order. */
class SettingsPageTest {

    @Test
    fun handheldsListTheSixCategoriesInOrder() {
        assertEquals(
            listOf(
                SettingsPage.PLAYER_CONTROLS,
                SettingsPage.AUDIO,
                SettingsPage.SUBTITLES,
                SettingsPage.LIBRARY,
                SettingsPage.SYNC,
                SettingsPage.ACCOUNTS,
            ),
            SettingsPage.categories(isTelevision = false),
        )
    }

    @Test
    fun televisionAddsAndroidTvLast() {
        val pages = SettingsPage.categories(isTelevision = true)
        assertEquals(SettingsPage.categories(isTelevision = false) + SettingsPage.ANDROID_TV, pages)
    }

    @Test
    fun parentalControlsOpensFromItsOwnGroupNotTheCategories() {
        assertFalse(SettingsPage.PARENTAL_CONTROLS in SettingsPage.categories(isTelevision = false))
        assertFalse(SettingsPage.PARENTAL_CONTROLS in SettingsPage.categories(isTelevision = true))
    }
}
