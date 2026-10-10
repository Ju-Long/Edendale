package com.babasama.edendale.remote

import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** D.1: the buffered source survives Media3's close → open on a seek. */
class ReusableSourceSlotTest {

    private class Source(val name: String) : Closeable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    /** A timer the test fires by hand. */
    private class ManualTimer {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val cancelled = mutableSetOf<Int>()

        fun schedule(delay: Long, task: () -> Unit): ReusableSourceSlot.Cancellable {
            tasks += delay to task
            val id = tasks.lastIndex
            return ReusableSourceSlot.Cancellable { cancelled += id }
        }

        fun fireAll() {
            tasks.forEachIndexed { id, (_, task) -> if (id !in cancelled) task() }
        }
    }

    @Test
    fun theSameFileReusesTheSourceAcrossCloseAndOpen() {
        val timer = ManualTimer()
        val slot = ReusableSourceSlot<Source>(schedule = timer::schedule)
        val first = slot.acquire("smb://nas/a.mkv") { Source("a") }
        slot.releaseLater()
        val second = slot.acquire("smb://nas/a.mkv") { Source("again") }
        assertSame(first, second)
        assertFalse(first.closed)
        // The release scheduled at close was cancelled by the reopen.
        timer.fireAll()
        assertFalse(first.closed)
    }

    @Test
    fun anotherFileReplacesTheSourceAtOnce() {
        val slot = ReusableSourceSlot<Source>(schedule = ManualTimer()::schedule)
        val a = slot.acquire("smb://nas/a.mkv") { Source("a") }
        val b = slot.acquire("smb://nas/b.mkv") { Source("b") }
        assertTrue(a.closed)
        assertFalse(b.closed)
        assertEquals("b", b.name)
    }

    @Test
    fun anIdleSourceIsReleasedAfterThirtySeconds() {
        val timer = ManualTimer()
        val slot = ReusableSourceSlot<Source>(schedule = timer::schedule)
        val a = slot.acquire("smb://nas/a.mkv") { Source("a") }
        slot.releaseLater()
        assertEquals(listOf(30_000L), timer.tasks.map { it.first })
        timer.fireAll()
        assertTrue(a.closed)
        assertFalse(slot.hasSource)
        // The next open starts a fresh source.
        val again = slot.acquire("smb://nas/a.mkv") { Source("fresh") }
        assertEquals("fresh", again.name)
    }

    @Test
    fun aFailedOpenReleasesAtOnce() {
        val slot = ReusableSourceSlot<Source>(schedule = ManualTimer()::schedule)
        val a = slot.acquire("smb://nas/a.mkv") { Source("a") }
        slot.releaseNow()
        assertTrue(a.closed)
        assertFalse(slot.hasSource)
    }
}
