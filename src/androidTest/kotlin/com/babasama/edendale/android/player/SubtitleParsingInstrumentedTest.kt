package com.babasama.edendale.android.player

import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B.4.T2: the subtitle files the player sideloads (Wyzie downloads included)
 * reach the overlay as the expected cue text. The fixtures are built here
 * rather than checked in as binaries. Instrumented, because Media3's parsers
 * build android.text spans.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SubtitleParsingInstrumentedTest {

    private fun parse(parser: SubtitleParser, bytes: ByteArray): List<CuesWithTiming> {
        val output = mutableListOf<CuesWithTiming>()
        parser.parse(bytes, SubtitleParser.OutputOptions.allCues()) { output += it }
        return output.sortedBy { it.startTimeUs }
    }

    private fun List<CuesWithTiming>.texts(): List<String> =
        flatMap { group -> group.cues.map { it.text.toString() } }

    private val srt = listOf(
        "1",
        "00:00:01,000 --> 00:00:02,500",
        "Hello there.",
        "Second line",
        "",
        "2",
        "00:00:03,000 --> 00:00:04,000",
        "Goodbye.",
        "",
    )

    @Test
    fun srtWithCrlfLineEndings() {
        val cues = parse(SubripParser(), srt.joinToString("\r\n").toByteArray(Charsets.UTF_8))
        assertEquals(listOf("Hello there.\nSecond line", "Goodbye."), cues.texts())
        assertEquals(1_000_000L, cues.first().startTimeUs)
        assertEquals(1_500_000L, cues.first().durationUs)
    }

    @Test
    fun srtInUtf16LittleEndianWithABom() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + srt.joinToString("\r\n").replace("Goodbye.", "Grüße — 再见").toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("Hello there.\nSecond line", "Grüße — 再见"), parse(SubripParser(), bytes).texts())
    }

    @Test
    fun webVtt() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:02.000 line:10%
            <i>Whispered</i> words

            00:00:03.000 --> 00:00:04.000
            Plain cue
        """.trimIndent() + "\n"
        val cues = parse(WebvttParser(), vtt.toByteArray(Charsets.UTF_8))
        assertEquals(listOf("Whispered words", "Plain cue"), cues.texts())
    }

    @Test
    fun assDialogueDropsOverrideTagsAndKeepsPlacement() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 1920
            PlayResY: 1080

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Alignment, MarginL, MarginR, MarginV
            Style: Default,Arial,48,&H00FFFFFF,0,0,2,10,10,10

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\an8}{\i1}Top line{\i0}\Nsecond
            Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\b1}Bottom{\b0} line
        """.trimIndent() + "\n"
        val cues = parse(SsaParser(), ass.toByteArray(Charsets.UTF_8))
        assertEquals(listOf("Top line\nsecond", "Bottom line"), cues.texts())
        // {\an8} anchors the first cue to the top; the overlay keeps that.
        assertEquals(Cue.ANCHOR_TYPE_START, cues.first().cues.single().lineAnchor)
    }
}
