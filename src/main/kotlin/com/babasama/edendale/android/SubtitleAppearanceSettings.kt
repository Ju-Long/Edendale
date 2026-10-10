package com.babasama.edendale.android

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.babasama.edendale.android.player.PlayerPreferences
import com.babasama.edendale.android.player.SubtitleAppearance
import com.babasama.edendale.android.player.SubtitleBackgroundColor
import com.babasama.edendale.android.player.SubtitleFontStyle
import com.babasama.edendale.android.player.SubtitleLayout
import com.babasama.edendale.android.player.SubtitleTextColor
import com.babasama.edendale.android.player.captionFontScale
import kotlin.math.roundToInt

/**
 * Settings → Subtitles' appearance rows (B.4.2): a preview frame, then the
 * font, text color, box color, and box opacity as named presets, and Reset
 * once anything differs from the archive look. Chips work the same under a
 * finger and a remote; opacity is a slider on handhelds and −/+ on TV. Each
 * change is saved at once, and an open player restyles its cue from the
 * preferences listener.
 */
@Composable
internal fun ColumnScope.SubtitleAppearanceRows(isTelevision: Boolean) {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences.from(context) }
    var appearance by remember { mutableStateOf(preferences.subtitleAppearance) }
    val update: (SubtitleAppearance) -> Unit = {
        appearance = it
        preferences.subtitleAppearance = it
    }

    SubtitlePreview(appearance, isTelevision)
    SettingsRowDivider()
    PresetRow(
        title = stringResource(R.string.settings_subtitles_font),
        options = SubtitleFontStyle.entries,
        selected = appearance.font,
        label = { fontLabel(it) },
        isTelevision = isTelevision,
        onSelect = { update(appearance.copy(font = it)) },
    )
    SettingsRowDivider()
    PresetRow(
        title = stringResource(R.string.settings_subtitles_text_color),
        options = SubtitleTextColor.entries,
        selected = appearance.textColor,
        label = { textColorLabel(it) },
        isTelevision = isTelevision,
        onSelect = { update(appearance.copy(textColor = it)) },
    )
    SettingsRowDivider()
    PresetRow(
        title = stringResource(R.string.settings_subtitles_background_color),
        options = SubtitleBackgroundColor.entries,
        selected = appearance.backgroundColor,
        label = { backgroundColorLabel(it) },
        isTelevision = isTelevision,
        onSelect = { update(appearance.copy(backgroundColor = it)) },
    )
    SettingsRowDivider()
    OpacityRow(appearance, isTelevision, update)
    if (!appearance.isDefault) {
        SettingsRowDivider()
        SettingsActionRow {
            ArchiveButton(
                label = stringResource(R.string.settings_subtitles_reset),
                isTelevision = isTelevision,
                onClick = { update(SubtitleAppearance.DEFAULT) },
            )
        }
    }
    SettingsRowDivider()
    FocusableRows(isTelevision) {
        InfoRow(stringResource(R.string.settings_subtitles_appearance_note))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> PresetRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    isTelevision: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            modifier = Modifier
                .selectableGroup()
                .semantics { contentDescription = title },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                ArchiveFilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    isTelevision = isTelevision,
                )
            }
        }
    }
}

@Composable
private fun OpacityRow(
    appearance: SubtitleAppearance,
    isTelevision: Boolean,
    update: (SubtitleAppearance) -> Unit,
) {
    val title = stringResource(R.string.settings_subtitles_background_opacity)
    val percent = "${(appearance.backgroundOpacity * 100).roundToInt()}%"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isTelevision) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // TV has no slider: −/+ step a tenth, as on Apple TV.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val lower = stringResource(R.string.settings_subtitles_opacity_lower)
                val higher = stringResource(R.string.settings_subtitles_opacity_higher)
                ArchiveIconButton(
                    onClick = { update(appearance.steppingOpacity(-1)) },
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = lower },
                    enabled = appearance.backgroundOpacity > 0f,
                    isTelevision = true,
                ) { focused ->
                    Text(
                        text = "−",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = percent,
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge,
                    color = EdendaleColors.Gold,
                    textAlign = TextAlign.Center,
                )
                ArchiveIconButton(
                    onClick = { update(appearance.steppingOpacity(1)) },
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = higher },
                    enabled = appearance.backgroundOpacity < 1f,
                    isTelevision = true,
                ) { focused ->
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(text = percent, style = MaterialTheme.typography.bodyMedium, color = EdendaleColors.Gold)
            }
            Slider(
                value = appearance.backgroundOpacity,
                onValueChange = {
                    update(appearance.copy(backgroundOpacity = SubtitleAppearance.normalizedOpacity(it)))
                },
                valueRange = 0f..1f,
                // Tenths, like the TV stepper: 0, 0.1, … 1.
                steps = 9,
                modifier = Modifier.semantics {
                    contentDescription = title
                    stateDescription = percent
                },
                colors = SliderDefaults.colors(
                    thumbColor = EdendaleColors.Gold,
                    activeTrackColor = EdendaleColors.Gold,
                ),
            )
        }
    }
}

