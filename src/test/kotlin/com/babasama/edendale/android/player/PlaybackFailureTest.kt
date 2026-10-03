package com.babasama.edendale.android.player

import com.babasama.edendale.remote.RemoteConnectionLostException
import com.babasama.edendale.remote.RemoteOpenException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** The player's error view names the host when a remote file fails (D.1). */
class PlaybackFailureTest {

    @Test
    fun aLostConnectionIsFoundThroughTheCauseChain() {
        val error = RuntimeException("Source error", IOException("wrapped", RemoteConnectionLostException("nas.local", "Connection reset")))
        assertEquals(PlaybackFailure.ConnectionLost("nas.local", "Connection reset"), PlaybackFailure.of(error))
    }

    @Test
    fun aFailedFirstOpenKeepsTheTransportsWords() {
        val error = RuntimeException(RemoteOpenException("nas.local", IOException("Logon failure: unknown user name or bad password.")))
        assertEquals(
            PlaybackFailure.CouldNotConnect("nas.local", "Logon failure: unknown user name or bad password."),
            PlaybackFailure.of(error),
        )
        assertEquals(PlaybackFailure.CouldNotConnect("nas.local", null), PlaybackFailure.of(RemoteOpenException("nas.local", null)))
    }

    @Test
    fun anythingElseIsGeneric() {
        assertEquals(PlaybackFailure.Other, PlaybackFailure.of(IllegalStateException("decoder")))
        // A cause cycle can't hang the walk.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(PlaybackFailure.Other, PlaybackFailure.of(a))
    }
}
