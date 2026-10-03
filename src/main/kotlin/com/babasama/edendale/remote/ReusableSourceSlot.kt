package com.babasama.edendale.remote

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps one [Closeable] source alive across a data source's close → open for
 * the same key (D.1). Media3 reuses one `DataSource` per media period and
 * reopens it on every seek; keeping the buffered source means its connection
 * and cache — including the container index at the end of the file — survive
 * the seek. A different key replaces the source at once; an idle source is
 * released after [idleMillis].
 */
class ReusableSourceSlot<S : Closeable>(
    private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
    private val schedule: (delayMillis: Long, task: () -> Unit) -> Cancellable = ::scheduleOnSharedTimer,
) {
    fun interface Cancellable {
        fun cancel()
    }

    private var key: String? = null
    private var source: S? = null
    private var pendingRelease: Cancellable? = null

    /** The source for [key]: the kept one when the key matches, else a new one from [create]. */
    @Synchronized
    fun acquire(key: String, create: () -> S): S {
        pendingRelease?.cancel()
        pendingRelease = null
        val current = source
        if (current != null && this.key == key) return current
        current?.let { runCatching { it.close() } }
        return create().also {
            this.key = key
            source = it
        }
    }

    /** Keeps the source for a quick reopen, releasing it after [idleMillis]. */
    @Synchronized
    fun releaseLater() {
        if (source == null) return
        pendingRelease?.cancel()
        var task: Cancellable? = null
        task = schedule(idleMillis) {
            synchronized(this) {
                if (pendingRelease === task) {
                    pendingRelease = null
                    releaseNowLocked()
                }
            }
        }
        pendingRelease = task
    }

    /** Releases the source at once, as after a failed open. */
    @Synchronized
    fun releaseNow() {
        pendingRelease?.cancel()
        pendingRelease = null
        releaseNowLocked()
    }

    private fun releaseNowLocked() {
        source?.let { runCatching { it.close() } }
        source = null
        key = null
    }

    @get:Synchronized
    val hasSource: Boolean get() = source != null

    companion object {
        const val DEFAULT_IDLE_MILLIS = 30_000L

        private val timer: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "Edendale.SourceRelease").apply { isDaemon = true }
            }
        }

        private fun scheduleOnSharedTimer(delayMillis: Long, task: () -> Unit): Cancellable {
            val future = timer.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
            return Cancellable { future.cancel(false) }
        }
    }
}