/**
 * A still dusk "frame" with a sample cue in the chosen look, sized as the
 * player sizes it for a picture this tall, straddling the bright horizon and
 * the dark ground so both the box color and its opacity show.
 */
@Composable
private fun SubtitlePreview(appearance: SubtitleAppearance, isTelevision: Boolean) {
    val context = LocalContext.current
    val sample = stringResource(R.string.settings_subtitles_preview_sample)
    val previewLabel = stringResource(R.string.settings_subtitles_preview)
    val fontFamily = remember(appearance.font) {
        when (appearance.font) {
            SubtitleFontStyle.SYSTEM -> FontFamily.Default
            SubtitleFontStyle.SERIF -> FontFamily.Serif
            SubtitleFontStyle.MONOSPACED -> FontFamily.Monospace
            SubtitleFontStyle.ROUNDED -> FontFamily(
                runCatching { ResourcesCompat.getFont(context, R.font.nunito) }.getOrNull() ?: Typeface.DEFAULT,
            )
        }
    }
    val fontScale = remember(appearance) { captionFontScale(context) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = if (isTelevision) 960.dp else 640.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(EdendaleRadii.Card.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(EdendaleRadii.Card.dp))
                .drawBehind { drawDusk() }
                .semantics(mergeDescendants = true) {
                    contentDescription = previewLabel
                    stateDescription = sample
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            val sizeDp = SubtitleLayout.textSizeDp(maxHeight.value, fontScale)
            val fontSize = with(LocalDensity.current) { sizeDp.dp.toSp() }
            val style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = fontFamily,
                fontSize = fontSize,
                lineHeight = fontSize * 1.25f,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = maxOf(16.dp, maxWidth * 0.05f))
                    .padding(bottom = maxHeight * 0.06f)
                    .background(Color(appearance.backgroundArgb), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                // The outline is the stroke drawn under the fill, as the
                // player's caption style does.
                Text(
                    text = sample,
                    style = style.copy(
                        color = Color(appearance.textColor.outlineArgb),
                        drawStyle = Stroke(width = with(LocalDensity.current) { (sizeDp * 0.12f).dp.toPx() }),
                    ),
                )
                Text(text = sample, style = style.copy(color = Color(appearance.textColor.argb)))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDusk() {
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFF2E3D6B), Color(0xFFDB8052), Color(0xFFFAD18C)),
        ),
    )
    val h = size.height
    val w = size.width
    drawCircle(Color(0xFFFFEDBF), radius = h * 0.1f, center = Offset(w * 0.68f, h * 0.62f))
    drawOval(Color(0xFF332B33), topLeft = Offset(w * 0.3f - w * 0.6f, h * 1.02f - h * 0.15f), size = Size(w * 1.2f, h * 0.3f))
    drawOval(Color(0xFF1A171C), topLeft = Offset(w * 0.85f - w * 0.55f, h * 1.04f - h * 0.12f), size = Size(w * 1.1f, h * 0.24f))
}

@Composable
private fun fontLabel(font: SubtitleFontStyle): String = stringResource(
    when (font) {
        SubtitleFontStyle.SYSTEM -> R.string.settings_subtitles_font_system
        SubtitleFontStyle.ROUNDED -> R.string.settings_subtitles_font_rounded
        SubtitleFontStyle.SERIF -> R.string.settings_subtitles_font_serif
        SubtitleFontStyle.MONOSPACED -> R.string.settings_subtitles_font_monospaced
    },
)

@Composable
private fun textColorLabel(color: SubtitleTextColor): String = stringResource(
    when (color) {
        SubtitleTextColor.PARCHMENT -> R.string.settings_subtitles_color_parchment
        SubtitleTextColor.WHITE -> R.string.settings_subtitles_color_white
        SubtitleTextColor.YELLOW -> R.string.settings_subtitles_color_yellow
        SubtitleTextColor.CYAN -> R.string.settings_subtitles_color_cyan
        SubtitleTextColor.GREEN -> R.string.settings_subtitles_color_green
        SubtitleTextColor.BLACK -> R.string.settings_subtitles_color_black
    },
)

@Composable
private fun backgroundColorLabel(color: SubtitleBackgroundColor): String = stringResource(
    when (color) {
        SubtitleBackgroundColor.INK -> R.string.settings_subtitles_color_ink
        SubtitleBackgroundColor.BLACK -> R.string.settings_subtitles_color_black
        SubtitleBackgroundColor.CHARCOAL -> R.string.settings_subtitles_color_charcoal
        SubtitleBackgroundColor.NAVY -> R.string.settings_subtitles_color_navy
        SubtitleBackgroundColor.WHITE -> R.string.settings_subtitles_color_white
    },
)
