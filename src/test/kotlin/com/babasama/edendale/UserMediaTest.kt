package com.babasama.edendale

import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.UserMediaRecord
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.domain.mergeUserMedia
import com.babasama.edendale.domain.mergeWatchProgress
import com.babasama.edendale.domain.sanitizeTmdbRating
import com.babasama.edendale.tmdb.TmdbAccountApi
import com.babasama.edendale.tmdb.TmdbSession
import com.babasama.edendale.tmdb.TmdbTransport
import com.babasama.edendale.tmdb.UserMediaSyncEngine
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserMediaTest {

    // MARK: Model

    @Test
    fun ratingSanitizesToHalfStepsInsideTmdbDomain() {
        assertEquals(7.5, sanitizeTmdbRating(7.5))
        assertEquals(7.5, sanitizeTmdbRating(7.74))
        assertEquals(10.0, sanitizeTmdbRating(11.0))
        assertEquals(0.5, sanitizeTmdbRating(0.1))
        assertNull(sanitizeTmdbRating(null))
    }

    @Test
    fun pendingUnfavouriteKeepsTheRecordAlive() {
        val record = UserMediaRecord(603, MediaType.MOVIE)
            .settingFavourite(true, nowMillis = 1)
            .settingFavourite(false, nowMillis = 2)
        assertTrue(record.hasState)
        assertTrue(record.favouriteDirty)
        assertFalse(record.copy(favouriteDirty = false).hasState)
    }

    // MARK: Replica merge

    @Test
    fun mergePicksNewestPerFieldIndependently() {
        val local = UserMediaRecord(
            tmdbId = 603, mediaType = MediaType.MOVIE,
            favourite = true, favouriteUpdatedAt = 100,
            rating = 9.0, ratingUpdatedAt = 10,
        )
        val replica = UserMediaRecord(
            tmdbId = 603, mediaType = MediaType.MOVIE,
            favourite = false, favouriteUpdatedAt = 50,
            rating = 7.0, ratingUpdatedAt = 20,
        )

        val merged = mergeUserMedia(listOf(local), listOf(replica)).single()
        assertTrue(merged.favourite)          // local is newer for favourite
        assertEquals(7.0, merged.rating)      // replica is newer for rating
        assertEquals(100, merged.favouriteUpdatedAt)
        assertEquals(20, merged.ratingUpdatedAt)
    }

    @Test
    fun mergeTiePrefersThePendingPushAndKeepsDisjointRecords() {
        val pending = UserMediaRecord(
            tmdbId = 603, mediaType = MediaType.MOVIE,
            watchlist = true, watchlistUpdatedAt = 5, watchlistDirty = true,
        )
        val clean = UserMediaRecord(
            tmdbId = 603, mediaType = MediaType.MOVIE,
            watchlist = false, watchlistUpdatedAt = 5,
        )
        val other = UserMediaRecord(
            tmdbId = 1399, mediaType = MediaType.TV,
            favourite = true, favouriteUpdatedAt = 1,
        )

        val merged = mergeUserMedia(listOf(clean, other), listOf(pending))
        assertEquals(2, merged.size)
        assertTrue(merged.first { it.tmdbId == 603 }.watchlist)
        assertTrue(merged.first { it.tmdbId == 603 }.watchlistDirty)
    }

    @Test
    fun mergePrunesStatelessRecords()  {
        val empty = UserMediaRecord(603, MediaType.MOVIE)
        assertTrue(mergeUserMedia(listOf(empty), emptyList()).isEmpty())
    }

    @Test
    fun watchProgressMergeKeepsTheNewestWritePerTitle() {
        val older = WatchProgress(603, WatchMediaType.MOVIE, position = 0.2, lastWatchedEpochMillis = 100)
        val newer = WatchProgress(603, WatchMediaType.MOVIE, position = 0.8, lastWatchedEpochMillis = 200)
        val other = WatchProgress(42, WatchMediaType.EPISODE, position = 0.5, lastWatchedEpochMillis = 50)

        val merged = mergeWatchProgress(listOf(older, other), listOf(newer))
        assertEquals(2, merged.size)
        assertEquals(0.8, merged.first { it.tmdbId == 603 }.position)
    }

    // MARK: Account API

    @Test
    fun requestTokenBuildsApprovalUrl() = runCommonTest {
        val transport = ScriptedTransport(
            getResponses = mapOf(
                "/authentication/token/new" to """{"success":true,"request_token":"tok123"}""",
            ),
        )
        val auth = TmdbAccountApi(transport).createRequestToken()
        assertEquals("tok123", auth.requestToken)
        assertEquals("https://www.themoviedb.org/authenticate/tok123", auth.approvalUrl)
    }

    @Test
    fun clearingARatingSendsDelete() = runCommonTest {
        val transport = ScriptedTransport()
        TmdbAccountApi(transport).setRating(session, ref(603), value = null)

        val call = transport.sent.single()
        assertEquals("DELETE", call.method)
        assertEquals("/movie/603/rating", call.path)
        assertEquals("abc", call.parameters["session_id"])
    }

    @Test
    fun settingARatingPostsTheSanitizedValue() = runCommonTest {
        val transport = ScriptedTransport()
        TmdbAccountApi(transport).setRating(session, ref(603), value = 8.74)

        val call = transport.sent.single()
        assertEquals("POST", call.method)
        assertEquals("""{"value":8.5}""", call.body)
    }

    // MARK: Sync engine

    @Test
    fun syncPushesDirtyFieldsAndPullsRemoteState() = runCommonTest {
        val transport = ScriptedTransport(
            getResponses = mapOf(
                "/account/7/favorite/movies" to page(item(603, "movie", "The Matrix")),
                "/account/7/favorite/tv" to emptyPage,
                "/account/7/watchlist/movies" to emptyPage,
                "/account/7/watchlist/tv" to page(item(1399, "tv", "Game of Thrones")),
                "/account/7/rated/movies" to page(item(603, "movie", "The Matrix", rating = 9.0)),
                "/account/7/rated/tv" to emptyPage,
            ),
        )
        val engine = UserMediaSyncEngine(TmdbAccountApi(transport))

        // Local: 550 favourited offline (dirty, unknown to TMDB yet); 603 clean.
        val local = listOf(
            UserMediaRecord(550, MediaType.MOVIE, title = "Fight Club")
                .settingFavourite(true, nowMillis = 10),
        )
        val result = engine.sync(session, local, nowMillis = 99)

        // Push: the offline favourite went out exactly once.
        assertEquals(1, result.pushed)
        val push = transport.sent.single()
        assertEquals("/account/7/favorite", push.path)
        assertTrue(push.body!!.contains("\"media_id\":550"))
        assertTrue(push.body!!.contains("\"favorite\":true"))

        // Pull: remote favourite + rating for 603, watchlist for 1399.
        val matrix = result.records.first { it.tmdbId == 603 }
        assertTrue(matrix.favourite)
        assertEquals(9.0, matrix.rating)
        assertEquals("The Matrix", matrix.title)
        val thrones = result.records.first { it.tmdbId == 1399 }
        assertTrue(thrones.watchlist)
        assertEquals(MediaType.TV, thrones.mediaType)

        // The pushed record is clean afterwards.
        val fightClub = result.records.first { it.tmdbId == 550 }
        assertTrue(fightClub.favourite)
        assertFalse(fightClub.favouriteDirty)
    }

    @Test
    fun syncSkipsPushWhenRemoteAlreadyMatchesTheDirtyValue() = runCommonTest {
        val transport = ScriptedTransport(
            getResponses = mapOf(
                "/account/7/favorite/movies" to page(item(603, "movie", "The Matrix")),
                "/account/7/favorite/tv" to emptyPage,
                "/account/7/watchlist/movies" to emptyPage,
                "/account/7/watchlist/tv" to emptyPage,
                "/account/7/rated/movies" to emptyPage,
                "/account/7/rated/tv" to emptyPage,
            ),
        )
        val local = listOf(
            UserMediaRecord(603, MediaType.MOVIE).settingFavourite(true, nowMillis = 10),
        )
        val result = UserMediaSyncEngine(TmdbAccountApi(transport)).sync(session, local, 99)

        assertEquals(0, result.pushed)
        assertTrue(transport.sent.isEmpty())
        assertFalse(result.records.single().favouriteDirty)
    }

    private val session = TmdbSession(sessionId = "abc", accountId = 7)

    private fun ref(id: Int) = com.babasama.edendale.domain.MediaRef(id, MediaType.MOVIE)
}

