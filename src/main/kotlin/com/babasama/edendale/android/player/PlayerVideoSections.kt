package com.babasama.edendale.android.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.ArchiveIconButton
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.R
import com.babasama.edendale.android.player.video.EnhancementPreset
import com.babasama.edendale.android.player.video.EnhancementSettings
import com.babasama.edendale.android.player.video.UpscaleRules
import com.babasama.edendale.android.player.video.VideoAdjustment
import com.babasama.edendale.android.player.video.VideoEffectsController
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Picture (F.2.3), after Aspect Ratio: brightness, contrast, gamma, saturation,
 * and hue as sliders (−/+ on TV), Show Original, and Reset. Saved on the device.
 */
@Composable
internal fun PictureSection(video: VideoEffectsController, chrome: PlayerChromeState, isTelevision: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelLabel(stringResource(R.string.video_picture))
        if (video.isHdr) {
            SecondaryPanelCopy(stringResource(R.string.video_not_available_hdr))
            return@Column
        }
        VideoAdjustment.entries.forEach { adjustment ->
            val value = video.adjustments[adjustment]
            StepControl(
                label = adjustmentLabel(adjustment),
                valueText = if (adjustment == VideoAdjustment.HUE) "${value.roundToInt()}°" else String.format(Locale.ROOT, "%.2f", value),
                value = value,
                range = adjustment.min..adjustment.max,
                step = adjustment.step,
                isTelevision = isTelevision,
                enabled = true,
            ) {
                video.setAdjustment(adjustment, it)
                chrome.noteInteraction()
            }
        }
        SwitchRow(stringResource(R.string.video_show_original), video.pictureShowOriginal) {
            video.showPictureOriginal(it)
            chrome.noteInteraction()
        }
        if (!video.adjustments.isNeutral) {
            PanelRow(
                title = stringResource(R.string.player_reset),
                onClick = {
                    video.resetAdjustments()
                    chrome.noteInteraction()
                },
            )
        }
    }
}

/**
 * Enhancement (F.7), the panel's last section: the preset menu, Sharpness,
 * Denoise (High Quality), Show Original, and the resolution label. Kept in
 * memory for the app process, never saved (D11).
 */
@Composable
internal fun EnhancementSection(video: VideoEffectsController, chrome: PlayerChromeState, isTelevision: Boolean) {
    val settings = video.enhancement
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelLabel(stringResource(R.string.video_enhancement))
        if (video.isHdr) {
            SecondaryPanelCopy(stringResource(R.string.video_not_available_hdr))
            return@Column
        }
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            PanelRow(
                title = stringResource(R.string.video_preset),
                detail = presetLabel(settings.preset),
                onClick = {
                    menuOpen = true
                    chrome.noteInteraction()
                },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                EnhancementPreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(presetLabel(preset)) },
                        trailingIcon = if (preset == settings.preset) {
                            { SelectedCheck() }
                        } else {
                            null
                        },
                        onClick = {
                            menuOpen = false
                            video.setPreset(preset)
                            chrome.noteInteraction()
                        },
                    )
                }
            }
        }
        StepControl(
            label = stringResource(R.string.video_sharpness),
            valueText = String.format(Locale.ROOT, "%.2f", settings.sharpness),
            value = settings.sharpness,
            range = 0f..1f,
            step = EnhancementSettings.STEP,
            isTelevision = isTelevision,
            enabled = settings.preset != EnhancementPreset.OFF,
        ) {
            video.setSharpness(it)
            chrome.noteInteraction()
        }
        if (settings.preset == EnhancementPreset.HIGH_QUALITY) {
            StepControl(
                label = stringResource(R.string.video_denoise),
                valueText = String.format(Locale.ROOT, "%.2f", settings.denoise),
                value = settings.denoise,
                range = 0f..1f,
                step = EnhancementSettings.STEP,
                isTelevision = isTelevision,
                enabled = true,
            ) {
                video.setDenoise(it)
                chrome.noteInteraction()
            }
        }
        SwitchRow(stringResource(R.string.video_show_original), settings.showOriginal) {
            video.showEnhancementOriginal(it)
            chrome.noteInteraction()
        }
        video.sourceSize?.let { source ->
            SecondaryPanelCopy(UpscaleRules.label(source, video.targetSize ?: source))
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    PanelRow(
        title = title,
        onClick = { onToggle(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/** A labeled value set by a slider on handhelds and by −/+ on TV. */
@Composable
private fun StepControl(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    isTelevision: Boolean,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    if (isTelevision) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val lower = stringResource(R.string.audio_step_lower, label)
            val higher = stringResource(R.string.audio_step_higher, label)
            ArchiveIconButton(
                onClick = { onChange(value - step) },
                modifier = Modifier.size(40.dp).semantics { contentDescription = lower },
                enabled = enabled && value > range.start,
                isTelevision = true,
            ) { focused ->
                Text("−", style = MaterialTheme.typography.titleLarge, color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = valueText,
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) EdendaleColors.Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            ArchiveIconButton(
                onClick = { onChange(value + step) },
                modifier = Modifier.size(40.dp).semantics { contentDescription = higher },
                enabled = enabled && value < range.endInclusive,
                isTelevision = true,
            ) { focused ->
                Text("+", style = MaterialTheme.typography.titleLarge, color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface)
            }
        }
    } else {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = valueText, style = MaterialTheme.typography.bodyMedium, color = EdendaleColors.Gold)
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0),
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = label
                    stateDescription = valueText
                },
                colors = SliderDefaults.colors(thumbColor = EdendaleColors.Gold, activeTrackColor = EdendaleColors.Gold),
            )
        }
    }
}

@Composable
private fun adjustmentLabel(adjustment: VideoAdjustment): String = stringResource(
    when (adjustment) {
        VideoAdjustment.BRIGHTNESS -> R.string.video_brightness
        VideoAdjustment.CONTRAST -> R.string.video_contrast
        VideoAdjustment.GAMMA -> R.string.video_gamma
        VideoAdjustment.SATURATION -> R.string.video_saturation
        VideoAdjustment.HUE -> R.string.video_hue
    },
)

@Composable
private fun presetLabel(preset: EnhancementPreset): String = stringResource(
    when (preset) {
        EnhancementPreset.OFF -> R.string.video_preset_off
        EnhancementPreset.SHARPEN_ONLY -> R.string.video_preset_sharpen_only
        EnhancementPreset.BALANCED -> R.string.video_preset_balanced
        EnhancementPreset.HIGH_QUALITY -> R.string.video_preset_high_quality
    },
)
