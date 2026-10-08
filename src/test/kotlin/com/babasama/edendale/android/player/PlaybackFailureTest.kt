package com.babasama.edendale.android.player

import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.remote.RemoteConnectionLostException
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteOpenException
import com.babasama.edendale.remote.RemoteSourceException
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
    fun aConnectorsRefusalIsReadAsItsFailureNotItsText() {
        // An SFTP copy on a host that's down: the view shows the connector's own message, not "Unreachable(host=…)".
        val unreachable = ConnectorFailure.Unreachable("10.0.2.2")
        val error = RuntimeException("Source error", RemoteOpenException("10.0.2.2", ConnectorException(unreachable)))
        assertEquals(PlaybackFailure.Connector(unreachable), PlaybackFailure.of(error))
        val wrapped = RemoteOpenException("nas", IOException("open", ConnectorException(ConnectorFailure.AuthenticationFailed("nas"))))
        assertEquals(PlaybackFailure.Connector(ConnectorFailure.AuthenticationFailed("nas")), PlaybackFailure.of(wrapped))
        assertEquals(PlaybackFailure.Connector(unreachable), PlaybackFailure.of(ConnectorException(unreachable)))
        // A reconnect that failed the same way keeps only the host.
        val lost = RemoteConnectionLostException("10.0.2.2", ConnectorException(unreachable).message, ConnectorException(unreachable))
        assertEquals(PlaybackFailure.ConnectionLost("10.0.2.2", null), PlaybackFailure.of(lost))
    }

    @Test
    fun aProviderFailureKeepsItsProviderAndReason() {
        val error = RuntimeException("Source error", RemoteSourceException(MediaSourceKind.DROPBOX, RemoteFailure.NotFound))
        assertEquals(PlaybackFailure.Provider(MediaSourceKind.DROPBOX, RemoteFailure.NotFound), PlaybackFailure.of(error))
        assertEquals(
            PlaybackFailure.Provider(MediaSourceKind.S3, RemoteFailure.ServerError(500)),
            PlaybackFailure.of(RemoteSourceException(MediaSourceKind.S3, RemoteFailure.ServerError(500))),
        )
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
