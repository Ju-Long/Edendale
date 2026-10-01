package com.babasama.edendale.introdb

import com.babasama.edendale.android.player.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

sealed class SkipAction {
    data class Seek(val positionMillis: Long) : SkipAction() {
        val targetSeconds: Double get() = positionMillis.toDouble() / 1000.0
    }
    object Finish : SkipAction()
}

/**
 * Session-only timestamp controller, independent of the video engine and its UI.
 * Handles background lookups, in-memory caching up to 12 items, segment activation,
 * and button-press suppression.
 */
class PlayerSegmentController(
    private val preferences: PlayerPreferences? = null,
    initialEnabled: Boolean = preferences?.segmentPromptsEnabled ?: false,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    var onActiveSegmentChanged: ((PlaybackSegment?) -> Unit)? = null,
    private val lookup: suspend (IntroDbRequest) -> List<PlaybackSegment>,
) {
    companion object {
        const val PREFERENCE_KEY = "player.segmentPromptsEnabled"
    }

    private var prefsSubscription: AutoCloseable? = null

    init {
        prefsSubscription = preferences?.addChangeListener {
            val prefVal = preferences.segmentPromptsEnabled
            if (isEnabled != prefVal) {
                isEnabled = prefVal
            }
        }
    }

    var isEnabled: Boolean = initialEnabled
        set(value) {
            if (field == value) return
            field = value
            preferences?.segmentPromptsEnabled = value
            invalidateLookup()
            if (!value) {
                cache.clear()
            }
            loadIfNeeded()
            notifyActiveSegmentChanged()
        }

    var segments: List<PlaybackSegment> = emptyList()
        private set

    var isLoading: Boolean = false
        private set

    var currentPositionMs: Long = 0L
        private set

    val currentTime: Double get() = currentPositionMs.toDouble() / 1000.0

    private var durationMs: Long? = null
    private var isSeekable: Boolean = false
    private var itemId: Any? = null
    private var media: IntroDbMedia? = null
    private var attemptedRequest: IntroDbRequest? = null
    private var generation: Long = 0L
    private var suppressedSegment: PlaybackSegment? = null
    private var lookupJob: Job? = null
    private val cache = mutableMapOf<IntroDbRequest, List<PlaybackSegment>>()

    val activeSegment: PlaybackSegment?
        get() {
            if (!isEnabled || !isSeekable) return null
            val d = durationMs ?: return null
            if (d <= 0) return null
            return segments.firstOrNull { segment ->
                segment != suppressedSegment && segment.endMs <= d && segment.contains(currentPositionMs)
            }
        }

    fun begin(itemId: Any, media: IntroDbMedia?) {
        invalidateLookup()
        this.itemId = itemId
        this.media = media
        currentPositionMs = 0L
        durationMs = null
        isSeekable = false
    }

    fun update(positionMillis: Long, durationMillis: Long?, isSeekable: Boolean) {
        currentPositionMs = positionMillis
        this.durationMs = durationMillis
        this.isSeekable = isSeekable

        val suppressed = suppressedSegment
        if (suppressed != null && !suppressed.contains(positionMillis)) {
            suppressedSegment = null
        }

        loadIfNeeded()
        notifyActiveSegmentChanged()
    }

    fun update(timeSeconds: Double, durationSeconds: Double?, isSeekable: Boolean) {
        val posMs = (timeSeconds * 1000.0).roundToLong()
        val durMs = durationSeconds?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000.0).roundToLong() }
        update(posMs, durMs, isSeekable)
    }

    /**
     * Consumes the active skip prompt. Revalidates against the current position and duration.
     * Suppresses repeat prompts until playback exits this range.
     */
    fun consumeSkip(positionMillis: Long, durationMillis: Long?, isSeekable: Boolean): SkipAction? {
        update(positionMillis, durationMillis, isSeekable)
        val segment = activeSegment ?: return null
        suppressedSegment = segment
        notifyActiveSegmentChanged()
        return if (segment.reachesEnd) SkipAction.Finish else SkipAction.Seek(segment.endMs)
    }

    fun consumeSkip(timeSeconds: Double, durationSeconds: Double?, isSeekable: Boolean): SkipAction? {
        val posMs = (timeSeconds * 1000.0).roundToLong()
        val durMs = durationSeconds?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000.0).roundToLong() }
        return consumeSkip(posMs, durMs, isSeekable)
    }

    fun end() {
        prefsSubscription?.close()
        prefsSubscription = null
        invalidateLookup()
        itemId = null
        media = null
        durationMs = null
        isSeekable = false
        cache.clear()
        notifyActiveSegmentChanged()
    }

    private fun notifyActiveSegmentChanged() {
        onActiveSegmentChanged?.invoke(activeSegment)
    }

    private fun invalidateLookup() {
        generation++
        lookupJob?.cancel()
        lookupJob = null
        segments = emptyList()
        attemptedRequest = null
        suppressedSegment = null
        isLoading = false
    }

    private fun loadIfNeeded() {
        val currentMedia = media ?: return
        val currentDuration = durationMs ?: return
        if (!isEnabled || itemId == null || attemptedRequest != null) return

        val request = IntroDbRequest.createFromMillis(currentMedia, currentDuration) ?: return
        attemptedRequest = request

        val cached = cache[request]
        if (cached != null) {
            segments = cached
            notifyActiveSegmentChanged()
            return
        }

        isLoading = true
        val currentGen = generation
        lookupJob = scope.launch {
            try {
                val result = lookup(request)
                if (generation != currentGen) return@launch
                if (cache.size >= 12) {
                    cache.clear()
                }
                cache[request] = result
                segments = result
                isLoading = false
                notifyActiveSegmentChanged()
            } catch (_: CancellationException) {
                // Ignore coroutine cancellation
            } catch (_: Exception) {
                if (generation != currentGen) return@launch
                isLoading = false
                notifyActiveSegmentChanged()
            }
        }
    }
}
