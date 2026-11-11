package com.babasama.edendale.android.parental

import android.content.Context
import android.os.SystemClock
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.android.AndroidAudiencePreferenceStore
import com.babasama.edendale.android.AudiencePreferenceStore
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.parental.CachedCertificationLists
import com.babasama.edendale.parental.CertificationLists
import com.babasama.edendale.parental.ParentalClock
import com.babasama.edendale.parental.ParentalCodec
import com.babasama.edendale.parental.ParentalPolicy
import com.babasama.edendale.parental.ParentalRules
import com.babasama.edendale.parental.PinHasher
import com.babasama.edendale.parental.PinRateLimit
import com.babasama.edendale.parental.PinRecord
import com.babasama.edendale.parental.PinResult
import com.babasama.edendale.parental.SystemParentalClock
import com.babasama.edendale.sync.SyncLog
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Where the certification lists stand, for the picker's loading and error lines (O.6). */
enum class ListsStatus { IDLE, LOADING, FAILED }

/**
 * F2's API on Android (FEATURES-27.1.md §4; Section O). Device-local: nothing
 * here is synced, handed off, sent anywhere, or logged. Times are wall-clock
 * millis so a caller can show a countdown against the same clock it reads.
 */
interface ParentalControls {
    /** False until the store has been read; the filter fails closed meanwhile and gates should wait. */
    val ready: StateFlow<Boolean>

    val policy: StateFlow<ParentalPolicy>

    val hasPin: StateFlow<Boolean>

    /** When the back-off ends, or null while an attempt may be made (O-D3). */
    val lockedUntilMillis: StateFlow<Long?>

    /** The region's two ordered lists once fetched or read back from the store (O-D5). */
    val lists: StateFlow<CertificationLists?>

    val listsStatus: StateFlow<ListsStatus>

    /** True with no PIN set, or within five minutes of a correct PIN until [lock] (O-D4). */
    fun isUnlocked(now: Long): Boolean

    /** The Settings sheet closed: the next gated action asks again. */
    fun lock()

    suspend fun verifyPin(pin: CharArray, now: Long): PinResult

    /** Stores [new] (4–8 digits); [current] is required while a PIN is set. Both arrays are wiped. */
    suspend fun setPin(current: CharArray?, new: CharArray, now: Long): PinResult

    suspend fun clearPin(current: CharArray, now: Long): PinResult

    /** Every policy change goes through here; false, and nothing changes, while a PIN is set and the controls are locked. */
    suspend fun updatePolicy(now: Long, change: (ParentalPolicy) -> ParentalPolicy): Boolean

    /** Fetches the lists when none are cached, they are a day old, or the device region changed; [force] fetches regardless. */
    suspend fun refreshLists(force: Boolean = false)
}

/** Suspends until the store has been read. */
suspend fun ParentalControls.awaitReady() {
    ready.first { it }
}

/**
 * The controls over a [ParentalStore], with the preset in its own store
 * (O-D7). The store is read once, off the caller's thread; hashing runs on
 * [hashDispatcher] and writes on [ioDispatcher]. One mutex orders every PIN
 * attempt and policy write, so attempts are serial and a lockout cannot be
 * raced; the lists have their own.
 */
