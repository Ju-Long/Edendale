package com.babasama.edendale.domain

/**
 * Classifies a filename before any network lookup using Edendale's established
 * filename grammar.
 */
object MediaParser {
    private val seasonEpisode = Regex(
        pattern = "^(.+?)[. _\\-][Ss](\\d{1,2})[Ee](\\d{1,2})",
        option = RegexOption.IGNORE_CASE,
    )
    private val numberXNumber = Regex(
        pattern = "^(.+?)[. _\\-](\\d{1,2})x(\\d{2})(?:[. _\\-]|$)",
        option = RegexOption.IGNORE_CASE,
    )
    private val year = Regex("[. _\\[(]((19|20)\\d{2})(?:[. _\\])]|$)")
    private val whitespace = Regex("\\s+")

    fun parse(fileName: String): ParsedMedia {
        val baseName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.', missingDelimiterValue = fileName
                .substringAfterLast('/')
                .substringAfterLast('\\'))

        listOf(seasonEpisode, numberXNumber).forEach { pattern ->
            val match = pattern.find(baseName) ?: return@forEach
            return ParsedMedia.Episode(
                showName = cleanTitle(match.groupValues[1]),
                season = match.groupValues[2].toIntOrNull() ?: 1,
                episode = match.groupValues[3].toIntOrNull() ?: 1,
            )
        }

        val yearMatch = year.find(baseName)
        return if (yearMatch != null) {
            ParsedMedia.Movie(
                title = cleanTitle(baseName.substring(0, yearMatch.range.first)),
                year = yearMatch.groupValues[1].toIntOrNull(),
            )
        } else {
            ParsedMedia.Movie(title = cleanTitle(baseName), year = null)
        }
    }

    private fun cleanTitle(raw: String): String = raw
        .replace('.', ' ')
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(whitespace, " ")
        .trim()
}
