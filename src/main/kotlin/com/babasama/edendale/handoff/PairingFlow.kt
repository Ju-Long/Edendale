package com.babasama.edendale.handoff

import com.babasama.edendale.connectors.MediaSourceKind
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * The pairing screen's rules, as `apple-27.1`'s `SyncHandoffView` has them
 * (FEATURES-27.1.md §6.2). The direction comes first, from the button the
 * viewer chose (Set Up Another Device sends, Receive from Another Device
 * receives); the sender then ticks what to send. A phone or tablet with a
 * camera scans the other device's QR code or shows its own; a TV, a
 * headset, or a device without a camera shows one. Unlike Apple's, any
 * device can also join by entering the six-digit code the other device
 * shows (ENHANCEMENT.md Section T), so two devices that can only show a
 * code still pair: one shows, the other types.
 */
object PairingFlow {
    /**
     * How this device pairs: it scans the other device's QR code, shows its
     * own, or enters the six-digit code the other device shows (J-PAKE,
     * SYNC-PROTOCOL.md §9.4).
     */
    enum class Mode { SCAN, SHOW, ENTER }

    /**
     * The ways offered first, the first one preselected where there is no
     * choice. Entering a code isn't among them: it is offered from these
     * screens ([offersEntry]), so the scanner stays the first choice where
     * there is a camera.
     */
    fun modes(isTelevision: Boolean, isHeadset: Boolean, hasCamera: Boolean): List<Mode> =
        if (hasCamera && !isTelevision && !isHeadset) listOf(Mode.SCAN, Mode.SHOW) else listOf(Mode.SHOW)

    /**
     * Whether the screen for [mode] offers to enter the other device's code
     * instead: the scanner always (the camera may be refused, or show
     * nothing), and a shown code where this device can't scan. A phone
     * showing its code goes Back to the choice, where the scanner is.
     */
    fun offersEntry(mode: Mode?, modes: List<Mode>): Boolean = when (mode) {
        Mode.SCAN -> true
        Mode.SHOW -> Mode.SCAN !in modes
        Mode.ENTER, null -> false
    }

    /** A version-2 host found on the network (§9.1): the name, platform, and version it advertises. */
    data class Host(val name: String, val platform: String?, val version: Int)

    /** Apple's platforms show only a QR code: CryptoKit has no J-PAKE, so they offer no typed code (FEATURES-27.1.md §6.2). */
    private val QR_ONLY_PLATFORMS = setOf("ios", "ipados", "macos", "tvos", "visionos")

    /** Whether a host on [platform] shows a six-digit code. A platform this build doesn't know is given the benefit of the doubt. */
    fun showsCode(platform: String?): Boolean = platform?.lowercase() !in QR_ONLY_PLATFORMS

    /** The hosts a typed code can go to: the version-2 hosts that show a code, in the order found. */
    fun codeHosts(found: List<Host>): List<Host> = found.filter { it.version >= HandoffV2.VERSION && showsCode(it.platform) }

    /** Whether version-2 hosts were found but none shows a code (only Apple devices), so the screen can say why none is listed. */
    fun onlyQrHosts(found: List<Host>): Boolean = found.any { it.version >= HandoffV2.VERSION } && codeHosts(found).isEmpty()

    /**
     * The host a typed code goes to, by name: the one the viewer picked while
     * it is still found, or else the only one there is. Null shows the list
     * (or the search); a picked host that left isn't swapped for another, as
     * the code was read from that one.
     */
    fun codeHost(hosts: List<String>, picked: String?): String? =
        if (picked != null) picked.takeIf { it in hosts } else hosts.singleOrNull()

    /**
     * What the code field keeps of what was typed: decimal digits only, read
     * as ASCII (a Japanese or Chinese keyboard may type full-width ones), at
     * most six.
     */
    fun typedCode(text: String): String =
        text.mapNotNull { it.digitToIntOrNull()?.let { digit -> '0' + digit } }.take(HandoffCrypto.CODE_LENGTH).joinToString("")

    /** Whether [code] is a whole six-digit code, ready to send. */
    fun isWholeCode(code: String): Boolean = code.length == HandoffCrypto.CODE_LENGTH && code.all { it in '0'..'9' }

    /**
     * The joining side's wrong codes in a row against one host, mirroring the
     * host's own count (§9.4): each code takes one attempt, and after five
     * wrong ones the host shows no code for a minute, so the field waits too.
     */
    data class CodeAttempts(val wrong: Int = 0, val lockedUntil: Long = 0L) {
        fun isLocked(now: Long): Boolean = now < lockedUntil

        /** After a wrong code at [now]: one more, or the minute's wait, after which the count starts again. */
        fun failed(now: Long): CodeAttempts =
            if (wrong + 1 >= HandoffV2.MAX_CODE_FAILURES) CodeAttempts(0, now + HandoffV2.CODE_LOCKOUT_MILLIS) else CodeAttempts(wrong + 1, lockedUntil)
    }

    /** A scanned code's text when it is a version-2 handoff code; anything else is ignored and the camera keeps looking. */
    fun scanned(text: String?): HandoffV2.QrPayload? = text?.takeIf { it.length <= MAX_CODE_TEXT }?.let(HandoffV2.QrPayload::parse)

    /**
     * The host to connect to when the code's address doesn't answer, among
     * the version-2 hosts found on the network: the one with the code's
     * name, or the only one there is. A wrong host can't pass the proof.
     */
    fun target(found: List<String>, wanted: String): String? =
        found.firstOrNull { it == wanted } ?: found.singleOrNull()

    /** Whether the sender's Continue has something to send. */
    fun canContinue(hasLocation: Boolean, includeLocation: Boolean, tickedAccounts: Int, tickedLogins: Int): Boolean =
        (hasLocation && includeLocation) || tickedAccounts > 0 || tickedLogins > 0

    /** A result item (§9.6) as the screen names it. */
    sealed interface Item {
        data object Location : Item

        /** `account:<kind>:<subject>`; the subject isn't shown. */
        data class Account(val kind: MediaSourceKind?, val rawKind: String) : Item

        /** `login:<kind>:<host>`; the host may itself hold colons (IPv6). */
        data class Login(val kind: MediaSourceKind?, val rawKind: String, val host: String) : Item

        data class Other(val raw: String) : Item
    }

    fun item(raw: String): Item {
        if (raw == HandoffV2.LOCATION_ITEM) return Item.Location
        val parts = raw.split(':', limit = 3)
        return when {
            parts[0] == "account" && parts.size >= 2 -> Item.Account(MediaSourceKind.fromRaw(parts[1]), parts[1])
            parts[0] == "login" && parts.size == 3 -> Item.Login(MediaSourceKind.fromRaw(parts[1]), parts[1], parts[2])
            else -> Item.Other(raw)
        }
    }

    /** A handoff link is about 160 characters; a much longer code is something else. */
    const val MAX_CODE_TEXT = 1_024
}

/**
 * Reads a QR code from a camera frame's luminance plane (the Y plane of
 * `YUV_420_888`) with ZXing, which the app already ships to draw codes.
 * A code on a light background or a dark one; null when there is none.
 * Not thread-safe: one per analyzer thread.
 */
class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** [luminance] holds [height] rows of [rowStride] bytes, the first [width] of each the picture. */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        require(rowStride >= width && luminance.size >= rowStride * (height - 1) + width) { "short frame" }
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return decode(source) ?: decode(source.invert())
    }

    private fun decode(source: LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (missing: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
