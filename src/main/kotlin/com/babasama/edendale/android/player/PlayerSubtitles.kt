package com.babasama.edendale.android.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.babasama.edendale.android.R
import kotlin.math.roundToInt

/**
 * Subtitles drawn by Edendale instead of `PlayerView` (B.4.3). PlayerView's own
 * subtitle view sits in its content frame, which in Fill mode reaches past the
 * screen, so cues there can be cropped away. Text cues are laid out in the
 * *visible* video rectangle instead — the whole picture for Fit, the on-screen
 * crop for Fill — sized from its height, styled by Settings → Subtitles, and
 * kept above the transport controls while they show. Bitmap cues (PGS, VobSub)
 * keep their authored pixels in the full video frame, where PlayerView placed
 * them. The overlay sits beneath the gesture layer and never takes input.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerSubtitleOverlay(
    player: Player,
    chrome: PlayerChromeState,
    controlsVisible: Boolean,
    /** The track's size while the effects pipeline is installed, where ExoPlayer reports none. */
    effectsVideoSize: VideoSize?,
    modifier: Modifier = Modifier,
) {
    var cues by remember { mutableStateOf(player.currentCues.cues) }
    var reportedVideoSize by remember { mutableStateOf(player.videoSize) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                reportedVideoSize = size
            }
        }
        player.addListener(listener)
        cues = player.currentCues.cues
        reportedVideoSize = player.videoSize
        onDispose { player.removeListener(listener) }
    }

    val videoSize = effectsVideoSize ?: reportedVideoSize
    val context = LocalContext.current
    val density = LocalDensity.current
    val appearance = chrome.subtitleAppearance
    val typeface = rememberSubtitleTypeface(appearance.font)
    val style = remember(appearance, typeface) { appearance.captionStyle(typeface) }
    val fontScale = remember(appearance) { captionFontScale(context) }
    val (textCues, bitmapCues) = remember(cues) { cues.partition { it.bitmap == null } }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val video = SubtitleLayout.videoRect(
            containerWidth = width,
            containerHeight = height,
            videoWidth = videoSize.width,
            videoHeight = videoSize.height,
            pixelRatio = videoSize.pixelWidthHeightRatio,
            aspectFill = chrome.aspectFill,
        )
        val visible = SubtitleLayout.visibleRect(video, width, height)
        val textSizeDp = SubtitleLayout.textSizeDp(visible.height / density.density, fontScale)
        val bottomPadding = SubtitleLayout.bottomPaddingFraction(
            visible = visible,
            obscuredTop = chrome.controlsBottomEdgePx.takeIf { controlsVisible },
            gap = with(density) { 12.dp.toPx() },
        )

        AndroidView(
            factory = { SubtitleView(it) },
            update = { view ->
                // Media3 draws bitmap cues relative to the view, so the view is
                // the whole video frame; no text cue ever reaches it.
                view.setCues(bitmapCues)
            },
            modifier = Modifier.placedIn(video),
        )
        AndroidView(
            factory = { viewContext ->
                SubtitleView(viewContext).apply {
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                }
            },
            update = { view ->
                view.setStyle(style)
                view.setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, textSizeDp)
                view.setBottomPaddingFraction(bottomPadding)
                view.setCues(textCues)
            },
            modifier = Modifier.placedIn(visible),
        )
    }
}

/**
 * Places the child at [box] (in this layout's pixels) at exactly its size,
 * while the modifier itself still fills the parent — so a Fill-mode frame can
 * hang past the screen's edges, as the picture does.
 */
private fun Modifier.placedIn(box: SubtitleLayout.Box): Modifier = layout { measurable, constraints ->
    val width = box.width.roundToInt().coerceAtLeast(0)
    val height = box.height.roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(box.left.roundToInt(), box.top.roundToInt())
    }
}

@Composable
private fun rememberSubtitleTypeface(font: SubtitleFontStyle): Typeface {
    val context = LocalContext.current
    return remember(font) {
        when (font) {
            SubtitleFontStyle.SYSTEM -> Typeface.DEFAULT
            SubtitleFontStyle.SERIF -> Typeface.SERIF
            SubtitleFontStyle.MONOSPACED -> Typeface.MONOSPACE
            SubtitleFontStyle.ROUNDED ->
                runCatching { ResourcesCompat.getFont(context, R.font.nunito) }.getOrNull() ?: Typeface.DEFAULT
        }
    }
}

/**
 * The caption style: the chosen text color with an outline in the matching
 * contrast color, the box color times its opacity, and no window behind it.
 */
@OptIn(UnstableApi::class)
internal fun SubtitleAppearance.captionStyle(typeface: Typeface): CaptionStyleCompat = CaptionStyleCompat(
    textColor.argb,
    backgroundArgb,
    Color.TRANSPARENT,
    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    textColor.outlineArgb,
    typeface,
)

/** The system caption font scale (Settings → Accessibility → Caption preferences). */
internal fun captionFontScale(context: Context): Float =
    context.getSystemService(CaptioningManager::class.java)?.fontScale ?: 1f
