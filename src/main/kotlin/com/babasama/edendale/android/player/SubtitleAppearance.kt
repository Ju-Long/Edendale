package com.babasama.edendale.android.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The viewer's look for text subtitles, set in Settings → Subtitles (B.4): the
 * typeface, the text color, and the color and opacity of the box behind each
 * cue. Named presets rather than a free picker, so every choice stays legible
 * and the TV can offer the same set. Bitmap subtitles (PGS, VobSub) keep their
 * authored pixels. Pure: no Android imports, so the JVM suite covers it.
 */
enum class SubtitleFontStyle(val raw: String) {
    SYSTEM("system"),

    /** Android ships no rounded family; this is the bundled Nunito (D18). */
    ROUNDED("rounded"),
    SERIF("serif"),
    MONOSPACED("monospaced");

    companion object {
        val DEFAULT = SYSTEM

        fun fromRaw(raw: String?): SubtitleFontStyle = entries.firstOrNull { it.raw == raw } ?: DEFAULT
    }
}

enum class SubtitleTextColor(val raw: String, val argb: Int) {
    /** The archive's parchment (`EdendaleColors.TextPrimary`), the look subtitles always had. */
    PARCHMENT("parchment", 0xFFE4E1E9.toInt()),
    WHITE("white", 0xFFFFFFFF.toInt()),
    YELLOW("yellow", 0xFFFFE033.toInt()),
    CYAN("cyan", 0xFF59E6FF.toInt()),
    GREEN("green", 0xFF73F273.toInt()),
    BLACK("black", 0xFF000000.toInt());

    /**
     * The stroke around each glyph: light around black text and the archive's
     * ink around everything else, so text reads even with no box behind it.
     */
    val outlineArgb: Int get() = if (this == BLACK) WHITE.argb else SubtitleBackgroundColor.INK.argb

    companion object {
        val DEFAULT = PARCHMENT

        fun fromRaw(raw: String?): SubtitleTextColor = entries.firstOrNull { it.raw == raw } ?: DEFAULT
    }
}

enum class SubtitleBackgroundColor(val raw: String, val argb: Int) {
    /** The archive's ink (`EdendaleColors.Background`). */
    INK("ink", 0xFF0A0A0F.toInt()),
    BLACK("black", 0xFF000000.toInt()),
    CHARCOAL("charcoal", 0xFF383838.toInt()),
    NAVY("navy", 0xFF0F1A3D.toInt()),
    WHITE("white", 0xFFFFFFFF.toInt());

    companion object {
        val DEFAULT = INK

        fun fromRaw(raw: String?): SubtitleBackgroundColor = entries.firstOrNull { it.raw == raw } ?: DEFAULT
    }
}

data class SubtitleAppearance(
    val font: SubtitleFontStyle = SubtitleFontStyle.DEFAULT,
    val textColor: SubtitleTextColor = SubtitleTextColor.DEFAULT,
    val backgroundColor: SubtitleBackgroundColor = SubtitleBackgroundColor.DEFAULT,
    /** 0 removes the box entirely; the outline keeps the text legible. */
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
) {
    val isDefault: Boolean get() = this == DEFAULT

    /** The box color with the opacity folded into its alpha channel. */
    val backgroundArgb: Int
        get() {
            val alpha = (normalizedOpacity(backgroundOpacity) * 255f).roundToInt()
            return (alpha shl 24) or (backgroundColor.argb and 0x00FFFFFF)
        }

    /** One TV −/+ press: a tenth, kept on whole percents. */
    fun steppingOpacity(steps: Int): SubtitleAppearance =
        copy(backgroundOpacity = normalizedOpacity(backgroundOpacity + steps * OPACITY_STEP))

    companion object {
        const val DEFAULT_BACKGROUND_OPACITY = 1f
        const val OPACITY_STEP = 0.1f
        val DEFAULT = SubtitleAppearance()

        /** Clamps to 0…1 and snaps to whole percents; a non-finite value reads as the default. */
        fun normalizedOpacity(opacity: Float?): Float =
            PlayerPreferencesRules.normalizeSubtitleBackgroundOpacity(opacity)
    }
}

/**
 * Where and how large text cues are drawn (B.4 placement). All lengths are in
 * one unit chosen by the caller (pixels or dp), except where named.
 */
object SubtitleLayout {
    const val MIN_TEXT_DP = 16f
    const val MAX_TEXT_DP = 48f
    const val TEXT_HEIGHT_FRACTION = 0.055f

    /** Media3's own default distance from the bottom of the viewport. */
    const val DEFAULT_BOTTOM_PADDING_FRACTION = 0.08f
    const val MAX_BOTTOM_PADDING_FRACTION = 0.4f

    /**
     * The cue size: 5.5 % of the visible video height, kept between 16 and
     * 48 dp, then scaled by the system caption font scale.
     */
    fun textSizeDp(visibleHeightDp: Float, fontScale: Float): Float {
        val scale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        return visibleHeightDp.times(TEXT_HEIGHT_FRACTION).coerceIn(MIN_TEXT_DP, MAX_TEXT_DP) * scale
    }

    data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        val isEmpty: Boolean get() = width <= 0f || height <= 0f
    }

    /**
     * The video's frame inside [containerWidth] × [containerHeight]: letterboxed
     * for Fit, cropped past the container's edges for Fill. Unknown sizes fill
     * the container. [pixelRatio] is the video's pixel width-to-height ratio.
     */
    fun videoRect(
        containerWidth: Float,
        containerHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
        pixelRatio: Float = 1f,
        aspectFill: Boolean,
    ): Box {
        val ratio = pixelRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val width = videoWidth * ratio
        if (containerWidth <= 0f || containerHeight <= 0f || width <= 0f || videoHeight <= 0) {
            return Box(0f, 0f, containerWidth, containerHeight)
        }
        val horizontal = containerWidth / width
        val vertical = containerHeight / videoHeight
        val scale = if (aspectFill) max(horizontal, vertical) else min(horizontal, vertical)
        val scaledWidth = width * scale
        val scaledHeight = videoHeight * scale
        return Box(
            left = (containerWidth - scaledWidth) / 2f,
            top = (containerHeight - scaledHeight) / 2f,
            width = scaledWidth,
            height = scaledHeight,
        )
    }

    /** The part of [video] the screen actually shows: the whole frame for Fit, the crop for Fill. */
    fun visibleRect(video: Box, containerWidth: Float, containerHeight: Float): Box {
        val left = max(video.left, 0f)
        val top = max(video.top, 0f)
        val right = min(video.right, containerWidth)
        val bottom = min(video.bottom, containerHeight)
        return Box(left, top, max(right - left, 0f), max(bottom - top, 0f))
    }

    /**
     * How far above the visible rectangle's bottom edge bottom-anchored cues
     * sit, as a fraction of its height. While the transport controls show,
     * cues clear their top edge ([obscuredTop], same units) plus [gap];
     * otherwise Media3's default applies. Capped so a cue never climbs past
     * the lower 40 % of the picture.
     */
    fun bottomPaddingFraction(visible: Box, obscuredTop: Float?, gap: Float): Float {
        if (obscuredTop == null || visible.isEmpty) return DEFAULT_BOTTOM_PADDING_FRACTION
        val needed = (visible.bottom - obscuredTop + gap) / visible.height
        return needed.coerceIn(DEFAULT_BOTTOM_PADDING_FRACTION, MAX_BOTTOM_PADDING_FRACTION)
    }
}
