package com.babasama.edendale.android

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.tmdb.ContentCertificationLookup
import com.babasama.edendale.tmdb.ContentCertificationProvider
import com.babasama.edendale.tmdb.YoungAudienceCertificationPolicy
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The three verification outcomes the filter caches per title. */
enum class AudienceDecision { ALLOWED, BLOCKED, UNAVAILABLE }

/**
 * Maps a certification lookup to a cache decision. Everything that is not
 * verified as PG / PG-13 fails closed: unrated titles are blocked, and network
 * failures are marked unavailable so a later verification can retry them.
 */
fun audienceDecision(
    lookup: ContentCertificationLookup,
    mediaType: MediaType,
): AudienceDecision = when (lookup) {
    is ContentCertificationLookup.Found ->
        if (YoungAudienceCertificationPolicy.allows(lookup.certification, mediaType)) {
            AudienceDecision.ALLOWED
        } else {
            AudienceDecision.BLOCKED
        }
    ContentCertificationLookup.Unrated -> AudienceDecision.BLOCKED
    ContentCertificationLookup.Unavailable -> AudienceDecision.UNAVAILABLE
}

/**
 * The subset of [items] a viewer may see. When the filter is off the source
 * list is returned verbatim; when on, only titles verified as allowed survive,
 * so unknown titles stay hidden until verification lands (fail-closed).
 */
fun visibleItems(
    items: List<MediaItem>,
    decisions: Map<MediaRef, AudienceDecision>,
    enabled: Boolean,
): List<MediaItem> =
    if (!enabled) items else items.filter { decisions[it.ref] == AudienceDecision.ALLOWED }

/** Persists the enabled flag; a seam so tests need no Android SharedPreferences. */
interface AudiencePreferenceStore {
    var enabled: Boolean
}

/**
 * Persisted PG / PG-13 preference plus a cache of TMDB certification
 * verifications. Local mutations of the preference are a synchronous,
 * zero-network bypass; turning it on verifies each title as it enters a
 * surface. Parity with Apple's `YoungAudienceFilter`.
 */
class YoungAudienceFilter(
    private val provider: ContentCertificationProvider,
    private val preferences: AudiencePreferenceStore,
) {
    private val enabledState = mutableStateOf(preferences.enabled)
    private var decisions by mutableStateOf<Map<MediaRef, AudienceDecision>>(emptyMap())
    private var contextIdentifierState by mutableStateOf(provider.contextIdentifier)

    var isEnabled: Boolean
        get() = enabledState.value
        set(value) {
            enabledState.value = value
            preferences.enabled = value
        }

    /** The region the cached decisions belong to; a change clears them. */
    val contextIdentifier: String
        get() {
            synchronizeContext()
            return contextIdentifierState
        }

    /** Turning the preference off is a synchronous, zero-network bypass. */
    fun allows(ref: MediaRef): Boolean {
        synchronizeContext()
        return !isEnabled || decisions[ref] == AudienceDecision.ALLOWED
    }

    /** Preserves source ordering; restores the original list when off. */
    fun visible(items: List<MediaItem>): List<MediaItem> {
        synchronizeContext()
        return visibleItems(items, decisions, isEnabled)
    }

    /**
     * True while any requested title has not been resolved yet. Unknown titles
     * count as verifying so a surface shows a spinner instead of briefly
     * flashing unverified artwork.
     */
    fun isVerifying(refs: List<MediaRef>): Boolean {
        synchronizeContext()
        if (!isEnabled) return false
        return refs.toSet().any { decisions[it] == null }
    }

    /**
     * Resolves every requested ref before returning. Overlapping callers are
     * coalesced by the provider; each caller still awaits its own refs so a
     * detail gate never races a background shelf verification.
     */
    suspend fun verify(refs: List<MediaRef>) {
        synchronizeContext()
        if (!isEnabled) return

        val unresolved = refs.toSet().filter {
            val current = decisions[it]
            current == null || current == AudienceDecision.UNAVAILABLE
        }
        if (unresolved.isEmpty()) return

        unresolved.chunked(BATCH_SIZE).forEach { batch ->
            val resolved = coroutineScope {
                batch.map { ref ->
                    async { ref to audienceDecision(provider.certification(ref), ref.mediaType) }
                }.map { it.await() }
            }
            decisions = decisions + resolved
        }
    }

    private fun synchronizeContext() {
        val current = provider.contextIdentifier
        if (current == contextIdentifierState) return
        contextIdentifierState = current
        decisions = emptyMap()
    }

    private companion object {
        const val BATCH_SIZE = 8
    }
}

/** SharedPreferences-backed preference store for the shipping app. */
private class AndroidAudiencePreferenceStore(context: Context) : AudiencePreferenceStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) { preferences.edit().putBoolean(KEY_ENABLED, value).apply() }

    private companion object {
        const val PREFERENCES_NAME = "edendale_audience"
        const val KEY_ENABLED = "young_audience_friendly"
    }
}

/**
 * Hosts the one [YoungAudienceFilter] the whole shell shares, so its
 * verification cache and preference survive tab switches and configuration
 * changes. Created once at the app root and passed to every surface.
 */
class AudienceFilterViewModel(application: Application) : AndroidViewModel(application) {
    val filter: YoungAudienceFilter = YoungAudienceFilter(
        provider = AndroidEdendaleCore.contentCertificationProvider(
            regionProvider = { Locale.getDefault().country },
        ),
        preferences = AndroidAudiencePreferenceStore(application),
    )
}
