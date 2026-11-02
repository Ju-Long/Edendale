package com.babasama.edendale.handoff

import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.sync.SyncLog
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The two ends of a version-2 handoff over a connected socket (SYNC-PROTOCOL.md
 * §9). The guest connects and speaks first: a `hello` for QR pairing or
 * J-PAKE `round1` for a typed code. Once the channel is up both sides send
 * their role, the sender its offer, and the receiver its result. Blocking:
 * each side runs on its own IO thread. Every exception is a [HandoffException].
 */
object HandoffProtocolV2 {
    /** What the host showed: a QR code (its key pair and secret) and/or a six-digit code. One handoff per code (§9.3, §9.4). */
    class HostPairing(
        val qr: HandoffV2.QrPayload?,
        private val keyPair: HandoffV2.KeyPair?,
        val code: String?,
        private val clock: () -> Long = System::currentTimeMillis,
        private val shownAt: Long = clock(),
    ) {
        @Volatile private var helloSeen = false
        @Volatile private var codeTried = false

        val isExpired: Boolean get() = clock() - shownAt > HandoffV2.CODE_VALIDITY_MILLIS

        /** Establishes the channel from the guest's first frame, which [firstFrame] already read. */
        internal fun establish(frames: FrameStream, firstFrame: ByteArray): SecureChannelV2 {
            if (isExpired) {
                frames.write(HandoffV2.errorFrame(HandoffV2.ErrorReason.SECRET))
                throw HandoffException.WrongSecret
            }
            val (version, type) = HandoffV2.peek(firstFrame)
            if (version != HandoffV2.VERSION) {
                frames.write(HandoffV2.errorFrame(HandoffV2.ErrorReason.VERSION))
                throw HandoffException.UnsupportedVersion(version ?: 0)
            }
            return when (type) {
                "hello" -> establishQr(frames, firstFrame)
                "round1" -> establishCode(frames, firstFrame)
                else -> {
                    frames.write(HandoffV2.errorFrame(HandoffV2.ErrorReason.MALFORMED))
                    throw HandoffException.MalformedMessage
                }
            }
        }

        private fun establishQr(frames: FrameStream, helloFrame: ByteArray): SecureChannelV2 {
            val payload = qr
            val keys = keyPair
            if (payload == null || keys == null || helloSeen) {
                frames.write(HandoffV2.errorFrame(HandoffV2.ErrorReason.SECRET))
                throw HandoffException.WrongSecret
            }
            helloSeen = true
            val hello = HandoffV2.decodeHello(helloFrame)
            val transcript = HandoffV2.transcript(payload.hostPublicKey, hello.guestPublicKey)
            val expected = HandoffV2.proof(payload.secret, transcript)
            if (!HandoffV2.constantTimeEquals(expected, hello.proof)) {
                frames.write(HandoffV2.errorFrame(HandoffV2.ErrorReason.SECRET))
                SyncLog.log(TAG, "QR proof did not verify")
                throw HandoffException.WrongSecret
            }
            val shared = keys.agree(hello.guestPublicKey)
            val key = HandoffV2.key(shared, payload.secret, transcript)
            shared.fill(0)
            frames.write(HandoffV2.confirmFrame(HandoffV2.confirm(key, transcript)))
            SyncLog.log(TAG, "QR pairing established")
            return SecureChannelV2(key, isHost = true)
        }

        private fun establishCode(frames: FrameStream, round1: ByteArray): SecureChannelV2 {
            val code = code
            if (code == null || codeTried) {
                frames.write(HandoffV2.errorFrame(HandoffV2.ErrorReason.CODE))
                throw HandoffException.WrongCode
            }
            codeTried = true
            val pake = HandoffCrypto.Pake(HandoffV2.HOST_ID, code, version = HandoffV2.VERSION)
            pake.receiveRound1(round1)
            frames.write(pake.round1())
            pake.receiveRound2(HandoffV2.checkError(frames.read()))
            frames.write(pake.round2())
            try {
                pake.receiveRound3(HandoffV2.checkError(frames.read()))
            } catch (wrong: HandoffException.WrongCode) {
                runCatching { frames.write(HandoffV2.errorFrame(HandoffV2.ErrorReason.CODE)) }
                SyncLog.log(TAG, "typed code did not verify")
                throw wrong
            }
            frames.write(pake.round3())
            SyncLog.log(TAG, "code pairing established")
            return SecureChannelV2(HandoffV2.codeKey(pake.keyingMaterial()), isHost = true)
        }
    }

    /** What the guest scanned or typed. */
    sealed interface GuestPairing {
        class Qr(val payload: HandoffV2.QrPayload) : GuestPairing
        class Code(val code: String) : GuestPairing
    }

    /** One side's part in the exchange after the channel is up (§9.6). */
    class Participant(
        val role: HandoffV2.Role,
        val name: String,
        val platform: String,
        /** The sender's offer for the receiver named; may wait for the viewer. Null declines. */
        val offer: (peer: HandoffV2.RoleMessage) -> HandoffV2.Offer? = { null },
        /** The receiver's validation and storage of what arrived. */
        val receive: (offer: HandoffV2.Offer, peer: HandoffV2.RoleMessage) -> HandoffV2.ResultMessage = { _, _ -> HandoffV2.ResultMessage(emptyList(), emptyList()) },
    )