private fun item(id: Int, type: String, title: String, rating: Double? = null): String {
    val name = if (type == "tv") "\"name\": \"$title\"" else "\"title\": \"$title\""
    val rated = if (rating != null) ", \"rating\": $rating" else ""
    return """{"id": $id, $name, "poster_path": "/p$id.jpg"$rated}"""
}

private fun page(vararg items: String): String =
    """{"page": 1, "total_pages": 1, "results": [${items.joinToString(",")}]}"""

private val emptyPage = """{"page": 1, "total_pages": 1, "results": []}"""

private class ScriptedTransport(
    private val getResponses: Map<String, String> = emptyMap(),
    override val isConfigured: Boolean = true,
) : TmdbTransport {
    data class SentRequest(
        val method: String,
        val path: String,
        val parameters: Map<String, String>,
        val body: String?,
    )

    val sent = mutableListOf<SentRequest>()

    override suspend fun get(path: String, parameters: Map<String, String>): String =
        getResponses[path] ?: error("Unexpected GET $path")

    override suspend fun send(
        method: String,
        path: String,
        parameters: Map<String, String>,
        jsonBody: String?,
    ): String {
        sent += SentRequest(method, path, parameters, jsonBody)
        return """{"success":true}"""
    }
}

/** These fake-transport tests complete synchronously, so no scheduler is needed. */
private fun runCommonTest(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                outcome = result
            }
        },
    )
    checkNotNull(outcome) { "Test coroutine unexpectedly suspended." }.getOrThrow()
}
