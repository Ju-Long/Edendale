package com.babasama.edendale.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A horizontal shelf's scroll geometry, mirrored into its heading's rule
 * (J.6, Apple's `ShelfScrollMetrics`). [offset] and [range] are pixels.
 */
data class ShelfScrollMetrics(
    val offset: Float = 0f,
    /** Scrollable distance: content width minus container width. */
    val range: Float = 0f,
    /** Container width over content width; sizes the thumb. */
    val visibleFraction: Float = 1f,
) {
    /** Scroll position, 0…1. */
    val progress: Float get() = if (range > 0f) (offset / range).coerceIn(0f, 1f) else 0f

    /** The rule only scrubs when the content actually overflows. */
    val isScrollable: Boolean get() = visibleFraction < 0.999f

    /**
     * The item, and the offset into it, that puts a row of same-width items
     * at [fraction] of its range.
     */
    fun scrollTarget(fraction: Float, itemExtent: Float, spacing: Float): Pair<Int, Int> {
        val stride = itemExtent + spacing
        if (stride <= 0f) return 0 to 0
        val x = fraction.coerceIn(0f, 1f) * range.coerceAtLeast(0f)
        val index = floor(x / stride).toInt()
        return index to (x - index * stride).roundToInt()
    }

    companion object {
        fun of(contentOffset: Float, contentWidth: Float, containerWidth: Float) = ShelfScrollMetrics(
            offset = contentOffset,
            range = contentWidth - containerWidth,
            visibleFraction = if (contentWidth > 0f) containerWidth / contentWidth else 1f,
        )

        /**
         * A lazy row of same-width items. It only lays out what's on screen,
         * so the content width and offset follow from the item count and the
         * first visible item's index and offset.
         */
        fun ofUniformRow(
            itemCount: Int,
            itemExtent: Float,
            spacing: Float,
            beforePadding: Float,
            afterPadding: Float,
            viewport: Float,
            firstIndex: Int,
            firstOffset: Float,
        ): ShelfScrollMetrics {
            if (itemCount == 0 || itemExtent <= 0f) return ShelfScrollMetrics()
            val content = beforePadding + itemCount * itemExtent + (itemCount - 1) * spacing + afterPadding
            return of(firstIndex * (itemExtent + spacing) + firstOffset, content, viewport)
        }
    }
}

/** A shelf's metrics and the way to move it, for a heading's rule. */
class ShelfScrubber(
    val metrics: ShelfScrollMetrics,
    /** False on TV: the rule is a read-only indicator, the remote scrolls the shelf. */
    val interactive: Boolean,
    /** Receives a new 0…1 position while the thumb is dragged or the rule tapped. */
    val onScrub: (Float) -> Unit,
)

/** Mirrors a lazy row of same-width items (an episode shelf) into a scrubber. */
@Composable
fun rememberShelfScrubber(state: LazyListState, interactive: Boolean): ShelfScrubber {
    val scope = rememberCoroutineScope()
    val metrics by remember(state) {
        derivedStateOf {
            val layout = state.layoutInfo
            ShelfScrollMetrics.ofUniformRow(
                itemCount = layout.totalItemsCount,
                itemExtent = layout.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: 0f,
                spacing = layout.mainAxisItemSpacing.toFloat(),
                beforePadding = layout.beforeContentPadding.toFloat(),
                afterPadding = layout.afterContentPadding.toFloat(),
                viewport = layout.viewportSize.width.toFloat(),
                firstIndex = state.firstVisibleItemIndex,
                firstOffset = state.firstVisibleItemScrollOffset.toFloat(),
            )
        }
    }
    return ShelfScrubber(metrics, interactive) { fraction ->
        val layout = state.layoutInfo
        val extent = layout.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: return@ShelfScrubber
        val (index, offset) = metrics.scrollTarget(fraction, extent, layout.mainAxisItemSpacing.toFloat())
        scope.launch { state.scrollToItem(index, offset) }
    }
}

/**
 * The hairline rule after a heading. When its shelf overflows, it becomes a
 * scroll indicator with a gold thumb sized to the visible part; drag the
 * thumb or tap the rule to move the shelf.
 */
@Composable
internal fun ShelfRule(scrubber: ShelfScrubber, label: String, modifier: Modifier = Modifier) {
    val metrics = scrubber.metrics
    val outline = EdendaleColors.Outline
    if (!metrics.isScrollable) {
        Canvas(modifier.height(1.dp)) { drawRect(outline) }
        return
    }
    val gold = EdendaleColors.Gold
    val onScrub by rememberUpdatedState(scrubber.onScrub)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val description = stringResource(R.string.shelf_scroll_position, label)
    val percent = remember(metrics.progress) { NumberFormat.getPercentInstance().format(metrics.progress.toDouble()) }
    val thumbMin = with(LocalDensity.current) { 28.dp.toPx() }
    val thumbHeight = with(LocalDensity.current) { 5.dp.toPx() }
    // A generous hit area around the 5 dp thumb.
    BoxWithConstraints(modifier.height(16.dp)) {
        val track = constraints.maxWidth.toFloat()
        val thumb = max(track * metrics.visibleFraction, thumbMin).coerceAtMost(track)
        val travel = max(track - thumb, 0f)
        fun fractionAt(x: Float): Float {
            if (travel <= 0f) return 0f
            val along = ((x - thumb / 2) / travel).coerceIn(0f, 1f)
            return if (rtl) 1f - along else along
        }
        val gestures = if (scrubber.interactive) {
            Modifier
                .pointerInput(travel, thumb, rtl) { detectTapGestures { onScrub(fractionAt(it.x)) } }
                .pointerInput(travel, thumb, rtl) {
                    detectHorizontalDragGestures(onDragStart = { onScrub(fractionAt(it.x)) }) { change, _ ->
                        change.consume()
                        onScrub(fractionAt(change.position.x))
                    }
                }
        } else {
            Modifier
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .then(gestures)
                // A drag target has nothing to grab with an assistive pointer, so
                // the rule doubles as an adjustable control stepping a screenful.
                .semantics {
                    contentDescription = description
                    stateDescription = percent
                    val step = max(metrics.visibleFraction, 0.1f)
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = metrics.progress,
                        range = 0f..1f,
                        steps = ((1f / step).roundToInt() - 1).coerceAtLeast(0),
                    )
                    if (scrubber.interactive) {
                        setProgress { target ->
                            onScrub(target.coerceIn(0f, 1f))
                            true
                        }
                    }
                },
        ) {
            val centerY = size.height / 2
            drawRect(outline, topLeft = Offset(0f, centerY - 0.5f.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            val along = travel * metrics.progress
            val x = if (rtl) track - thumb - along else along
            drawRoundRect(
                color = gold,
                topLeft = Offset(x, centerY - thumbHeight / 2),
                size = Size(thumb, thumbHeight),
                cornerRadius = CornerRadius(thumbHeight / 2),
            )
        }
    }
}
