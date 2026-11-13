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
 * camera, or a Quest 3 with its passthrough camera, scans the other
 * device's QR code or shows its own; a TV, another headset, or a device
 * without a camera shows one. Unlike Apple's, any
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
     * choice. A phone or tablet with a camera scans or shows; so does a
     * headset whose passthrough camera an app can read ([headsetCamera]: a
     * Quest 3 or 3S, [headsetScans]). A TV, any other headset, or a device
     * without a camera shows. Entering a code isn't among them: it is offered
     * from these screens ([offersEntry]), so the scanner stays the first
     * choice where there is a camera.
     */
    fun modes(isTelevision: Boolean, isHeadset: Boolean, hasCamera: Boolean, headsetCamera: Boolean): List<Mode> {
        val scans = when {
            isTelevision -> false
            isHeadset -> headsetCamera
            else -> hasCamera
        }
        return if (scans) listOf(Mode.SCAN, Mode.SHOW) else listOf(Mode.SHOW)
    }

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

    /** A joining device's steps once it has a code to use: connecting, then pairing on the open socket. */
    enum class GuestStep { CONNECTING, PAIRING }

    /** What a joining device shows. */
    enum class GuestView { SCANNER, CODE_ENTRY, CONNECTING, PAIRING }

    /**
     * What the joining side shows. Once it holds a code (scanned, or opened
     * as an `edendale://handoff` link) it shows the connection starting,
     * even in the frame before the connection starts, so a pairing link
     * never composes the scanner, which would ask for the camera.
     */
    fun guestView(mode: Mode?, hasCode: Boolean, step: GuestStep?): GuestView = when {
        step == GuestStep.PAIRING -> GuestView.PAIRING
        step == GuestStep.CONNECTING || hasCode -> GuestView.CONNECTING
        mode == Mode.ENTER -> GuestView.CODE_ENTRY
        else -> GuestView.SCANNER
    }

    /**
     * Where Try Again goes: the same screen, except that a link's code on a
     * device that can't scan gives way to the device's own code (never the
     * scanner, nor a frame of the scan-or-show choice).
     */
    fun retryMode(mode: Mode?, modes: List<Mode>): Mode? = if (mode == Mode.SCAN && Mode.SCAN !in modes) modes.first() else mode

    /** A host found on the network (§9.1): the name, platform, and version it advertises, and its port. */
    data class Host(val name: String, val platform: String?, val version: Int, val port: Int = 0)

    /** Apple's platforms show only a QR code: CryptoKit has no J-PAKE, so they offer no typed code (FEATURES-27.1.md §6.2). */
    private val QR_ONLY_PLATFORMS = setOf("ios", "ipados", "macos", "tvos", "visionos")

    /** Whether a host on [platform] shows a six-digit code. A platform this build doesn't know is given the benefit of the doubt. */
    fun showsCode(platform: String?): Boolean = platform?.lowercase() !in QR_ONLY_PLATFORMS

    /** Whether a typed code can go to [host]: a version-2 host that shows a code. */
    fun takesCode(host: Host): Boolean = host.version >= HandoffV2.VERSION && showsCode(host.platform)

    /**
     * The hosts a typed code can go to, in the order found. This device's own
     * code, still advertised for a moment after it stopped showing it to
     * enter another's ([ownName] on [ownPort]), isn't one of them.
     */
    fun codeHosts(found: List<Host>, ownName: String? = null, ownPort: Int? = null): List<Host> =
        found.filter { takesCode(it) && !(ownPort != null && it.port == ownPort && it.name == ownName) }

    /** Whether version-2 hosts were found but none shows a code (only Apple devices), so the screen can say why none is listed. */
    fun onlyQrHosts(found: List<Host>): Boolean = found.any { it.version >= HandoffV2.VERSION } && found.none(::takesCode)

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

    /** The camera permission every scanner asks for. */
    const val CAMERA_PERMISSION = "android.permission.CAMERA"

    /**
     * Meta's Passthrough Camera API (Quest 3 and 3S, Horizon OS v74 and
     * later): the runtime permission that reads the headset's front colour
     * cameras and nothing else (`CAMERA` would also open the avatar camera).
     * A Quest asks for it alone where the app declares it: Meta's store
     * refuses it beside `CAMERA` (2026-11-13).
     */
    const val HEADSET_CAMERA_PERMISSION = "horizonos.permission.HEADSET_CAMERA"

    /** Meta's vendor tags on a Quest's cameras: the source (0 is a passthrough camera) and the position (0 left, 1 right). */
    const val META_CAMERA_SOURCE = "com.meta.extra_metadata.camera_source"
    const val META_CAMERA_POSITION = "com.meta.extra_metadata.position"
    const val META_SOURCE_PASSTHROUGH = 0

    /** A camera as the scanner sees it: its ID, and Meta's tags where it has them. */
    data class CameraTags(val id: String, val source: Int?, val position: Int?)

    /**
     * The camera a Quest reads codes with: a passthrough camera, the left one
     * where both are listed. Null where there is none: a headset's other
     * cameras (the avatar camera) can't see the other device's screen.
     */
    fun passthroughCamera(cameras: List<CameraTags>): String? =
        cameras.filter { it.source == META_SOURCE_PASSTHROUGH }.minByOrNull { it.position ?: Int.MAX_VALUE }?.id

    /** Quest 3 (`eureka`) and Quest 3S: the headsets with the Passthrough Camera API. */
    private val PASSTHROUGH_MODELS = setOf("quest 3", "quest 3s")
    private val PASSTHROUGH_DEVICES = setOf("eureka")

    /**
     * Whether a Meta headset scans codes: one of its cameras carries Meta's
     * passthrough tag, or it is a Quest 3 or 3S, in case its cameras aren't
     * listed before the permission is granted. A headset whose system is too
     * old to open them says the camera isn't available, and the code can be
     * entered instead.
     */
    fun headsetScans(passthroughListed: Boolean, model: String, device: String): Boolean =
        passthroughListed || model.trim().lowercase() in PASSTHROUGH_MODELS || device.trim().lowercase() in PASSTHROUGH_DEVICES

    /**
     * The permissions the scanner asks for: the camera, or on a Meta headset
     * the headset camera alone where the app [declared] it, as Meta's own
     * scanner sample does. Meta's pages disagree on whether both are needed
     * ("either" in the API's overview), its store refuses `HEADSET_CAMERA`
     * beside `CAMERA`, and a permission the app doesn't declare is refused
     * without a prompt, so a build that declares only `CAMERA` asks a Quest
     * for that.
     */
    fun cameraPermissions(metaHeadset: Boolean, declared: Collection<String>): List<String> =
        if (metaHeadset && HEADSET_CAMERA_PERMISSION in declared) listOf(HEADSET_CAMERA_PERMISSION) else listOf(CAMERA_PERMISSION)

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
