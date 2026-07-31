package com.babasama.edendale.wyzie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WyzieSubtitleTest {
    @Test
    fun movieQueryContainsOnlyIdAndKey() {
        val parameters = queryParameters(WyzieSubtitleQuery(id = "278"), "secret")

        assertEquals(listOf("id", "key"), parameters.map { it.first })
        assertEquals(listOf("278", "secret"), parameters.map { it.second })
    }

    @Test
    fun fullEpisodeQueryUsesContractOrderWithKeyLast() {
        val parameters = queryParameters(
            WyzieSubtitleQuery(
                id = "1396",
                season = 2,
                episode = 8,
                language = "en",
                format = "srt",
                hearingImpaired = true,
            ),
            "secret",
        )

        assertEquals(
            listOf("id", "season", "episode", "language", "format", "hi", "key"),
            parameters.map { it.first },
        )
        assertEquals(
            listOf("1396", "2", "8", "en", "srt", "true", "secret"),
            parameters.map { it.second },
        )
    }

    @Test
    fun episodeWithoutSeasonIsDropped() {
        val parameters = queryParameters(
            WyzieSubtitleQuery(id = "1396", episode = 8),
            "secret",
        )

        assertEquals(listOf("id", "key"), parameters.map { it.first })
    }

    @Test
    fun successDecodingNormalizesSourcesAndToleratesAbsentOptionals() {
        val subtitles = decodeSearchResponse(successBody, 200)

        assertEquals(3, subtitles.size)
        assertEquals(listOf("lima"), subtitles[0].source)
        assertEquals(listOf("lima", "opensubtitles"), subtitles[1].source)
        with(subtitles[2]) {
            assertNull(source)
            assertNull(release)
            assertNull(releases)
            assertNull(fileName)
            assertNull(downloadCount)
            assertNull(origin)
            assertNull(matchedRelease)
            assertNull(matchedFilter)
        }
    }

    @Test
    fun errorEnvelopePreservesServerMessage() {
        val error = assertFailsWith<WyzieException.BadStatus> {
            decodeSearchResponse(
                """{"code":401,"message":"API key required","details":{},"notice":"notice"}""",
                401,
            )
        }

        assertEquals(401, error.code)
        assertEquals("API key required", error.serverMessage)
    }

    @Test
    fun cacheNameIsOneSafePathComponent() {
        val name = cacheFileName(
            subtitle(
                id = "../Sub ID",
                language = "EN/../US",
                format = "",
            ),
        )

        assertEquals("wyzie-subid-enus.srt", name)
        assertFalse('/' in name)
        assertFalse(' ' in name)
        assertFalse(".." in name)
    }

    @Test
    fun searchUrlPercentEncodesOrderedParameters() {
        val url = searchUrl(
            WyzieSubtitleQuery(id = "tt 12/?", language = "pt-BR"),
            "a+b&c",
        )

        assertEquals(
            "https://sub.wyzie.io/search?id=tt%2012%2F%3F&language=pt-BR&key=a%2Bb%26c",
            url,
        )
    }
}

private fun subtitle(
    id: String,
    language: String,
    format: String,
) = WyzieSubtitle(
    id = id,
    url = "https://example.com/subtitle.srt",
    format = format,
    encoding = "UTF-8",
    isHearingImpaired = false,
    flagUrl = "https://example.com/flag.png",
    media = "movie",
    display = "English",
    language = language,
)

private val successBody = """
    [
      {
        "id":"one","url":"https://example.com/one.srt","format":"srt","encoding":"UTF-8",
        "isHearingImpaired":false,"flagUrl":"https://example.com/en.png","media":"movie",
        "display":"English","language":"en","source":"lima","release":"Release.One",
        "releases":["Release.One"],"fileName":"one.srt","downloadCount":12,"origin":"Wyzie",
        "matchedRelease":"Release.One","matchedFilter":"release","ai":false
      },
      {
        "id":"two","url":"https://example.com/two.srt","format":"srt","encoding":"UTF-8",
        "isHearingImpaired":true,"flagUrl":"https://example.com/en.png","media":"movie",
        "display":"English HI","language":"en","source":["lima","opensubtitles"]
      },
      {
        "id":"three","url":"https://example.com/three.vtt","format":"vtt","encoding":"UTF-8",
        "isHearingImpaired":false,"flagUrl":"https://example.com/fr.png","media":"movie",
        "display":"Français","language":"fr"
      },
      {"id":"missing-required","url":"https://example.com/bad.srt"}
    ]
""".trimIndent()
