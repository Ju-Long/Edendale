package com.babasama.edendale.android.parental

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babasama.edendale.android.AccountProgressRow
import com.babasama.edendale.android.ArchiveButton
import com.babasama.edendale.android.ArchiveButtonKind
import com.babasama.edendale.android.BodyCopyStyle
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleRadii
import com.babasama.edendale.android.InfoRow
import com.babasama.edendale.android.R
import com.babasama.edendale.android.SettingsActionRow
import com.babasama.edendale.android.SettingsRowDivider
import com.babasama.edendale.android.SettingsSection
import com.babasama.edendale.android.SettingsSwitchRow
import com.babasama.edendale.android.tvFocusLift
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.parental.CertificationLists
import com.babasama.edendale.parental.GatedAction
import com.babasama.edendale.parental.ParentalPolicy
import com.babasama.edendale.parental.ParentalRules
import com.babasama.edendale.parental.PinHasher
import com.babasama.edendale.parental.PinResult
import com.babasama.edendale.parental.RatingCeiling
import java.util.Locale
import kotlinx.coroutines.launch

/** Which PIN dialog the section has open. */
private enum class PinFlow { SET, CHANGE, REMOVE }

/**
 * Settings → Parental Controls (O.6; O-D12, where "Audience" was): the Young
 * Audience preset, the two rating ceilings with their picker, the three
 * switches, and the PIN. Every change goes through [gate] as a parental
 * change, so the PIN typed to open Settings carries over (O-D4) and a locked
 * profile is asked (FEATURES-27.1 §4). Nothing here leaves the device.
 */
