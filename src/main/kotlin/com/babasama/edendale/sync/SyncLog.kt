package com.babasama.edendale.sync

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one place sync and the handoff log through (SYNC-PROTOCOL.md §8.5;
 * FEATURES-27.1.md H10). Lines carry counts, file names, stamps, and error
 * classes, never record contents, review text, titles, tokens, codes, keys,
 * or passwords; `LogHygieneTest` runs a whole handoff and sync through a
 * capturing sink and checks. The app installs its sink at startup; tests
 * capture.
 */
object SyncLog {
    fun interface Sink {
        fun log(tag: String, message: String)
    }

    @Volatile
    var sink: Sink = Sink { _, _ -> }

    fun log(tag: String, message: String) = sink.log(tag, message)

    /** What an exception adds to a log line: its class, never its message, which may quote a URL or a server's words. */
    fun describe(error: Throwable): String = error.javaClass.simpleName

    /** A sink that keeps every line, for tests. */
    class Recording : Sink {
        val lines = CopyOnWriteArrayList<String>()
        override fun log(tag: String, message: String) {
            lines += "$tag: $message"
        }
    }
}
