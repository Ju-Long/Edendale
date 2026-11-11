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
 * headset, or a device without a camera shows one.
 */
object PairingFlow {
    /** How this device pairs: it shows a QR code for the other to scan, or scans the other's. */
    enum class Mode { SCAN, SHOW }

    /** The ways this device may pair, the first one preselected where there is no choice. */
    fun modes(isTelevision: Boolean, isHeadset: Boolean, hasCamera: Boolean): List<Mode> =
        if (hasCamera && !isTelevision && !isHeadset) listOf(Mode.SCAN, Mode.SHOW) else listOf(Mode.SHOW)

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