class DeviceParentalControls(
    private val store: ParentalStore,
    private val preset: AudiencePreferenceStore,
    private val listsSource: suspend (MediaType) -> JsonObject?,
    private val hasher: PinHasher = PinHasher(),
    private val clock: ParentalClock = SystemParentalClock,
    private val regionProvider: () -> String = { "" },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hashDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ParentalControls {
    private val _ready = MutableStateFlow(false)
    private val _policy = MutableStateFlow(ParentalPolicy(youngAudienceFriendly = preset.enabled))
    private val _hasPin = MutableStateFlow(false)
    private val _lockedUntilMillis = MutableStateFlow<Long?>(null)
    private val _lists = MutableStateFlow<CertificationLists?>(null)
    private val _listsStatus = MutableStateFlow(ListsStatus.IDLE)

    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    override val policy: StateFlow<ParentalPolicy> = _policy.asStateFlow()
    override val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()
    override val lockedUntilMillis: StateFlow<Long?> = _lockedUntilMillis.asStateFlow()
    override val lists: StateFlow<CertificationLists?> = _lists.asStateFlow()
    override val listsStatus: StateFlow<ListsStatus> = _listsStatus.asStateFlow()

    private val mutex = Mutex()
    private val listsMutex = Mutex()
    private var pinRecord: PinRecord? = null
    private var rateLimit = PinRateLimit()

    @Volatile
    private var cached: CachedCertificationLists? = null

    @Volatile
    private var unlockedUntil: Long? = null

    @Volatile
    private var lastListsFailure: Long = NO_FAILURE

    // The preset is written through updatePolicy, but a restored backup or an
    // older build's row may change it underneath; the policy follows the file.
    @Suppress("unused")
    private val presetListener = preset.addListener {
        _policy.update { it.copy(youngAudienceFriendly = preset.enabled) }
    }

    init {
        scope.launch { load() }
    }

    private class Loaded(
        val pin: PinRecord?,
        val rateLimit: PinRateLimit,
        val policy: ParentalPolicy,
        val lists: CachedCertificationLists?,
    )

    private suspend fun load() {
        val loaded = withContext(ioDispatcher) {
            Loaded(
                pin = PinRecord.decode(store.getString(KEY_PIN)),
                rateLimit = PinRateLimit(
                    failures = store.getLong(KEY_FAILURES, 0L).toInt(),
                    lockedUntilWall = store.getLong(KEY_LOCKED_UNTIL_WALL, 0L),
                    lockedUntilElapsed = store.getLong(KEY_LOCKED_UNTIL_ELAPSED, 0L),
                ),
                policy = ParentalCodec.decodePolicy(store.getString(KEY_POLICY), preset.enabled),
                lists = ParentalCodec.decodeLists(store.getString(KEY_LISTS)),
            )
        }
        val wantsLists = mutex.withLock {
            pinRecord = loaded.pin
            rateLimit = loaded.rateLimit
            cached = loaded.lists
            _hasPin.value = loaded.pin != null
            _lists.value = loaded.lists?.lists
            // The preset is read again: it may have changed while the store was loading.
            _policy.value = loaded.policy.copy(youngAudienceFriendly = preset.enabled)
            publishLockout(clock.wallMillis())
            _ready.value = true
            loaded.policy.ceiling?.isEmpty == false
        }
        if (wantsLists) refreshLists()
    }

    override fun isUnlocked(now: Long): Boolean = !_hasPin.value || (unlockedUntil?.let { now < it } == true)

    override fun lock() {
        unlockedUntil = null
    }

    override suspend fun verifyPin(pin: CharArray, now: Long): PinResult {
        awaitReady()
        return mutex.withLock {
            val record = pinRecord ?: run {
                PinHasher.wipe(pin)
                return PinResult.NoPin
            }
            attempt(pin, record, now)
        }
    }

    override suspend fun setPin(current: CharArray?, new: CharArray, now: Long): PinResult {
        awaitReady()
        if (!PinHasher.isValidPin(new)) {
            PinHasher.wipe(new)
            current?.let(PinHasher::wipe)
            return PinResult.InvalidPin
        }
        return mutex.withLock {
            val record = pinRecord
            if (record != null) {
                if (current == null) {
                    PinHasher.wipe(new)
                    return PinResult.PinRequired
                }
                val result = attempt(current, record, now)
                if (result !is PinResult.Unlocked) {
                    PinHasher.wipe(new)
                    return result
                }
            } else {
                current?.let(PinHasher::wipe)
            }
            val stored = withContext(hashDispatcher) { hasher.hash(new) }
            rateLimit = PinRateLimit()
            withContext(ioDispatcher) {
                store.putString(KEY_PIN, stored.encode())
                writeRateLimit()
            }
            pinRecord = stored
            _hasPin.value = true
            _lockedUntilMillis.value = null
            val until = now + UNLOCK_WINDOW_MILLIS
            unlockedUntil = until
            SyncLog.log(TAG, if (record == null) "PIN set" else "PIN changed")
            PinResult.Unlocked(until)
        }
    }

    override suspend fun clearPin(current: CharArray, now: Long): PinResult {
        awaitReady()
        return mutex.withLock {
            val record = pinRecord ?: run {
                PinHasher.wipe(current)
                return PinResult.NoPin
            }
            val result = attempt(current, record, now)
            if (result !is PinResult.Unlocked) return result
            withContext(ioDispatcher) { store.remove(KEY_PIN) }
            pinRecord = null
            _hasPin.value = false
            unlockedUntil = null
            SyncLog.log(TAG, "PIN removed")
            result
        }
    }

    override suspend fun updatePolicy(now: Long, change: (ParentalPolicy) -> ParentalPolicy): Boolean {
        awaitReady()
        val next = mutex.withLock {
            if (!isUnlocked(now)) return false
            val current = _policy.value
            val next = change(current)
            if (next != current) {
                withContext(ioDispatcher) {
                    if (next.youngAudienceFriendly != current.youngAudienceFriendly) preset.enabled = next.youngAudienceFriendly
                    store.putString(KEY_POLICY, ParentalCodec.encodePolicy(next))
                }
                _policy.value = next
            }
            next
        }
        // A ceiling needs the lists; fetch them now rather than when the first surface asks.
        if (next.ceiling?.isEmpty == false && listsStale(clock.wallMillis())) scope.launch { refreshLists() }
        return true
    }

    override suspend fun refreshLists(force: Boolean) {
        awaitReady()
        if (!force && !shouldFetch(clock.wallMillis())) return
        listsMutex.withLock {
            if (!force && !shouldFetch(clock.wallMillis())) return
            val region = regionProvider().trim().uppercase()
            _listsStatus.value = ListsStatus.LOADING
            val parsed = try {
                val movie = listsSource(MediaType.MOVIE)
                val tv = listsSource(MediaType.TV)
                if (movie != null && tv != null) ParentalRules.parseCertificationLists(movie, tv, region) else null
            } catch (cancelled: CancellationException) {
                _listsStatus.value = if (cached == null) ListsStatus.FAILED else ListsStatus.IDLE
                throw cancelled
            } catch (error: Exception) {
                SyncLog.log(TAG, "certification lists: ${SyncLog.describe(error)}")
                null
            }
            if (parsed == null) {
                lastListsFailure = clock.wallMillis()
                _listsStatus.value = ListsStatus.FAILED
                SyncLog.log(TAG, "certification lists: unavailable")
                return
            }
            val fetched = CachedCertificationLists(parsed, region, clock.wallMillis())
            withContext(ioDispatcher) { store.putString(KEY_LISTS, ParentalCodec.encodeLists(fetched)) }
            cached = fetched
            _lists.value = parsed
            _listsStatus.value = ListsStatus.IDLE
            SyncLog.log(TAG, "certification lists: ${parsed.region}, ${parsed.movie.size} movie and ${parsed.tv.size} tv labels")
        }
    }

    /** Stale lists, and no failure in the last half minute (a surface's every verification must not retry a dead network). */
    private fun shouldFetch(now: Long): Boolean =
        listsStale(now) && (lastListsFailure == NO_FAILURE || now - lastListsFailure >= RETRY_AFTER_FAILURE_MILLIS)

    private fun listsStale(now: Long): Boolean {
        val current = cached ?: return true
        return current.deviceRegion != regionProvider().trim().uppercase() ||
            now - current.fetchedAt > LISTS_MAX_AGE_MILLIS ||
            current.fetchedAt > now
    }

    /** One attempt against [record] with the mutex held; [pin] is wiped whatever happens. */
    private suspend fun attempt(pin: CharArray, record: PinRecord, now: Long): PinResult {
        val elapsed = clock.elapsedMillis()
        val waiting = rateLimit.retryAfterMillis(now, elapsed)
        if (waiting > 0L) {
            PinHasher.wipe(pin)
            _lockedUntilMillis.value = now + waiting
            return PinResult.LockedOut(waiting)
        }
        val matched = withContext(hashDispatcher) { hasher.verify(pin, record) }
        if (matched) {
            rateLimit = rateLimit.recordSuccess()
            withContext(ioDispatcher) { writeRateLimit() }
            _lockedUntilMillis.value = null
            val until = now + UNLOCK_WINDOW_MILLIS
            unlockedUntil = until
            return PinResult.Unlocked(until)
        }
        rateLimit = rateLimit.recordFailure(now, elapsed)
        withContext(ioDispatcher) { writeRateLimit() }
        val retryAfter = rateLimit.retryAfterMillis(now, elapsed)
        _lockedUntilMillis.value = if (retryAfter > 0L) now + retryAfter else null
        if (retryAfter > 0L) SyncLog.log(TAG, "PIN refused; back-off $retryAfter ms after ${rateLimit.failures} failures")
        return PinResult.Wrong(rateLimit.failures, retryAfter)
    }

    private fun publishLockout(now: Long) {
        val waiting = rateLimit.retryAfterMillis(now, clock.elapsedMillis())
        _lockedUntilMillis.value = if (waiting > 0L) now + waiting else null
    }

    /** Writes the back-off state; the caller is on [ioDispatcher]. */
    private fun writeRateLimit() {
        store.putLong(KEY_FAILURES, rateLimit.failures.toLong())
        store.putLong(KEY_LOCKED_UNTIL_WALL, rateLimit.lockedUntilWall)
        store.putLong(KEY_LOCKED_UNTIL_ELAPSED, rateLimit.lockedUntilElapsed)
    }

    companion object {
        /** O-D4. */
        const val UNLOCK_WINDOW_MILLIS = 5 * 60_000L

        /** The lists are fetched again after a day. */
        const val LISTS_MAX_AGE_MILLIS = 24 * 60 * 60_000L

        /** After a failed fetch, the next automatic attempt waits this long. */
        const val RETRY_AFTER_FAILURE_MILLIS = 30_000L

        const val KEY_PIN = "parental.pin"
        const val KEY_FAILURES = "parental.failures"
        const val KEY_LOCKED_UNTIL_WALL = "parental.lockedUntilWall"
        const val KEY_LOCKED_UNTIL_ELAPSED = "parental.lockedUntilElapsed"
        const val KEY_POLICY = "parental.policy"
        const val KEY_LISTS = "parental.lists"

        private const val TAG = "Parental"

        /** No fetch has failed yet; a sentinel rather than arithmetic on `Long.MIN_VALUE`. */
        private const val NO_FAILURE = Long.MIN_VALUE
    }
}

/** Android's clocks: `SystemClock.elapsedRealtime()` keeps counting through deep sleep, unlike `System.nanoTime()`. */
object AndroidParentalClock : ParentalClock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}

private val sharedLock = Any()

@Volatile
private var shared: ParentalControls? = null

/**
 * The one controls object of the process: the shell, the Settings sheet, the
 * player, and the Watch Next publisher must see the same unlock state and the
 * same policy.
 */
fun parentalControls(context: Context): ParentalControls = shared ?: synchronized(sharedLock) {
    shared ?: DeviceParentalControls(
        store = EncryptedParentalStore(context),
        preset = AndroidAudiencePreferenceStore(context),
        listsSource = AndroidEdendaleCore.certificationLists(),
        clock = AndroidParentalClock,
        regionProvider = { Locale.getDefault().country },
    ).also { shared = it }
}

/** The policy now, and again after every change, for work outside the UI such as the Watch Next row. */
fun parentalPolicyChanges(context: Context): Flow<ParentalPolicy> = parentalControls(context).policy
