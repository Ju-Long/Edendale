package com.babasama.edendale.android

/**
 * The hero trailer: YouTube's privacy-enhanced embed, loaded straight into a
 * WebView after the reader asks for it.
 *
 * YouTube's embedded player refuses to play (error 153) unless the embedding
 * client identifies itself through the HTTP `Referer`. A WebView sends none of
 * its own, so YouTube's Required Minimum Functionality asks apps that load the
 * embed URL directly to add the header themselves, as an HTTPS URL whose host is
 * the app's application ID. The value names the app and nothing else.
 *
 * Where the device policy doesn't play trailers inline (Horizon OS), Watch
 * Trailer opens [watchUrl] instead, which the system hands to the YouTube app
 * or the browser.
 */
internal object TrailerEmbed {

    fun url(youTubeKey: String): String =
        "https://www.youtube-nocookie.com/embed/$youTubeKey?autoplay=1&modestbranding=1&playsinline=1"

    fun watchUrl(youTubeKey: String): String = "https://www.youtube.com/watch?v=$youTubeKey"

    /** Extra request headers for [url]; pass `Context.packageName` as [applicationId]. */
    fun headers(applicationId: String): Map<String, String> =
        mapOf("Referer" to "https://$applicationId/")
}
