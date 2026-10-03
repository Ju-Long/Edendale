package com.babasama.edendale.android

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.babasama.edendale.android.player.AudioEnhancementProfile
import com.babasama.edendale.android.player.AudioEnhancementRules
import com.babasama.edendale.android.player.AudioEnhancementSettings
import com.babasama.edendale.android.player.PlayerPreferences
import kotlin.math.roundToInt

/**
 * Settings → Audio Enhancement (E.1.3): the profile, then — behind the
 * equalizer button — the preamp and the ten bands as adjustments on top of
 * it, Reset Adjustments, and the Audio Booster. Sliders on handhelds, −/+ on
 * TV. Every change is saved at once and an open player applies it from the
 * next buffer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudioEnhancementSettingsSection(isTelevision: Boolean) {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences.from(context) }
    var settings by remember { mutableStateOf(preferences.audioEnhancement) }
    var expanded by remember { mutableStateOf(false) }
    val update: (AudioEnhancementSettings) -> Unit = {
        settings = it
        preferences.audioEnhancement = it
    }

    SettingsSection(
        header = stringResource(R.string.settings_section_audio_enhancement),
        isTelevision = isTelevision,
        focusableContent = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.audio_profile),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val equalizerLabel = stringResource(if (expanded) R.string.audio_hide_equalizer else R.string.audio_show_equalizer)
                ArchiveIconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics { contentDescription = equalizerLabel },
                    isTelevision = isTelevision,
                ) { focused ->
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = when {
                            focused -> EdendaleColors.OnGold
                            expanded -> EdendaleColors.Gold
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            FlowRow(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AudioEnhancementProfile.entries.forEach { profile ->
                    ArchiveFilterChip(
                        selected = settings.profile == profile,
                        onClick = { update(settings.selecting(profile)) },
                        label = { Text(profileLabel(profile)) },
                        isTelevision = isTelevision,
                    )
                }
            }
        }

        if (expanded) {
            SettingsRowDivider()
            DecibelControl(
                label = stringResource(R.string.audio_preamp),
                effective = settings.effectivePreamp,
                adjustment = settings.userPreamp,
                isTelevision = isTelevision,
                onChange = { update(settings.withUserPreamp(it)) },
            )
            AudioEnhancementRules.BAND_LABELS.forEachIndexed { index, label ->
                DecibelControl(
                    label = "$label Hz",
                    effective = settings.effectiveBands[index],
                    adjustment = settings.userBands[index],
                    isTelevision = isTelevision,
                    onChange = { update(settings.withUserBand(index, it)) },
                )
            }
            if (settings.hasUserAdjustments) {
                SettingsActionRow {
                    ArchiveButton(
                        label = stringResource(R.string.audio_reset_adjustments),
                        isTelevision = isTelevision,
                        onClick = { update(settings.resettingAdjustments()) },
                    )
                }
            }
        }

        SettingsRowDivider()
        BoosterRow(settings.boosterEnabled, isTelevision) { update(settings.copy(boosterEnabled = it)) }
        SettingsRowDivider()
        FocusableRows(isTelevision) {
            InfoRow(stringResource(R.string.audio_passthrough_note))
        }
    }
}

@Composable
private fun BoosterRow(enabled: Boolean, isTelevision: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsToggleRow(
        title = stringResource(R.string.audio_booster),
        detail = stringResource(R.string.audio_booster_detail),
        checked = enabled,
        onToggle = onToggle,
    )
}

/**
 * A whole-row switch: one focus target that fills gold under the remote, with
 * the switch showing state (the Audience row's pattern).
 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = { onToggle(!checked) },
        modifier = Modifier.fillMaxWidth(),
        color = if (focused) EdendaleColors.Gold else Color.Transparent,
        contentColor = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = detail,
                    style = BodyCopyStyle(),
                    color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/**
 * A preamp or band row: the effective value in dB beside its name, set by a
 * slider (handhelds) or −/+ (TV) that moves the user's adjustment by 1 dB.
 */
@Composable
private fun DecibelControl(
    label: String,
    effective: Float,
    adjustment: Float,
    isTelevision: Boolean,
    onChange: (Float) -> Unit,
) {
    val value = decibelLabel(effective)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
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
                    onClick = { onChange(adjustment - 1) },
                    modifier = Modifier.size(40.dp).semantics { contentDescription = lower },
                    enabled = adjustment > AudioEnhancementRules.MIN_DB,
                    isTelevision = true,
                ) { focused ->
                    Text("−", style = MaterialTheme.typography.titleLarge, color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = value,
                    modifier = Modifier
                        .widthIn(min = 88.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge,
                    color = EdendaleColors.Gold,
                    textAlign = TextAlign.Center,
                )
                ArchiveIconButton(
                    onClick = { onChange(adjustment + 1) },
                    modifier = Modifier.size(40.dp).semantics { contentDescription = higher },
                    enabled = adjustment < AudioEnhancementRules.MAX_DB,
                    isTelevision = true,
                ) { focused ->
                    Text("+", style = MaterialTheme.typography.titleLarge, color = if (focused) EdendaleColors.OnGold else MaterialTheme.colorScheme.onSurface)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = value, style = MaterialTheme.typography.bodyMedium, color = EdendaleColors.Gold)
            }
            Slider(
                value = adjustment,
                onValueChange = { onChange(it.roundToInt().toFloat()) },
                valueRange = AudioEnhancementRules.MIN_DB..AudioEnhancementRules.MAX_DB,
                // Whole decibels.
                steps = (AudioEnhancementRules.MAX_DB - AudioEnhancementRules.MIN_DB).toInt() - 1,
                modifier = Modifier.semantics {
                    contentDescription = label
                    stateDescription = value
                },
                colors = SliderDefaults.colors(thumbColor = EdendaleColors.Gold, activeTrackColor = EdendaleColors.Gold),
            )
        }
    }
}

/** "+3 dB", "−2 dB", "0 dB" (Apple's decibelLabel). */
internal fun decibelLabel(value: Float): String {
    val rounded = value.roundToInt()
    return when {
        rounded > 0 -> "+$rounded dB"
        rounded < 0 -> "$rounded dB"
        else -> "0 dB"
    }
}

@Composable
internal fun profileLabel(profile: AudioEnhancementProfile): String = stringResource(
    when (profile) {
        AudioEnhancementProfile.FLAT -> R.string.audio_profile_flat
        AudioEnhancementProfile.MOVIES -> R.string.audio_profile_movies
        AudioEnhancementProfile.MUSIC -> R.string.audio_profile_music
        AudioEnhancementProfile.DIALOGUE -> R.string.audio_profile_dialogue
        AudioEnhancementProfile.NIGHT_MODE -> R.string.audio_profile_night_mode
    },
)
