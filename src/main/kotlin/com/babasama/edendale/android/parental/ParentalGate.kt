package com.babasama.edendale.android.parental

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babasama.edendale.android.ArchiveButton
import com.babasama.edendale.android.ArchiveButtonKind
import com.babasama.edendale.android.BodyCopyStyle
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleRadii
import com.babasama.edendale.android.R
import com.babasama.edendale.parental.GatedAction
import com.babasama.edendale.parental.ParentalGateRules
import com.babasama.edendale.parental.ParentalPolicy
import com.babasama.edendale.parental.PinHasher
import com.babasama.edendale.parental.PinResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One gated action waiting on the PIN: what asked, and what to run once the PIN is right or the viewer gives up. */
internal class GateRequest(val action: GatedAction, val onUnlocked: () -> Unit, val onRefused: () -> Unit)

/**
 * The PIN gate in front of Settings, library edits, Sync, pairing links, and
 * the parental section (O.5). [require] runs the action at once when nothing
 * asks for the PIN (no PIN set, the switch off, or inside the unlock window,
 * see [ParentalGateRules.requiresPin]); otherwise it holds the request while
 * [rememberParentalGate] shows the PIN dialog, and runs or drops it with the
 * answer. Until the controls have read their store a request waits rather
 * than guessing. One question at a time: a request that arrives while
 * another waits is refused, except a newer pairing link, which replaces the
 * one still waiting (the viewer scanned it last; the older one is dropped).
 */
@Stable
class ParentalGate internal constructor(
    private val controls: ParentalControls,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal var pending by mutableStateOf<GateRequest?>(null)
        private set

    /** Runs [onUnlocked] now or after a correct PIN; [onRefused] when the dialog is cancelled or, for a pairing link, the PIN was wrong. */
    fun require(action: GatedAction, onRefused: () -> Unit = {}, onUnlocked: () -> Unit) {
        val request = GateRequest(action, onUnlocked, onRefused)
        if (controls.ready.value) {
            decide(request)
        } else {
            scope.launch {
                controls.awaitReady()
                decide(request)
            }
        }
    }

    /** Whether [action] would ask for the PIN right now, for a caller that only wants to know. */
    fun asks(action: GatedAction): Boolean =
        controls.ready.value &&
            ParentalGateRules.requiresPin(action, controls.policy.value, controls.hasPin.value, controls.isUnlocked(now()))

    private fun decide(request: GateRequest) {
        val asks = ParentalGateRules.requiresPin(
            request.action,
            controls.policy.value,
            controls.hasPin.value,
            controls.isUnlocked(now()),
        )
        val waiting = pending
        when {
            !asks -> request.onUnlocked()
            waiting == null -> pending = request
            waiting.action == GatedAction.PAIRING_LINK && request.action == GatedAction.PAIRING_LINK -> {
                pending = request
                waiting.onRefused()
            }
            else -> request.onRefused()
        }
    }

    /**
     * Closes a waiting [action]'s dialog without running either callback:
     * for a pairing link that was answered, refused, or replaced elsewhere
     * (another window of the app holds the same process-wide link).
     */
    fun withdraw(action: GatedAction) {
        if (pending?.action == action) pending = null
    }

    /** The dialog's answer: the PIN matched, or the viewer gave up. */
    internal fun finish(unlocked: Boolean) {
        val request = pending ?: return
        pending = null
        if (unlocked) request.onUnlocked() else request.onRefused()
    }
}

/**
 * The gate for a screen, with its PIN dialog hosted here. Every gate shares
 * the process's controls, so a PIN typed for one request opens the unlock
 * window for all of them (O-D4).
 */
@Composable
fun rememberParentalGate(isTelevision: Boolean): ParentalGate {
    val context = LocalContext.current
    val controls = remember(context) { parentalControls(context) }
    val scope = rememberCoroutineScope()
    val gate = remember(controls, scope) { ParentalGate(controls, scope) }
    gate.pending?.let { request ->
        // Keyed by the request, so a replacing pairing link starts with an empty field.
        key(request) {
            PinDialog(
                controls = controls,
                isTelevision = isTelevision,
                title = stringResource(R.string.parental_enter_pin),
                message = if (request.action == GatedAction.PAIRING_LINK) stringResource(R.string.parental_pairing_link_message) else null,
                dismissOnWrongPin = ParentalGateRules.dropsOnWrongPin(request.action),
                onUnlocked = { gate.finish(true) },
                onDismiss = { gate.finish(false) },
            )
        }
    }
    return gate
}

