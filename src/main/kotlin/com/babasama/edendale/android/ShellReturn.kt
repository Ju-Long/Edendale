package com.babasama.edendale.android

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

// Coming back to the shell. A title's page, a show, a filmography and TV
// Settings replace the shell (EdendaleApp) rather than cover it, so Back
// builds the shell again; what follows puts the viewer back where they were.

/**
 * The shell's state while a full-screen page is up: its tabs' saved state
 * (scroll positions, the poster that opened the page), and the live scroll
 * states of its lists ([rememberShellListState]).
 */
@Stable
internal class ShellState(private val holder: SaveableStateHolder) {
    val lists = ShellListStates()

    /** The shell, under this state. */
    @Composable
    fun Shell(content: @Composable () -> Unit) {
        holder.SaveableStateProvider("shell") {
            CompositionLocalProvider(LocalShellListStates provides lists, content = content)
        }
    }
}

@Composable
internal fun rememberShellState(): ShellState {
    val holder = rememberSaveableStateHolder()
    return remember(holder) { ShellState(holder) }
}

/**
 * Lists' live scroll states, parked while a page replaces the shell. Saved
 * state alone brings a list back at its old index, which lands rows off when
 * items above moved meanwhile: a Continue Watching shelf that playing from
 * the page added or emptied, a scan's progress row. A live state also knows
 * its first visible row by key, so the list finds that row again.
 */
internal class ShellListStates {
    private val parked = mutableMapOf<String, LazyListState>()

    fun park(key: String, state: LazyListState) {
        parked[key] = state
    }

    fun take(key: String): LazyListState? = parked.remove(key)

    /** Another tab or section is showing, so nothing parked is coming back. */
    fun clear() = parked.clear()
}

internal val LocalShellListStates = staticCompositionLocalOf<ShellListStates?> { null }

/**
 * A shell list's scroll state: saved as `rememberLazyListState()` is, so
 * rotation and process death bring it back by index, and live across a
 * full-screen page ([ShellListStates]), so Back brings it back by key. [key]
 * names the list among the shell's.
 */
@Composable
internal fun rememberShellListState(key: String): LazyListState {
    val lists = LocalShellListStates.current
    val saver = remember(key, lists) {
        listSaver<LazyListState, Int>(
            // Saved as the shell goes (and whenever the activity saves its
            // state): the live state waits for Back.
            save = { state ->
                lists?.park(key, state)
                listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            },
            restore = { (index, offset) -> lists?.take(key) ?: LazyListState(index, offset) },
        )
    }
    return rememberSaveable(saver = saver) {
        // A visit of its own starts at the top; anything parked is an earlier visit's.
        lists?.take(key)
        LazyListState()
    }
}

/**
 * TV: Back from a title's page puts the remote back on the poster that
 * opened it. The page replaced the shell, so the focus went with it; which
 * poster it was comes back with the shell's saved state. Only the first
 * return does, so coming back from Settings later doesn't pull focus down to
 * an old poster.
 */
@Stable
internal class PosterReturn(
    private val isTelevision: Boolean,
    private val opened: MutableState<String?>,
    private val returning: MutableState<Boolean>,
    private val focus: FocusRequester,
) {
    /** Call as the poster [key] opens its page. */
    fun opening(key: String) {
        opened.value = key
        returning.value = true
    }

    /** The poster [key]'s modifier: the focus target, for the poster that opened the page. */
    fun modifier(key: String): Modifier =
        if (isTelevision && key == opened.value) Modifier.focusRequester(focus) else Modifier
}

/** [PosterReturn] for a page whose posters are all composed once [ready]. */
@Composable
internal fun rememberPosterReturn(isTelevision: Boolean, ready: Boolean): PosterReturn {
    val opened = rememberSaveable { mutableStateOf<String?>(null) }
    val returning = rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Read once, as the page is built: true only for a page brought back with a poster to return to.
    val backFromPoster = remember { isTelevision && returning.value }
    LaunchedEffect(backFromPoster && ready) {
        if (!backFromPoster || !ready) return@LaunchedEffect
        // Posters are lazy items, composed in the layout pass after this starts.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        returning.value = false
    }
    return remember(isTelevision) { PosterReturn(isTelevision, opened, returning, focus) }
}
