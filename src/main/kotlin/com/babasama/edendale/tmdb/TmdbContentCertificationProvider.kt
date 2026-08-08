package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.MediaRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Caches certification lookups per (title, region) and coalesces overlapping
 * requests, so a background shelf verification and a foreground detail gate for
 * the same title share one network call. A bounded semaphore keeps a full shelf
 * from opening dozens of connections at once. Authentication and transient
 * failures are not cached, so a later verification can retry them.
 */
class TmdbContentCertificationProvider(
    private val api: TmdbApi,
    private val regionProvider: () -> String,
    maximumConcurrentRequests: Int = 8,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : ContentCertificationProvider {

    private data class Key(val ref: MediaRef, val regionCode: String)

    private val gate = Semaphore(maximumConcurrentRequests.coerceAtLeast(1))
    private val lock = Mutex()
    private val cache = mutableMapOf<Key, ContentCertificationLookup>()
    private val inFlight = mutableMapOf<Key, Deferred<ContentCertificationLookup>>()

    override val contextIdentifier: String
        get() = regionProvider().uppercase()

    override suspend fun certification(ref: MediaRef): ContentCertificationLookup {
        val region = contextIdentifier
        if (region.isBlank()) return ContentCertificationLookup.Unrated
        val key = Key(ref, region)

        val request: Deferred<ContentCertificationLookup> = lock.withLock {
            cache[key]?.let { return it }
            inFlight[key] ?: scope.async { lookup(ref, region) }.also { inFlight[key] = it }
        }

        val result = request.await()

        lock.withLock {
            inFlight.remove(key)
            // Auth and transient network failures may recover later.
            if (result != ContentCertificationLookup.Unavailable) cache[key] = result
        }
        return result
    }

    private suspend fun lookup(ref: MediaRef, region: String): ContentCertificationLookup =
        gate.withPermit {
            try {
                val certification = api.contentCertification(ref, region)
                if (certification != null) {
                    ContentCertificationLookup.Found(certification)
                } else {
                    ContentCertificationLookup.Unrated
                }
            } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                ContentCertificationLookup.Unavailable
            }
        }
}