@Composable
internal fun ParentalControlsSection(gate: ParentalGate, isTelevision: Boolean) {
    val context = LocalContext.current
    val controls = remember(context) { parentalControls(context) }
    val scope = rememberCoroutineScope()
    val ready by controls.ready.collectAsState()
    val policy by controls.policy.collectAsState()
    val hasPin by controls.hasPin.collectAsState()
    val lists by controls.lists.collectAsState()
    val listsStatus by controls.listsStatus.collectAsState()
    var picker by remember { mutableStateOf<MediaType?>(null) }
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    // A lock switch turned on before any PIN exists: set one first, then apply the switch.
    var afterPin by remember { mutableStateOf<((ParentalPolicy) -> ParentalPolicy)?>(null) }
    val deviceRegion = remember { Locale.getDefault().country.uppercase() }

    // The lists are fetched when the section opens (O.3), so the picker has them; a no-op while they are fresh.
    LaunchedEffect(ready) { if (ready) controls.refreshLists() }

    fun change(mutation: (ParentalPolicy) -> ParentalPolicy) {
        gate.require(GatedAction.PARENTAL_CHANGE) {
            scope.launch { controls.updatePolicy(System.currentTimeMillis(), mutation) }
        }
    }

    fun toggleLock(on: Boolean, mutation: (ParentalPolicy) -> ParentalPolicy) {
        if (on && !hasPin) {
            afterPin = mutation
            pinFlow = PinFlow.SET
        } else {
            change(mutation)
        }
    }

    val regionShown = lists?.region ?: deviceRegion.ifEmpty { ParentalRules.FALLBACK_REGION }
    val regionNote = stringResource(R.string.parental_region_note, regionName(regionShown))

    SettingsSection(
        header = stringResource(R.string.settings_section_parental_controls),
        isTelevision = isTelevision,
        focusableContent = false,
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_young_audience_title),
            detail = stringResource(R.string.settings_young_audience_description),
            checked = policy.youngAudienceFriendly,
            onToggle = { on -> change { it.copy(youngAudienceFriendly = on) } },
        )
        SettingsRowDivider()
        RatingLimitRow(
            title = stringResource(R.string.parental_max_rating_movies),
            value = policy.ceiling?.movie ?: stringResource(R.string.parental_no_limit),
            detail = regionNote,
            onClick = { gate.require(GatedAction.PARENTAL_CHANGE) { picker = MediaType.MOVIE } },
        )
        SettingsRowDivider()
        RatingLimitRow(
            title = stringResource(R.string.parental_max_rating_tv),
            value = policy.ceiling?.tv ?: stringResource(R.string.parental_no_limit),
            detail = regionNote,
            onClick = { gate.require(GatedAction.PARENTAL_CHANGE) { picker = MediaType.TV } },
        )
        // Where the lists stand (O-D5): still loading, not loaded, or another region's because TMDB has none for this one.
        val current = lists
        when {
            current == null && listsStatus == ListsStatus.LOADING -> {
                SettingsRowDivider()
                AccountProgressRow(stringResource(R.string.parental_loading_ratings))
            }
            current == null && listsStatus == ListsStatus.FAILED -> {
                SettingsRowDivider()
                InfoRow(stringResource(R.string.parental_ratings_unavailable))
                SettingsActionRow {
                    ArchiveButton(
                        label = stringResource(R.string.action_try_again),
                        isTelevision = isTelevision,
                        onClick = { scope.launch { controls.refreshLists(force = true) } },
                    )
                }
            }
            current != null && deviceRegion.isNotEmpty() && !current.region.equals(deviceRegion, ignoreCase = true) -> {
                SettingsRowDivider()
                InfoRow(stringResource(R.string.parental_region_fallback, regionName(deviceRegion), regionName(current.region)))
            }
        }
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.parental_hide_restricted),
            detail = stringResource(R.string.parental_hide_restricted_detail),
            checked = policy.hideRestrictedTitles,
            onToggle = { on -> change { it.copy(hideRestrictedTitles = on) } },
        )
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.parental_block_trailers),
            detail = stringResource(R.string.parental_block_trailers_detail),
            checked = policy.blockTrailersAndLinks,
            onToggle = { on -> change { it.copy(blockTrailersAndLinks = on) } },
        )
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.parental_lock_settings),
            detail = stringResource(R.string.parental_lock_settings_detail),
            checked = policy.lockSettings,
            onToggle = { on -> toggleLock(on) { it.copy(lockSettings = on) } },
        )
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.parental_lock_library),
            detail = stringResource(R.string.parental_lock_library_detail),
            checked = policy.lockLibraryEdits,
            onToggle = { on -> toggleLock(on) { it.copy(lockLibraryEdits = on) } },
        )
        SettingsRowDivider()
        SettingsActionRow {
            if (!hasPin) {
                ArchiveButton(
                    label = stringResource(R.string.parental_set_pin),
                    kind = ArchiveButtonKind.Secondary,
                    isTelevision = isTelevision,
                    onClick = { pinFlow = PinFlow.SET },
                )
            } else {
                ArchiveButton(
                    label = stringResource(R.string.parental_change_pin),
                    kind = ArchiveButtonKind.Secondary,
                    isTelevision = isTelevision,
                    onClick = { pinFlow = PinFlow.CHANGE },
                )
                ArchiveButton(
                    label = stringResource(R.string.parental_remove_pin),
                    isTelevision = isTelevision,
                    onClick = { pinFlow = PinFlow.REMOVE },
                )
            }
        }
        SettingsRowDivider()
        InfoRow(stringResource(R.string.parental_local_note))
    }

    picker?.let { type ->
        RatingPickerDialog(
            mediaType = type,
            lists = lists,
            status = listsStatus,
            selected = if (type == MediaType.MOVIE) policy.ceiling?.movie else policy.ceiling?.tv,
            regionNote = regionNote,
            isTelevision = isTelevision,
            onPick = { label ->
                picker = null
                val region = lists?.region ?: return@RatingPickerDialog
                change { current -> current.copy(ceiling = current.ceiling.with(type, label, region)) }
            },
            onRetry = { scope.launch { controls.refreshLists(force = true) } },
            onDismiss = { picker = null },
        )
    }

    when (pinFlow) {
        PinFlow.SET, PinFlow.CHANGE -> SetPinDialog(
            controls = controls,
            isTelevision = isTelevision,
            changing = pinFlow == PinFlow.CHANGE,
            message = if (afterPin != null) stringResource(R.string.parental_set_pin_first) else null,
            onDone = {
                pinFlow = null
                afterPin?.let { mutation ->
                    afterPin = null
                    // A new PIN opens the unlock window, so the switch applies without a second question.
                    scope.launch { controls.updatePolicy(System.currentTimeMillis(), mutation) }
                }
            },
            onDismiss = {
                pinFlow = null
                afterPin = null
            },
        )
        PinFlow.REMOVE -> PinDialog(
            controls = controls,
            isTelevision = isTelevision,
            title = stringResource(R.string.parental_remove_pin),
            confirmLabel = stringResource(R.string.action_remove),
            attempt = controls::clearPin,
            onUnlocked = { pinFlow = null },
            onDismiss = { pinFlow = null },
        )
        null -> Unit
    }
}

