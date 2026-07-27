package com.babasama.edendale.domain

/**
 * What a search field value is scoped to. [ALL] is the unprefixed default:
 * titles first, people alongside.
 */
enum class SearchScope {
    ALL,
    PEOPLE,
    MOVIES,
    SHOWS,
    ;

    /** Chip copy for the active scope. */
    val label: String
        get() = when (this) {
            ALL -> "All"
            PEOPLE -> "People"
            MOVIES -> "Films"
            SHOWS -> "Series"
        }
}

/**
 * A raw search field value split into the scope its keyword prefix asks for
 * and the [term] TMDB should actually see.
 *
 * The grammar is a keyword, a colon, and the rest of the line — case
 * insensitive, singular or plural, whitespace after the colon optional:
 *
 * ```
 * actors: tom hanks   -> SearchQuery(PEOPLE, "tom hanks")
 * Movies:alien        -> SearchQuery(MOVIES, "alien")
 * Alien: Romulus      -> SearchQuery(ALL, "Alien: Romulus")
 * ```
 *
 * An unrecognised `word:` is deliberately left alone: titles legitimately
 * contain colons, and silently eating "Alien:" would make them unsearchable.
 */
data class SearchQuery(
    val scope: SearchScope,
    val term: String,
) {
    val isScoped: Boolean get() = scope != SearchScope.ALL

    /** True when a prefix was typed but nothing has been searched for yet. */
    val isAwaitingTerm: Boolean get() = isScoped && term.isEmpty()

    companion object {
        /**
         * Recognised prefixes, without the colon. Kept as one flat table so
         * aliases remain explicit and testable.
         */
        private val keywords: Map<String, SearchScope> = mapOf(
            "actor" to SearchScope.PEOPLE,
            "actors" to SearchScope.PEOPLE,
            "actress" to SearchScope.PEOPLE,
            "actresses" to SearchScope.PEOPLE,
            "person" to SearchScope.PEOPLE,
            "people" to SearchScope.PEOPLE,
            "cast" to SearchScope.PEOPLE,
            "movie" to SearchScope.MOVIES,
            "movies" to SearchScope.MOVIES,
            "film" to SearchScope.MOVIES,
            "films" to SearchScope.MOVIES,
            "show" to SearchScope.SHOWS,
            "shows" to SearchScope.SHOWS,
            "tv" to SearchScope.SHOWS,
            "series" to SearchScope.SHOWS,
        )

        fun parse(raw: String): SearchQuery {
            val colon = raw.indexOf(':')
            if (colon < 0) return SearchQuery(SearchScope.ALL, raw.trim())

            val keyword = raw.substring(0, colon).trim().lowercase()
            val scope = keywords[keyword] ?: return SearchQuery(SearchScope.ALL, raw.trim())
            return SearchQuery(scope, raw.substring(colon + 1).trim())
        }
    }
}
