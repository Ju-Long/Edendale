package com.babasama.edendale.domain

/**
 * Android external-link contract.
 *
 * One grammar, two spellings of the same route:
 *
 * | Route            | Custom scheme                          | Web link                                     |
 * |------------------|----------------------------------------|----------------------------------------------|
 * | Search           | `edendale://search?q=blade`            | `https://<host>/search?q=blade`              |
 * | TMDB detail      | `edendale://media/movie/78`            | `https://<host>/media/movie/78`              |
 * | Local detail     | `edendale://library/movie/<uuid>`      | `https://<host>/library/movie/<uuid>`        |
 * | Play (TMDB id)   | `edendale://play/episode/63056`        | `https://<host>/play/episode/63056`          |
 * | Play (local id)  | `edendale://play/local-movie/<uuid>`   | `https://<host>/play/local-movie/<uuid>`     |
 *
 * The custom scheme carries the first component in the authority position
 * (`edendale://search`), the web link carries it as the first path segment
 * (`https://host/search`); [parse] normalises both into the same segment list.
 *
 * A route carries intent only. The Android shell resolves it so external
 * links can never bypass navigation or the player lifecycle.
 */
sealed interface AppRoute {
    data class Search(val query: String) : AppRoute
    data class Media(val ref: MediaRef) : AppRoute
    data class LocalMovie(val id: String) : AppRoute
    data class LocalShow(val id: String) : AppRoute
    data class PlayMovie(val tmdbId: Int) : AppRoute
    data class PlayEpisode(val tmdbId: Int) : AppRoute
    data class PlayLocalMovie(val id: String) : AppRoute
    data class PlayLocalEpisode(val id: String) : AppRoute

    companion object {
        /** Custom scheme registered by the Android app. Always available offline. */
        const val SCHEME: String = "edendale"

        /**
         * Verified web-link host.
         *
         * When changing this host, update both:
         *
         *  - `src/main/AndroidManifest.xml` (the `autoVerify` filter)
         *  - the hosted `/.well-known/assetlinks.json`
         *
         * Until the host serves those two files over HTTPS, web links stay inert
         * and only [SCHEME] links resolve.
         */
        const val HOST: String = "edendale.babasama.com"

        /** Path prefixes the web host owns; mirrored by the Android intent filter. */
        val WEB_PATH_PREFIXES: List<String> = listOf("search", "media", "library", "play")

        /** Returns the route a link asks for, or null when it is not one of ours. */
        fun parse(url: String): AppRoute? {
            val schemeSeparator = url.indexOf("://")
            if (schemeSeparator <= 0) return null
            val scheme = url.substring(0, schemeSeparator).trim().lowercase()

            // Strip the fragment, then split the query off the path.
            val withoutFragment = url.substring(schemeSeparator + 3).substringBefore('#')
            val query = withoutFragment.substringAfter('?', "")
            val rawSegments = withoutFragment.substringBefore('?')
                .split('/')
                .filter { it.isNotEmpty() }

            val segments = when (scheme) {
                SCHEME -> rawSegments
                "https" -> {
                    // Authority may carry userinfo and a port; neither identifies the host.
                    val host = rawSegments.firstOrNull()
                        ?.substringAfterLast('@')
                        ?.substringBefore(':')
                        ?.lowercase()
                    if (host != HOST) return null
                    rawSegments.drop(1)
                }
                else -> return null
            }

            val head = segments.firstOrNull()?.lowercase() ?: return null
            val tail = segments.drop(1).map { percentDecode(it, plusAsSpace = false) }

            return when (head) {
                "search" -> {
                    val text = queryValue(query, "q")?.trim()
                    if (text.isNullOrEmpty()) null else Search(text)
                }

                "media" -> {
                    if (tail.size != 2) return null
                    val mediaType = MediaType.entries.firstOrNull { it.pathSegment == tail[0] }
                        ?: return null
                    val id = tail[1].toIntOrNull() ?: return null
                    Media(MediaRef(id, mediaType))
                }

                "library" -> {
                    if (tail.size != 2) return null
                    val id = localId(tail[1]) ?: return null
                    when (tail[0]) {
                        "movie" -> LocalMovie(id)
                        "show" -> LocalShow(id)
                        else -> null
                    }
                }

                "play" -> {
                    if (tail.size != 2) return null
                    when (tail[0]) {
                        "movie" -> tail[1].toIntOrNull()?.let(::PlayMovie)
                        "episode" -> tail[1].toIntOrNull()?.let(::PlayEpisode)
                        "local-movie" -> localId(tail[1])?.let(::PlayLocalMovie)
                        "local-episode" -> localId(tail[1])?.let(::PlayLocalEpisode)
                        else -> null
                    }
                }

                else -> null
            }
        }
    }
}

