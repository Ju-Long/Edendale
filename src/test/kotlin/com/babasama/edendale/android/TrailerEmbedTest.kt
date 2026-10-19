package com.babasama.edendale.android

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trailer embed and the client identity YouTube requires; without the
 * `Referer` the player shows error 153.
 */
class TrailerEmbedTest {

    @Test
    fun embedsThePrivacyEnhancedPlayer() {
        assertEquals(
            "https://www.youtube-nocookie.com/embed/xEQP4VVuyrY?autoplay=1&modestbranding=1&playsinline=1",
            TrailerEmbed.url("xEQP4VVuyrY"),
        )
    }

    @Test
    fun identifiesTheAppByItsApplicationId() {
        assertEquals(
            mapOf("Referer" to "https://com.babasama.edendale/"),
            TrailerEmbed.headers("com.babasama.edendale"),
        )
    }
}
