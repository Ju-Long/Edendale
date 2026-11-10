package com.babasama.edendale.android.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** The TMDB pass's runner: one pass at a time, and no request dropped. */
class CoalescingRunnerTest {

    /**
     * Runs [block] on runBlocking's single thread with a scope for the
     * runner, cancelled afterwards. Everything here runs on that thread, so
     * a short delay lets any pass that is due start before a count is read.
     */
    private fun runnerTest(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val job = Job(coroutineContext[Job])
        try {
            block(CoroutineScope(coroutineContext + job))
        } finally {
            job.cancel()
        }
    }

    private suspend fun <T> Channel<T>.next(): T = withTimeout(5_000) { receive() }

    @Test
    fun `a request while idle runs one pass`() = runnerTest { scope ->
        val passes = Channel<Int>(Channel.UNLIMITED)
        var count = 0
        val runner = CoalescingRunner(scope) { passes.send(++count) }
        runner.request()
        assertEquals(1, passes.next())
        delay(50)
        assertEquals(1, count)
        runner.request()
        assertEquals(2, passes.next())
    }

    @Test
    fun `requests during a pass run exactly one more`() = runnerTest { scope ->
        listOf(1, 5).forEach { requestsDuringPass ->
            val gate = CompletableDeferred<Unit>()
            val passes = Channel<Int>(Channel.UNLIMITED)
            var count = 0
            val runner = CoalescingRunner(scope) {
                passes.send(++count)
                if (count == 1) gate.await()
            }
            runner.request()
            assertEquals(1, passes.next())
            repeat(requestsDuringPass) { runner.request() }
            gate.complete(Unit)
            assertEquals(2, passes.next())
            delay(50)
            assertEquals(2, count, "$requestsDuringPass request(s) during a pass")
        }
    }

    @Test
    fun `a failed pass doesn't stop later ones`() = runnerTest { scope ->
        val passes = Channel<Int>(Channel.UNLIMITED)
        var count = 0
        val runner = CoalescingRunner(scope) {
            passes.send(++count)
            if (count == 1) error("TMDB unreachable")
        }
        runner.request()
        assertEquals(1, passes.next())
        runner.request()
        assertEquals(2, passes.next())
    }
}
