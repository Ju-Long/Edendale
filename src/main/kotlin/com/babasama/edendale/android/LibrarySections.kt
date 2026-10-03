package com.babasama.edendale.android

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.saveable.Saver
import com.babasama.edendale.domain.MediaType

/**
 * One part of the Downloaded page shown on its own: the Downloaded row's
 * children in extended navigation (J.1). A null section is the whole page.
 */
enum class DownloadedSection(@StringRes val title: Int, @DrawableRes val icon: Int) {
    CONTINUE_WATCHING(R.string.section_continue_watching, R.drawable.ic_hourglass_half),
    MOVIES(R.string.section_movies, R.drawable.ic_film),
    SHOWS(R.string.section_tv_shows, R.drawable.ic_tv),
    ;

    companion object {
        /** The sections with something to show, in navigation order. */
        fun available(hasResumeItems: Boolean, hasMovies: Boolean, hasShows: Boolean): List<DownloadedSection> =
            entries.filter { section ->
                when (section) {
                    CONTINUE_WATCHING -> hasResumeItems
                    MOVIES -> hasMovies
                    SHOWS -> hasShows
                }
            }
    }
}

/**
 * One group of the Watchlist page shown on its own: the Watchlist row's
 * children in extended navigation (J.1). A null section is the whole page.
 */
enum class WatchlistSection(@StringRes val title: Int, @DrawableRes val icon: Int, val mediaType: MediaType) {
    MOVIES(R.string.section_movies, R.drawable.ic_film, MediaType.MOVIE),
    SHOWS(R.string.section_tv_shows, R.drawable.ic_tv, MediaType.TV),
    ;

    companion object {
        /** The sections holding at least one of [mediaTypes], in navigation order. */
        fun available(mediaTypes: Collection<MediaType>): List<WatchlistSection> =
            entries.filter { it.mediaType in mediaTypes }
    }
}

/**
 * A navigation row: a root tab, or one section of the Watchlist or Downloaded
 * page. Only extended navigation (wide windows) lists the section rows.
 */
internal sealed interface NavigationItem {
    val tab: AppTab

    data object Movies : NavigationItem {
        override val tab get() = AppTab.MOVIES
    }

    data class Watchlist(val section: WatchlistSection? = null) : NavigationItem {
        override val tab get() = AppTab.WATCHLIST
    }

    data class Downloaded(val section: DownloadedSection? = null) : NavigationItem {
        override val tab get() = AppTab.DOWNLOADED
    }

    data object Search : NavigationItem {
        override val tab get() = AppTab.SEARCH
    }

    /** The row's whole page. */
    val page: NavigationItem get() = of(tab)

    /** A section row whose section has emptied falls back to its whole page. */
    fun resolved(
        watchlistSections: List<WatchlistSection>,
        downloadedSections: List<DownloadedSection>,
    ): NavigationItem = when {
        this is Watchlist && section != null && section !in watchlistSections -> Watchlist()
        this is Downloaded && section != null && section !in downloadedSections -> Downloaded()
        else -> this
    }

    companion object {
        /** A route that picks a tab opens its whole page. */
        fun of(tab: AppTab): NavigationItem = when (tab) {
            AppTab.MOVIES -> Movies
            AppTab.WATCHLIST -> Watchlist()
            AppTab.DOWNLOADED -> Downloaded()
            AppTab.SEARCH -> Search
        }

        /** Saves the row as "TAB" or "TAB/SECTION" across configuration changes. */
        val Saver: Saver<NavigationItem, String> = Saver(
            save = { item ->
                val section = when (item) {
                    is Watchlist -> item.section?.name
                    is Downloaded -> item.section?.name
                    else -> null
                }
                listOfNotNull(item.tab.name, section).joinToString("/")
            },
            restore = { saved -> decode(saved) },
        )

        internal fun decode(saved: String): NavigationItem? {
            val tab = AppTab.entries.firstOrNull { it.name == saved.substringBefore('/') } ?: return null
            val section = saved.substringAfter('/', "").ifEmpty { null }
            return when (tab) {
                AppTab.WATCHLIST -> Watchlist(WatchlistSection.entries.firstOrNull { it.name == section })
                AppTab.DOWNLOADED -> Downloaded(DownloadedSection.entries.firstOrNull { it.name == section })
                else -> of(tab)
            }
        }
    }
}
