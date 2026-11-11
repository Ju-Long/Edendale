package com.babasama.edendale.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The string resources themselves, read from `src/main/res` (Gradle runs the
 * JVM suite from the project directory). R.1: the Link Source notes say what
 * 27.1 sync writes, and every locale keeps the English placeholders, so a
 * translation can't show a raw `%1$s` or miss an argument.
 */
class StringResourcesTest {

    private val res = File("src/main/res")

    /** `<string>` values by name; plurals and arrays are left out. */
    private fun strings(folder: String): Map<String, String> {
        val file = File(res, "$folder/strings.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun placeholders(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.sorted().toList()

    @Test
    fun `every locale keeps the English placeholders`() {
        val english = strings("values")
        for (folder in TRANSLATED + ENGLISH_OVERRIDES) {
            for ((name, value) in strings(folder)) {
                val base = english[name] ?: continue
                assertEquals(placeholders(base), placeholders(value), "$folder/$name")
            }
        }
    }

    @Test
    fun `link source privacy notes cover the sync files`() {
        for (folder in listOf("values") + ENGLISH_OVERRIDES) {
            for ((name, value) in strings(folder).filterKeys { it in PRIVACY_NOTES }) {
                assertFalse("never uploads" in value, "$folder/$name")
                assertFalse("read-only" in value, "$folder/$name")
                assertTrue("sync" in value, "$folder/$name")
            }
        }
        for (folder in listOf("values") + TRANSLATED) {
            val values = strings(folder)
            for (name in PRIVACY_NOTES) assertTrue(name in values, "$folder/$name")
            // OneDrive writes to its app folder (Dropbox to `Edendale/`).
            assertTrue("Apps/Edendale" in values.getValue("link_source_onedrive_privacy"), folder)
        }
    }

    companion object {
        private val PLACEHOLDER = Regex("""%(\d+\$)?[sdf]""")

        private val PRIVACY_NOTES = listOf(
            "link_source_cloud_privacy",
            "link_source_onedrive_privacy",
            "link_source_gdrive_privacy",
            "link_source_gdrive_privacy_tv",
        )

        /** The 14 translated locales; `values` is English. */
        val TRANSLATED = listOf(
            "values-b+zh+Hans", "values-b+zh+Hant", "values-de", "values-es-rES", "values-es-rMX",
            "values-fr", "values-it", "values-ja", "values-ko", "values-nl", "values-pt-rBR",
            "values-pt-rPT", "values-ru", "values-sv",
        )

        /** Spelling overrides; anything they leave out falls back to `values`. */
        val ENGLISH_OVERRIDES = listOf("values-en-rAU", "values-en-rCA", "values-en-rGB", "values-en-rUS")
    }
}
