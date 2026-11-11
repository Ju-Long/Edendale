package com.babasama.edendale.android

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.saveable.Saver
import com.babasama.edendale.domain.MediaType

/**
 * One part of the Downloaded page shown on its own: the Downloaded row's
 * children in extended navigation (J.1), and the choices of the page's
 * section picker at every width (27.1). A null section is the whole page.
 */
enum class DownloadedSection(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    /** The picker's label, where four choices share one phone-width row. TalkBack still reads [title]. */
    @StringRes val shortTitle: Int = title,
) {
    CONTINUE_WATCHING(R.string.section_continue_watching, R.drawable.ic_hourglass_half, R.string.section_watching_short),
    MOVIES(R.string.section_movies, R.drawable.ic_film),
    SHOWS(R.string.section_tv_shows, R.drawable.ic_tv, R.string.section_shows_short),
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

        /** The section picker's choices: the whole page (null), then each section with something to show. */
        fun pickerChoices(sections: List<DownloadedSection>): List<DownloadedSection?> = listOf(null) + sections

        /** Offered once two sections have something to show; with one, the whole page and that section list the same titles. */
        fun isPickerOffered(sections: List<DownloadedSection>): Boolean = sections.size > 1
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

    /**
     * What a window shows for this row. Watchlist sections exist only as
     * extended navigation's rows, so elsewhere they show their whole page;
     * Downloaded's are also the page's own section picker (27.1), so they
     * show at every width.
     */
    fun shown(extendedNavigation: Boolean): NavigationItem =
        if (extendedNavigation || this is Downloaded) this else page

    /**
     * Whether [tab]'s own navigation row is lit while this is on screen.
     * Extended navigation lists the sections as rows ([sectionRows]), so a
     * section lights its row and the tab's row only its whole page. A rail, a
     * bottom bar, and the TV strip list no sections, so there the tab stays
     * lit while the page's picker shows one of its sections (27.1).
     */
    fun lights(tab: AppTab, sectionRows: Boolean): Boolean =
        if (sectionRows) this == of(tab) else this.tab == tab

    /**
     * A section that has emptied falls back to its whole page, and so does a
     * Downloaded section once the picker stops offering a choice where no
     * navigation row lists it ([sectionRows] false): the page would be stuck
     * on it with nothing to pick. A null list is one still loading, whose
     * sections are left alone.
     */
    fun resolved(
        watchlistSections: List<WatchlistSection>?,
        downloadedSections: List<DownloadedSection>?,
        sectionRows: Boolean = true,
    ): NavigationItem = when {
        this is Watchlist && section != null && watchlistSections != null && section !in watchlistSections -> Watchlist()
        this is Downloaded && section != null && downloadedSections != null && (
            section !in downloadedSections || !sectionRows && !DownloadedSection.isPickerOffered(downloadedSections)
            ) -> Downloaded()
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
