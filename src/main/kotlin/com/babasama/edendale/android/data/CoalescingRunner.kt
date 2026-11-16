package com.babasama.edendale.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs [pass] in [scope] when asked, one pass at a time, without dropping a
 * request: any number of requests made while a pass runs yield exactly one
 * more pass after it. The library's TMDB matching runs through this, so
 * titles stored during a pass (another source's scan, a resumed one) are
 * matched by the pass that follows instead of waiting for the next scan.
 */
internal class CoalescingRunner(
    scope: CoroutineScope,
    private val pass: suspend () -> Unit,
) {
    /** Holds at most one waiting request: the ones made during a pass merge into it. */
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    pass()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // A failed pass doesn't stop the passes asked for after it.
                }
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
