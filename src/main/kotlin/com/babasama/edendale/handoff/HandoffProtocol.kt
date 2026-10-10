package com.babasama.edendale.handoff

import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudAccountVault
import com.babasama.edendale.oauth.CloudTokenProvider
import com.babasama.edendale.oauth.OAuthClient
import com.babasama.edendale.oauth.OAuthConfiguration
import com.babasama.edendale.oauth.OAuthException
import com.babasama.edendale.remote.RemoteHttp
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/** Length-prefixed frames over a stream ([AccountHandoff.frame]). */
class FrameStream(input: InputStream, private val output: OutputStream) {
    private val input = DataInputStream(input)

    fun write(body: ByteArray) {
        output.write(AccountHandoff.frame(body))
        output.flush()
    }

    /** One frame's body; a closed connection reads as a malformed message. */
    fun read(): ByteArray {
        val header = ByteArray(4)
        try {
            input.readFully(header)
            return ByteArray(AccountHandoff.bodyLength(header)).also(input::readFully)
        } catch (error: EOFException) {
            throw HandoffException.MalformedMessage
        }
    }
}

/**
 * The two ends of one handoff over a connected socket (I.2.2). Both sides
 * run the key agreement (phone first), then the TV sends its request and the
 * phone its answer, and the TV says what it did with it. Blocking: each side
 * runs on its own IO thread.
 */
object HandoffProtocol {
    /** The key agreement's reads: the other side is a program, not a person. */
    const val HANDSHAKE_TIMEOUT_MILLIS = 30_000

    /** The TV's wait for the phone's answer, which may include a whole sign-in. */
    const val RESPONSE_TIMEOUT_MILLIS = 10 * 60_000

    /** The phone's wait for the TV to validate and store what it sent. */
    const val RESULT_TIMEOUT_MILLIS = 2 * 60_000

    /**
     * The TV side: agrees the key with [code], sends [request], and hands the
     * phone's approved answer to [store], whose result goes back to the
     * phone. Throws [HandoffException.WrongCode] after telling the phone,
     * [HandoffException.Declined] when the viewer said no, and
     * [HandoffException.TimedOut] when the phone never answered.
     */
    fun receive(socket: Socket, code: String, request: AccountHandoff.Request, store: (AccountHandoff.Response) -> AccountHandoff.Result): AccountHandoff.Response {
        val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
        val pake = HandoffCrypto.Pake(HandoffCrypto.TV, code)
        socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
        timed {
            pake.receiveRound1(frames.read())
            frames.write(pake.round1())
            pake.receiveRound2(frames.read())
            frames.write(pake.round2())
            val confirmation = frames.read()
            try {
                pake.receiveRound3(confirmation)
            } catch (wrong: HandoffException.WrongCode) {
                runCatching { frames.write(HandoffCrypto.wrongCodeFrame()) }
                throw wrong
            }
            frames.write(pake.round3())
        }
        val channel = SecureChannel.forTelevision(pake.sessionKey())
        frames.write(channel.seal(AccountHandoff.encodeRequest(request)))

        socket.soTimeout = RESPONSE_TIMEOUT_MILLIS
        val response = timed { AccountHandoff.decodeResponse(channel.open(frames.read())) }
        if (response.status != AccountHandoff.Status.APPROVED) throw HandoffException.Declined
        val answeredKind = response.account?.kind ?: response.login?.kind
        if (answeredKind != request.kind) {
            runCatching { frames.write(channel.seal(AccountHandoff.encodeResult(AccountHandoff.Result(stored = false)))) }
            throw HandoffException.WrongKind
        }
        val result = store(response)
        frames.write(channel.seal(AccountHandoff.encodeResult(result)))
        return response
    }

    /**
     * The phone side: agrees the key with [code], reads the TV's request,
     * asks [respond] (which may wait for the viewer and a sign-in), sends the
     * answer, and returns the TV's result. A declined answer returns null.
     */
    fun send(socket: Socket, code: String, respond: (AccountHandoff.Request) -> AccountHandoff.Response): Exchange {
        val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
        val pake = HandoffCrypto.Pake(HandoffCrypto.PHONE, code)
        socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
        timed {
            frames.write(pake.round1())
            pake.receiveRound1(frames.read())
            frames.write(pake.round2())
            pake.receiveRound2(frames.read())
            frames.write(pake.round3())
            pake.receiveRound3(frames.read())
        }
        val channel = SecureChannel.forPhone(pake.sessionKey())
        val request = timed { AccountHandoff.decodeRequest(channel.open(frames.read())) }
        val response = respond(request)
        frames.write(channel.seal(AccountHandoff.encodeResponse(response)))
        if (response.status != AccountHandoff.Status.APPROVED) return Exchange(request, null)
        socket.soTimeout = RESULT_TIMEOUT_MILLIS
        val result = timed { AccountHandoff.decodeResult(channel.open(frames.read())) }
        if (!result.stored) throw HandoffException.Rejected(result.message)
        return Exchange(request, result)
    }

    /** What the phone learned: the TV's request, and its result once the answer was stored (null when declined). */
    data class Exchange(val request: AccountHandoff.Request, val result: AccountHandoff.Result?)

    private inline fun <T> timed(block: () -> T): T = try {
        block()
    } catch (error: SocketTimeoutException) {
        throw HandoffException.TimedOut
    }
}

/**
 * What the TV does with a handed-over account (Apple's
 * `adoptHandedOffAccount`): refreshes the phone's token to prove it works,
 * then stores the account; a token the provider refuses is rejected rather
 * than stored. The phone's refresh token is reused, not exchanged for another.
 */
suspend fun adoptHandedOffAccount(
    account: CloudAccount,
    configuration: OAuthConfiguration?,
    http: RemoteHttp,
    vault: CloudAccountVault,
    tokens: CloudTokenProvider,
    rejectedMessage: (OAuthException) -> String? = { it.message },
): CloudAccount {
    val settings = configuration ?: throw HandoffException.Rejected(null)
    val response = try {
        OAuthClient(settings, http).refresh(account.refreshToken)
    } catch (error: OAuthException) {
        throw HandoffException.Rejected(rejectedMessage(error))
    } catch (error: IOException) {
        throw HandoffException.Rejected(error.message)
    }
    // Microsoft rotates refresh tokens; keep the newest one.
    val stored = response.refreshToken?.takeIf { it.isNotEmpty() }?.let { account.copy(refreshToken = it) } ?: account
    vault.save(stored)
    tokens.store(response, stored)
    return stored
}