/**
 * Asks for the PIN (O-D2): digits only, masked, Go submits, with the error
 * line and the back-off countdown (O-D3). [attempt] is the controls' check,
 * or `clearPin` when the dialog removes the PIN. On TV the field opens the
 * on-screen keyboard on Select, as the pairing code field does.
 */
@Composable
internal fun PinDialog(
    controls: ParentalControls,
    isTelevision: Boolean,
    title: String,
    message: String? = null,
    confirmLabel: String = stringResource(R.string.parental_unlock),
    dismissOnWrongPin: Boolean = false,
    attempt: suspend (CharArray, Long) -> PinResult = controls::verifyPin,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val lockedUntil by controls.lockedUntilMillis.collectAsState()
    val remaining = rememberCountdown(lockedUntil)
    val wrong = stringResource(R.string.parental_wrong_pin)
    val tooShort = stringResource(R.string.parental_pin_length)
    val focus = remember { FocusRequester() }
    // A remote's keyboard would open at once; a thumb wants the field ready.
    LaunchedEffect(Unit) { if (!isTelevision) focus.requestFocus() }

    fun submit() {
        if (checking || remaining > 0L) return
        val typed = pin.toCharArray()
        if (!PinHasher.isValidPin(typed)) {
            error = tooShort
            return
        }
        checking = true
        error = null
        pin = ""
        scope.launch {
            val result = attempt(typed, System.currentTimeMillis())
            checking = false
            when (result) {
                is PinResult.Unlocked, PinResult.NoPin -> onUnlocked()
                is PinResult.Wrong -> if (dismissOnWrongPin) onDismiss() else error = wrong
                is PinResult.LockedOut -> Unit // the countdown line says so
                PinResult.PinRequired, PinResult.InvalidPin -> error = wrong
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        containerColor = EdendaleColors.Surface,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                message?.let { Text(it, style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(PinHasher.MAX_PIN_LENGTH)
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    label = { Text(stringResource(R.string.parental_pin)) },
                    singleLine = true,
                    enabled = !checking && remaining == 0L,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
                when {
                    checking -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.parental_checking_pin), style = BodyCopyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    remaining > 0L -> Text(
                        text = stringResource(R.string.parental_locked_out, DateUtils.formatElapsedTime((remaining + 999L) / 1_000L)),
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
                label = confirmLabel,
                kind = ArchiveButtonKind.Primary,
                isTelevision = isTelevision,
                enabled = pin.length >= PinHasher.MIN_PIN_LENGTH && !checking && remaining == 0L,
                onClick = { submit() },
            )
        },
    )
}

/** Milliseconds until [untilMillis], ticking once a second, or 0 when it has passed or there is none. */
@Composable
internal fun rememberCountdown(untilMillis: Long?): Long {
    var remaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(untilMillis) {
        if (untilMillis == null) {
            remaining = 0L
            return@LaunchedEffect
        }
        while (true) {
            val left = untilMillis - System.currentTimeMillis()
            if (left <= 0L) {
                remaining = 0L
                break
            }
            remaining = left
            delay(1_000L)
        }
    }
    return remaining
}

/** The restricted state's title: the Young Audience wording while only the preset is on, the rating-limit wording once a ceiling exists. */
@StringRes
internal fun restrictedTitleRes(policy: ParentalPolicy?): Int =
    if (policy?.ceiling?.isEmpty == false) R.string.parental_restricted_title else R.string.audience_restricted_title

@StringRes
internal fun restrictedMessageRes(policy: ParentalPolicy?): Int =
    if (policy?.ceiling?.isEmpty == false) R.string.parental_restricted_message else R.string.audience_restricted_message