/**
 * The ceiling with one type's label replaced: labels stay labels, the
 * region is the lists' (O-D5), and no limit on either type is no ceiling.
 */
private fun RatingCeiling?.with(mediaType: MediaType, label: String?, region: String): RatingCeiling? {
    val movie = if (mediaType == MediaType.MOVIE) label else this?.movie
    val tv = if (mediaType == MediaType.TV) label else this?.tv
    return if (movie == null && tv == null) null else RatingCeiling(region, movie, tv)
}

/** The localized country name for an ISO 3166-1 code, or the code when the platform has no name for it. */
private fun regionName(code: String): String =
    runCatching { Locale.Builder().setRegion(code).build().displayCountry }.getOrNull()?.takeIf { it.isNotBlank() } ?: code

/** A ceiling row: the title, the region line, and the chosen label; the whole row opens the picker and takes focus on TV. */
@Composable
private fun RatingLimitRow(title: String, value: String, detail: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
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
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (focused) EdendaleColors.OnGold else EdendaleColors.Gold,
            )
        }
    }
}

/**
 * The region's labels for one media type, "No limit" first, as a radio group
 * for TalkBack; a pick applies at once. Loading and error lines while the
 * lists are not there.
 */
@Composable
private fun RatingPickerDialog(
    mediaType: MediaType,
    lists: CertificationLists?,
    status: ListsStatus,
    selected: String?,
    regionNote: String,
    isTelevision: Boolean,
    onPick: (String?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = lists?.let { if (mediaType == MediaType.MOVIE) it.movie else it.tv }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        containerColor = EdendaleColors.Surface,
        title = {
            Text(
                text = stringResource(if (mediaType == MediaType.MOVIE) R.string.parental_max_rating_movies else R.string.parental_max_rating_tv),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(regionNote, style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    lists == null && status == ListsStatus.FAILED -> {
                        Text(stringResource(R.string.parental_ratings_unavailable), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.error)
                        ArchiveButton(label = stringResource(R.string.action_try_again), isTelevision = isTelevision, onClick = onRetry)
                    }
                    lists == null -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.parental_loading_ratings), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        PickerRow(label = stringResource(R.string.parental_no_limit), selected = selected == null, isTelevision = isTelevision) { onPick(null) }
                        entries.forEach { entry ->
                            val isSelected = selected != null && ParentalRules.normalize(selected) == ParentalRules.normalize(entry.certification)
                            PickerRow(label = entry.certification, selected = isSelected, isTelevision = isTelevision) { onPick(entry.certification) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ArchiveButton(label = stringResource(R.string.action_close), kind = ArchiveButtonKind.Primary, isTelevision = isTelevision, onClick = onDismiss)
        },
    )
}

@Composable
private fun PickerRow(label: String, selected: Boolean, isTelevision: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(EdendaleRadii.Soft.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusLift(isTelevision, shape)
            // Radio semantics, so TalkBack says which label is chosen.
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        shape = shape,
        color = if (selected) EdendaleColors.SurfaceLow else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private enum class PinStep { CURRENT, NEW, CONFIRM }

/**
 * Set or Change PIN in steps: the current PIN when one exists (verified at
 * once, so a wrong one is reported before anything is typed twice), the new
 * PIN, and the new PIN again. 4–8 digits, masked, Go moves on (O-D2); the
 * back-off countdown shows while the current PIN is being asked (O-D3).
 */
@Composable
internal fun SetPinDialog(
    controls: ParentalControls,
    isTelevision: Boolean,
    changing: Boolean,
    message: String? = null,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (changing) PinStep.CURRENT else PinStep.NEW) }
    var entry by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf<String?>(null) }
    var newPin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val lockedUntil by controls.lockedUntilMillis.collectAsState()
    val remaining = if (step == PinStep.CURRENT) rememberCountdown(lockedUntil) else 0L
    val wrong = stringResource(R.string.parental_wrong_pin)
    val tooShort = stringResource(R.string.parental_pin_length)
    val differ = stringResource(R.string.parental_pins_differ)
    val focus = remember { FocusRequester() }
    LaunchedEffect(step) { if (!isTelevision) focus.requestFocus() }

    fun submit() {
        if (busy || remaining > 0L) return
        val typed = entry
        if (!PinHasher.isValidPin(typed.toCharArray())) {
            error = tooShort
            return
        }
        when (step) {
            PinStep.CURRENT -> {
                busy = true
                error = null
                scope.launch {
                    when (controls.verifyPin(typed.toCharArray(), System.currentTimeMillis())) {
                        is PinResult.Unlocked -> {
                            currentPin = typed
                            entry = ""
                            step = PinStep.NEW
                        }
                        PinResult.NoPin -> {
                            currentPin = null
                            entry = ""
                            step = PinStep.NEW
                        }
                        is PinResult.LockedOut -> entry = ""
                        else -> {
                            entry = ""
                            error = wrong
                        }
                    }
                    busy = false
                }
            }
            PinStep.NEW -> {
                newPin = typed
                entry = ""
                error = null
                step = PinStep.CONFIRM
            }
            PinStep.CONFIRM -> {
                if (typed != newPin) {
                    entry = ""
                    newPin = null
                    error = differ
                    step = PinStep.NEW
                    return
                }
                busy = true
                error = null
                scope.launch {
                    val result = controls.setPin(currentPin?.toCharArray(), typed.toCharArray(), System.currentTimeMillis())
                    busy = false
                    entry = ""
                    when (result) {
                        is PinResult.Unlocked -> onDone()
                        PinResult.InvalidPin -> {
                            newPin = null
                            error = tooShort
                            step = PinStep.NEW
                        }
                        // The current PIN is needed, or was refused after all: ask for it again.
                        else -> {
                            newPin = null
                            currentPin = null
                            error = if (result is PinResult.LockedOut) null else wrong
                            step = PinStep.CURRENT
                        }
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        containerColor = EdendaleColors.Surface,
        title = {
            Text(
                text = stringResource(if (changing) R.string.parental_change_pin else R.string.parental_set_pin),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                message?.let { Text(it, style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(
                    value = entry,
                    onValueChange = {
                        entry = it.filter(Char::isDigit).take(PinHasher.MAX_PIN_LENGTH)
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    label = {
                        Text(
                            stringResource(
                                when (step) {
                                    PinStep.CURRENT -> R.string.parental_current_pin
                                    PinStep.NEW -> R.string.parental_new_pin
                                    PinStep.CONFIRM -> R.string.parental_confirm_pin
                                },
                            ),
                        )
                    },
                    singleLine = true,
                    enabled = !busy && remaining == 0L,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
                when {
                    busy -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.parental_checking_pin), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    remaining > 0L -> Text(
                        text = stringResource(R.string.parental_locked_out, android.text.format.DateUtils.formatElapsedTime((remaining + 999L) / 1_000L)),
                        style = BodyCopyStyle(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    error != null -> Text(error!!, style = BodyCopyStyle(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            ArchiveButton(label = stringResource(R.string.action_cancel), isTelevision = isTelevision, onClick = onDismiss)
        },
        confirmButton = {
            ArchiveButton(
                label = stringResource(if (step == PinStep.CONFIRM) R.string.action_save else R.string.parental_next),
                kind = ArchiveButtonKind.Primary,
                isTelevision = isTelevision,
                enabled = entry.length >= PinHasher.MIN_PIN_LENGTH && !busy && remaining == 0L,
                onClick = { submit() },
            )
        },
    )
}
