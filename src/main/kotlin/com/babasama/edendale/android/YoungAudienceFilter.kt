package com.babasama.edendale.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.android.parental.ParentalControls
import com.babasama.edendale.android.parental.awaitReady
import com.babasama.edendale.android.parental.parentalControls
import com.babasama.edendale.android.parental.parentalPolicyChanges
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.parental.AudienceDecision
import com.babasama.edendale.parental.CertificationLists
import com.babasama.edendale.parental.ParentalClock
import com.babasama.edendale.parental.ParentalPolicy
import com.babasama.edendale.parental.ParentalRules
import com.babasama.edendale.parental.SystemParentalClock
import com.babasama.edendale.tmdb.ContentCertificationProvider
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The subset of [items] a viewer may see. When hiding is off the source
 * list is returned verbatim; when on, only titles verified as allowed
 * survive, so unknown titles stay hidden until verification lands
 * (fail-closed).
 */
fun visibleItems(
    items: List<MediaItem>,
    decisions: Map<MediaRef, AudienceDecision>,
    enabled: Boolean,
): List<MediaItem> =
    if (!enabled) items else items.filter { decisions[it.ref] == AudienceDecision.ALLOWED }

/**
 * Persists the Young Audience preset, which stays in `edendale_audience` and
 * rides backup as it always has (O-D7); a seam so tests need no Android
 * SharedPreferences.
 */
interface AudiencePreferenceStore {
    var enabled: Boolean

    /** Calls [listener] after every change until the returned handle is closed. */
    fun addListener(listener: () -> Unit): AutoCloseable
}

/**
 * The one enforcement engine for the parental policy (Section O): a cache of
 * TMDB certification decisions under the policy and the region's lists,
 * which every surface consults. The policy and the lists come from
 * [controls]; until the controls have read their store the filter fails
 * closed, so a cold start cannot flash a restricted title. The controls'
 * flows are pushed into snapshot state through [scope], so a change made in
 * Settings recomposes every surface. Parity with Apple's `YoungAudienceFilter`.
 */
class YoungAudienceFilter(
    private val provider: ContentCertificationProvider,
    private val controls: ParentalControls,
    private val scope: CoroutineScope,
    private val clock: ParentalClock = SystemParentalClock,
) {
    // Null until the controls have read their store: every title is then "verifying".
    private var policyState by mutableStateOf(currentPolicy())
    private var listsState by mutableStateOf(controls.lists.value)
    private var decisions by mutableStateOf<Map<MediaRef, AudienceDecision>>(emptyMap())
    private var contextIdentifierState by mutableStateOf(contextKey(policyState, listsState))

    init {
        scope.launch {
            combine(controls.ready, controls.policy, controls.lists) { _, _, _ -> }.collect { refreshPreference() }
        }
    }

    /** The policy in force, or null while the controls are still reading their store. */
    val policy: ParentalPolicy?
        get() {
            synchronizeContext()
            return policyState
        }

    /**
     * True while a title rule is in force (`policy.isActive`), and while the
     * policy is still unknown. Setting it toggles the Young Audience preset
     * through the controls, which refuse while a PIN is set and locked; the
     * value follows the controls, never the caller.
     */
    var isEnabled: Boolean
        get() {
            synchronizeContext()
            return policyState?.isActive ?: true
        }
        set(value) {
            scope.launch {
                controls.updatePolicy(clock.wallMillis()) { it.copy(youngAudienceFriendly = value) }
            }
        }

    /** Reads the controls' policy and lists again; a change clears the cached decisions. */
    fun refreshPreference() {
        synchronizeContext()
    }

    /** The region, policy, and lists the cached decisions belong to; a change of any clears them. */
    val contextIdentifier: String
        get() {
            synchronizeContext()
            return contextIdentifierState
        }

    /**
     * Whether the policy permits [ref]: what the detail page and playback
     * gate on. An inactive policy is a synchronous, zero-network bypass.
     */
    fun allows(ref: MediaRef): Boolean {
        synchronizeContext()
        return !isEnabled || decisions[ref] == AudienceDecision.ALLOWED
    }

    /**
     * Whether a list may show [ref]: everything while the policy shows
     * restricted titles rather than hiding them, otherwise only what [allows].
     */
    fun shows(ref: MediaRef): Boolean {
        synchronizeContext()
        return !hidesRestrictedTitles || allows(ref)
    }

    /** Preserves source ordering; restores the original list when off or when restricted titles are shown. */
    fun visible(items: List<MediaItem>): List<MediaItem> {
        synchronizeContext()
        return visibleItems(items, decisions, hidesRestrictedTitles)
    }

    private val hidesRestrictedTitles: Boolean
        get() = isEnabled && (policyState?.hideRestrictedTitles ?: true)

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
     * detail gate never races a background shelf verification. Answers that
     * arrive after the policy, the lists, or the region changed are dropped,
     * since they were decided under the old context.
     */
    suspend fun verify(refs: List<MediaRef>) {
        controls.awaitReady()
        synchronizeContext()
        if (!isEnabled) return
        // A ceiling compares against the region's lists; asking here keeps them
        // in step with the device region, and is a no-op while they are fresh.
        if (policyState?.ceiling?.isEmpty == false) controls.refreshLists()
        synchronizeContext()
        val policy = policyState ?: return
        val lists = listsState
        val context = contextIdentifierState

        val unresolved = refs.toSet().filter {
            val current = decisions[it]
            current == null || current == AudienceDecision.UNAVAILABLE
        }
        if (unresolved.isEmpty()) return

        unresolved.chunked(BATCH_SIZE).forEach { batch ->
            val resolved = coroutineScope {
                batch.map { ref ->
                    async { ref to ParentalRules.decision(provider.certification(ref), ref.mediaType, policy, lists) }
                }.map { it.await() }
            }
            synchronizeContext()
            if (contextIdentifierState != context) return
            decisions = decisions + resolved
        }
    }

    private fun currentPolicy(): ParentalPolicy? = controls.policy.value.takeIf { controls.ready.value }

    private fun contextKey(policy: ParentalPolicy?, lists: CertificationLists?): String =
        "${provider.contextIdentifier}|${policy?.key ?: "loading"}|${lists?.let { "${it.region}#${it.hashCode()}" } ?: "-"}"

    private fun synchronizeContext() {
        val policy = currentPolicy()
        val lists = controls.lists.value
        if (policy != policyState) policyState = policy
        if (lists != listsState) listsState = lists
        val current = contextKey(policy, lists)
        if (current == contextIdentifierState) return
        contextIdentifierState = current
        decisions = emptyMap()
    }

    private companion object {
        const val BATCH_SIZE = 8
    }
}