    /** What one side learned: the other device, and the result (the sender's copy of what the receiver stored, or the receiver's own). Null result means declined. */
    data class Outcome(val peer: HandoffV2.RoleMessage, val result: HandoffV2.ResultMessage?)

    /**
     * The host side, after it accepted a connection and read the first frame
     * ([firstFrame]) to tell version 1 from 2. Throws [HandoffException.WrongSecret]
     * or [HandoffException.WrongCode] after telling the guest.
     */
    fun host(socket: Socket, frames: FrameStream, firstFrame: ByteArray, pairing: HostPairing, participant: Participant): Outcome {
        socket.soTimeout = HandoffProtocol.HANDSHAKE_TIMEOUT_MILLIS
        val channel = timed { pairing.establish(frames, firstFrame) }
        return exchange(socket, frames, channel, participant)
    }

    /** The guest side, on a socket it just connected. */
    fun guest(socket: Socket, pairing: GuestPairing, participant: Participant): Outcome {
        val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
        socket.soTimeout = HandoffProtocol.HANDSHAKE_TIMEOUT_MILLIS
        val channel = timed {
            when (pairing) {
                is GuestPairing.Qr -> {
                    val keys = HandoffV2.newKeyPair()
                    val transcript = HandoffV2.transcript(pairing.payload.hostPublicKey, keys.publicKey)
                    frames.write(HandoffV2.helloFrame(keys.publicKey, HandoffV2.proof(pairing.payload.secret, transcript)))
                    val mac = HandoffV2.decodeConfirm(frames.read())
                    val shared = keys.agree(pairing.payload.hostPublicKey)
                    val key = HandoffV2.key(shared, pairing.payload.secret, transcript)
                    shared.fill(0)
                    keys.destroy()
                    if (!HandoffV2.constantTimeEquals(HandoffV2.confirm(key, transcript), mac)) throw HandoffException.WrongSecret
                    SecureChannelV2(key, isHost = false)
                }
                is GuestPairing.Code -> {
                    val pake = HandoffCrypto.Pake(HandoffV2.GUEST_ID, pairing.code, version = HandoffV2.VERSION)
                    frames.write(pake.round1())
                    pake.receiveRound1(HandoffV2.checkError(frames.read()))
                    frames.write(pake.round2())
                    pake.receiveRound2(HandoffV2.checkError(frames.read()))
                    frames.write(pake.round3())
                    pake.receiveRound3(HandoffV2.checkError(frames.read()))
                    SecureChannelV2(HandoffV2.codeKey(pake.keyingMaterial()), isHost = false)
                }
            }
        }
        return exchange(socket, frames, channel, participant)
    }

    /** §9.6: roles, then the offer one way and the result back. */
    private fun exchange(socket: Socket, frames: FrameStream, channel: SecureChannelV2, participant: Participant): Outcome {
        frames.write(channel.seal(HandoffV2.encodeRole(HandoffV2.RoleMessage(participant.role, participant.name, participant.platform))))
        val peer = timed { HandoffV2.decodeRole(channel.open(frames.read())) }
        if (peer.role == participant.role) {
            runCatching { frames.write(channel.seal(HandoffV2.channelErrorFrame(HandoffV2.ErrorReason.ROLE))) }
            throw HandoffException.SameRole
        }
        return when (participant.role) {
            HandoffV2.Role.SENDER -> {
                val offer = participant.offer(peer) ?: HandoffV2.Offer.DECLINED
                frames.write(channel.seal(HandoffV2.encodeOffer(offer)))
                if (!offer.approved) {
                    SyncLog.log(TAG, "offer declined by the sender")
                    return Outcome(peer, null)
                }
                SyncLog.log(TAG, "offer sent: location=${offer.location != null}, accounts=${offer.accounts.size}, logins=${offer.logins.size}")
                socket.soTimeout = HandoffProtocol.RESULT_TIMEOUT_MILLIS
                val result = timed { HandoffV2.decodeResult(channel.open(frames.read())) }
                SyncLog.log(TAG, "result: stored=${result.stored.size}, failed=${result.failed.size}")
                Outcome(peer, result)
            }
            HandoffV2.Role.RECEIVER -> {
                socket.soTimeout = HandoffProtocol.RESPONSE_TIMEOUT_MILLIS
                val offer = timed { HandoffV2.decodeOffer(channel.open(frames.read())) }
                if (!offer.approved) {
                    SyncLog.log(TAG, "offer declined by the sender")
                    throw HandoffException.Declined
                }
                val result = participant.receive(offer, peer)
                frames.write(channel.seal(HandoffV2.encodeResult(result)))
                SyncLog.log(TAG, "result sent: stored=${result.stored.size}, failed=${result.failed.size}")
                Outcome(peer, result)
            }
        }
    }

    private inline fun <T> timed(block: () -> T): T = try {
        block()
    } catch (error: SocketTimeoutException) {
        throw HandoffException.TimedOut
    }

    const val TAG = "Handoff"
}
