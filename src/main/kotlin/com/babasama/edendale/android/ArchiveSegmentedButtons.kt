package com.babasama.edendale.android

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * Segmented buttons in the archive look (27.1): one outlined pill split into
 * segments, the chosen one a dark fill with gold ink and a gold edge.
 * Material's own SegmentedButton keeps room for its check mark in every
 * segment, which four labels can't spare at phone width, so these segments
 * hold their label alone.
 *
 * [fillWidth] shares the row's width equally and shrinks a label that
 * doesn't fit; otherwise every segment takes the widest label's width and the
 * row keeps to its start. Focus — the D-pad, a keyboard's Tab — fills a
 * segment gold with dark ink, as on the TV tab strip, so "the remote is here"
 * reads apart from "this one is on".
 */
@Composable
internal fun <T> ArchiveSegmentedButtons(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    /** The segment's visible label. */
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    /** What TalkBack reads for the segment, when a short label stands in for a longer name. */
    description: @Composable (T) -> String = label,
    fillWidth: Boolean = false,
    isTelevision: Boolean = false,
) {
    // Not labelLarge: its wide tracking is for label caps, and here every
    // dp of a phone-width segment goes to the word.
    val textStyle = if (isTelevision) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    }
    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max))
            // One height for every segment, even one whose label shrank.
            .height(IntrinsicSize.Min)
            .selectableGroup(),
        // Neighbouring outlines overlap into one hairline, as Material's do.
        horizontalArrangement = Arrangement.spacedBy(-SegmentedButtonDefaults.BorderWidth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            val interactionSource = remember { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            val shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            val container by animateColorAsState(
                targetValue = when {
                    focused -> EdendaleColors.Gold
                    isSelected -> MaterialTheme.colorScheme.secondaryContainer
                    else -> Color.Transparent
                },
                animationSpec = tween(140),
                label = "Segment container",
            )
            val ink by animateColorAsState(
                targetValue = when {
                    focused -> EdendaleColors.OnGold
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(140),
                label = "Segment ink",
            )
            val spoken = description(option)
            Surface(
                selected = isSelected,
                onClick = { onSelect(option) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The chosen segment's gold edge draws over its neighbours' hairlines.
                    .zIndex(if (isSelected) 1f else 0f)
                    // Left and right walk the segments and stop at the ends,
                    // rather than jumping diagonally into whatever is below.
                    // Start and end, so a right-to-left layout stops at its
                    // own ends and not between its segments.
                    .focusProperties {
                        if (index == 0) start = FocusRequester.Cancel
                        if (index == options.lastIndex) end = FocusRequester.Cancel
                    }
                    .tvFocusLift(isTelevision, shape)
                    .semantics {
                        role = Role.RadioButton
                        contentDescription = spoken
                    },
                shape = shape,
                color = container,
                contentColor = ink,
                border = BorderStroke(
                    SegmentedButtonDefaults.BorderWidth,
                    if (isSelected && !focused) EdendaleColors.Gold else MaterialTheme.colorScheme.outline,
                ),
                interactionSource = interactionSource,
            ) {
                Box(
                    modifier = Modifier.padding(
                        horizontal = when {
                            isTelevision -> 20.dp
                            fillWidth -> 8.dp
                            else -> 16.dp
                        },
                        vertical = if (isTelevision) 12.dp else 8.dp,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    // One line, never broken at a space: a label that doesn't
                    // fit shrinks, and only past the smallest size ellipsizes.
                    BasicText(
                        text = label(option),
                        // The segment says its full name instead.
                        modifier = Modifier.clearAndSetSemantics {},
                        style = textStyle.copy(color = LocalContentColor.current, textAlign = TextAlign.Center),
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        maxLines = 1,
                        autoSize = if (fillWidth) {
                            TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = textStyle.fontSize, stepSize = 0.5.sp)
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