private const val AUDIENCE_PREFERENCES_NAME = "edendale_audience"
private const val AUDIENCE_KEY_ENABLED = "young_audience_friendly"

/** SharedPreferences-backed preference store for the shipping app. */
internal class AndroidAudiencePreferenceStore(context: Context) : AudiencePreferenceStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(AUDIENCE_PREFERENCES_NAME, Context.MODE_PRIVATE)

    // SharedPreferences holds its listeners weakly; this list keeps them alive
    // for as long as the store does.
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override var enabled: Boolean
        get() = preferences.getBoolean(AUDIENCE_KEY_ENABLED, false)
        set(value) { preferences.edit().putBoolean(AUDIENCE_KEY_ENABLED, value).apply() }

    override fun addListener(listener: () -> Unit): AutoCloseable {
        val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AUDIENCE_KEY_ENABLED || key == null) listener()
        }
        listeners += changeListener
        preferences.registerOnSharedPreferenceChangeListener(changeListener)
        return AutoCloseable {
            preferences.unregisterOnSharedPreferenceChangeListener(changeListener)
            listeners -= changeListener
        }
    }
}

/**
 * A filter over the process's parental controls, for the shell and for work
 * outside the UI such as the Watch Next row (I.3). [scope] outlives the
 * filter's use: it carries the controls' changes into the filter's state.
 */
internal fun youngAudienceFilter(context: Context, scope: CoroutineScope): YoungAudienceFilter {
    val controls = parentalControls(context)
    return YoungAudienceFilter(
        provider = AndroidEdendaleCore.contentCertificationProvider(
            // O-D5: the lists' region once there are lists (it already applied the
            // US fallback), the device's until then, so a title and the ceiling
            // are read in the same vocabulary.
            regionProvider = { ParentalRules.effectiveRegion(Locale.getDefault().country, controls.lists.value) },
        ),
        controls = controls,
        scope = scope,
    )
}

/** Whether a title rule is in force now, and again after every change. */
internal fun audiencePreferenceChanges(context: Context): Flow<Boolean> =
    parentalPolicyChanges(context).map { it.isActive }.distinctUntilChanged()

/**
 * Hosts the one [YoungAudienceFilter] the whole shell shares, so its
 * verification cache survives tab switches and configuration changes, and
 * the controls behind it for the gates (O.5) and the Settings section (O.6).
 * Created once at the app root and passed to every surface.
 */
class AudienceFilterViewModel(application: Application) : AndroidViewModel(application) {
    val controls: ParentalControls = parentalControls(application)
    val filter: YoungAudienceFilter = youngAudienceFilter(application, viewModelScope)
}