/** `edendale://…` — the always-available form. */
fun AppRoute.deepLink(): String = "${AppRoute.SCHEME}://${pathAndQuery()}"

/** `https://host/…` — resolves in-app only once [host] verifies the app. */
fun AppRoute.webLink(host: String = AppRoute.HOST): String = "https://$host/${pathAndQuery()}"

private fun AppRoute.pathAndQuery(): String = when (this) {
    is AppRoute.Search -> "search?q=${percentEncode(query)}"
    is AppRoute.Media -> "media/${ref.mediaType.pathSegment}/${ref.id}"
    is AppRoute.LocalMovie -> "library/movie/$id"
    is AppRoute.LocalShow -> "library/show/$id"
    is AppRoute.PlayMovie -> "play/movie/$tmdbId"
    is AppRoute.PlayEpisode -> "play/episode/$tmdbId"
    is AppRoute.PlayLocalMovie -> "play/local-movie/$id"
    is AppRoute.PlayLocalEpisode -> "play/local-episode/$id"
}

private val UUID_SHAPE =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * Web links use UUID-shaped local identifiers. Android stores local media as
 * SAF document URIs and cannot resolve these; it parses them before reporting
 * the link as unsupported.
 */
private fun localId(value: String): String? =
    value.takeIf(UUID_SHAPE::matches)?.lowercase()

private fun queryValue(query: String, name: String): String? {
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val separator = pair.indexOf('=')
        if (separator < 0) continue
        if (percentDecode(pair.substring(0, separator), plusAsSpace = false) != name) continue
        return percentDecode(pair.substring(separator + 1), plusAsSpace = true)
    }
    return null
}

private const val HEX = "0123456789ABCDEF"

/** Unreserved per RFC 3986; everything else in a query value gets escaped. */
private fun percentEncode(value: String): String {
    val builder = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val character = code.toChar()
        if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character == '-' || character == '.' || character == '_' || character == '~'
        ) {
            builder.append(character)
        } else {
            builder.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
    }
    return builder.toString()
}

/**
 * Decodes `%XX` (and, in query values, `+`) back to text. Literal runs are
 * converted whole rather than character by character, so astral-plane
 * characters survive their surrogate pairs intact.
 */
private fun percentDecode(value: String, plusAsSpace: Boolean): String {
    if (!value.contains('%') && !(plusAsSpace && value.contains('+'))) return value

    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    var literalStart = 0

    fun flushLiteral(end: Int) {
        if (end > literalStart) {
            for (byte in value.substring(literalStart, end).encodeToByteArray()) bytes.add(byte)
        }
    }

    while (index < value.length) {
        val character = value[index]
        if (character == '%' && index + 3 <= value.length) {
            val code = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (code != null) {
                flushLiteral(index)
                bytes.add(code.toByte())
                index += 3
                literalStart = index
                continue
            }
        }
        if (plusAsSpace && character == '+') {
            flushLiteral(index)
            bytes.add(' '.code.toByte())
            index++
            literalStart = index
            continue
        }
        index++
    }
    flushLiteral(index)

    return bytes.toByteArray().decodeToString()
}
