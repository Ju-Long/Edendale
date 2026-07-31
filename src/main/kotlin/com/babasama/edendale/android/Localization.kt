package com.babasama.edendale.android

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.babasama.edendale.domain.SearchScope
import com.babasama.edendale.tmdb.CollectionFilter
import com.babasama.edendale.wyzie.WyzieException

/**
 * Localization plumbing for the parts of the app that cannot call
 * `stringResource`, including view models and the library repository.
 *
 * Domain enums map exhaustively to Android resources here, so a new enum case
 * fails the build instead of leaking untranslated copy.
 */

/** Strings needed off the Compose thread, resolved against the app context. */
class AppStrings(private val context: Context) {
    // Browse / search failures
    val archiveNotLoaded: String get() = context.getString(R.string.error_archive_not_loaded)
    val detailsNotLoaded: String get() = context.getString(R.string.error_details_not_loaded)
    val seasonNotLoaded: String get() = context.getString(R.string.error_season_not_loaded)
    val filmographyFailed: String get() = context.getString(R.string.filmography_error_message)
    val searchFailed: String get() = context.getString(R.string.error_search_failed)

    // TMDB account
    val savedSignInUnreadable: String get() = context.getString(R.string.tmdb_error_saved_signin)
    val connectionStartFailed: String get() = context.getString(R.string.tmdb_error_start)
    val noBrowser: String get() = context.getString(R.string.tmdb_error_no_browser)
    val approvalRejected: String get() = context.getString(R.string.tmdb_error_approval)
    val syncFailed: String get() = context.getString(R.string.tmdb_error_sync)

    fun syncStatus(time: String, pushed: Int, pulled: Int): String =
        context.getString(R.string.tmdb_sync_status, time, pushed, pulled)

    // Wyzie subtitle search/download. Server text is retained by the service
    // exception for diagnostics and tests, but never surfaced verbatim where
    // it could echo request data.
    fun wyzieError(error: Throwable, download: Boolean): String = when (error) {
        is WyzieException.MissingKey -> context.getString(R.string.wyzie_error_missing_key)
        is WyzieException.BadStatus -> when (error.code) {
            401, 403 -> context.getString(R.string.wyzie_error_authorization)
            else -> context.getString(R.string.wyzie_error_http, error.code)
        }
        is WyzieException.EmptyFile -> context.getString(R.string.wyzie_error_empty_file)
        is WyzieException.FileTooLarge -> context.getString(R.string.wyzie_error_file_too_large)
        is WyzieException.BadUrl -> context.getString(R.string.wyzie_error_bad_url)
        else -> context.getString(
            if (download) R.string.wyzie_error_download else R.string.wyzie_error_search,
        )
    }

    // Library import
    val folderNotOpened: String get() = context.getString(R.string.error_folder_not_opened)
    val defaultFolderName: String get() = context.getString(R.string.default_folder_name)
    val defaultVideoName: String get() = context.getString(R.string.default_video_name)
    val permissionRevoked: String get() = context.getString(R.string.error_permission_revoked)
    val folderUnopenable: String get() = context.getString(R.string.error_folder_unopenable)
    val shareUnreachable: String get() = context.getString(R.string.error_share_unreachable)
    val addressIsFile: String get() = context.getString(R.string.error_address_is_file)
    val scanFailed: String get() = context.getString(R.string.error_scan_failed)

    fun notShareAddress(input: String): String =
        context.getString(R.string.error_not_share_address, input)

    fun sourceError(displayName: String, reason: String): String =
        context.getString(R.string.error_source_scan, displayName, reason)

    fun sourcePartialError(displayName: String): String =
        context.getString(R.string.error_source_partial, displayName)
}

/** Localized chip copy for a search scope. */
@StringRes
fun SearchScope.labelRes(): Int = when (this) {
    SearchScope.ALL -> R.string.scope_all
    SearchScope.PEOPLE -> R.string.scope_people
    SearchScope.MOVIES -> R.string.scope_films
    SearchScope.SHOWS -> R.string.scope_series
}

/**
 * Collection-filter copy. Genre filters keep TMDB's own name — TMDB returns it
 * already localized for the requested language — so only the fixed cases map to
 * the catalogue.
 */
@Composable
fun CollectionFilter.localizedTitle(): String = when (this) {
    is CollectionFilter.All -> stringResource(R.string.collection_all_archives)
    is CollectionFilter.Movies -> stringResource(R.string.collection_feature_films)
    is CollectionFilter.Shows -> stringResource(R.string.collection_series)
    is CollectionFilter.ByGenre -> genre.name
}

/**
 * Runtime formatter for [mediaSubtitle] and friends, which stay pure so the
 * hermetic unit tests in `src/test` can reach them without a Context.
 */
@Composable
fun rememberRuntimeFormat(): (Int) -> String {
    val context = LocalContext.current
    return remember(context) { { minutes -> context.getString(R.string.runtime_minutes, minutes) } }
}
