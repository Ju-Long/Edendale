package com.babasama.edendale.android

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Hardware-keyboard commands for the library window (J.3). MainActivity
 * raises them for keys nothing on screen handled; the shell and the
 * Downloaded page act on the ones that apply to what's showing.
 */
enum class LibraryCommand { TOGGLE_NAVIGATION, ADD_FOLDER, LINK_SOURCE, RESCAN }

/** Player keys on a hardware keyboard (J.3); S for the skip prompt is handled with it. */
enum class PlayerKeyCommand { PLAY_PAUSE, SKIP_BACK, SKIP_FORWARD, CLOSE }

object KeyboardShortcuts {
    /**
     * Ctrl+B toggles the navigation; Ctrl+N adds a media folder and
     * Ctrl+Alt+N links a network source; Ctrl+R or F5 rescans.
     */
    fun libraryCommand(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): LibraryCommand? {
        if (shift || meta) return null
        if (keyCode == KeyEvent.KEYCODE_F5) return if (!ctrl && !alt) LibraryCommand.RESCAN else null
        if (!ctrl) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_B -> LibraryCommand.TOGGLE_NAVIGATION.takeUnless { alt }
            KeyEvent.KEYCODE_N -> if (alt) LibraryCommand.LINK_SOURCE else LibraryCommand.ADD_FOLDER
            KeyEvent.KEYCODE_R -> LibraryCommand.RESCAN.takeUnless { alt }
            else -> null
        }
    }

    fun libraryCommand(event: KeyEvent): LibraryCommand? = libraryCommand(
        keyCode = event.keyCode,
        ctrl = event.isCtrlPressed,
        alt = event.isAltPressed,
        shift = event.isShiftPressed,
        meta = event.isMetaPressed,
    )

    /**
     * Space plays and pauses, ← and → skip by the App Controls lengths, and
     * Esc closes a panel, then the player. Unmodified keys only.
     */
    fun playerCommand(keyCode: Int, hasModifiers: Boolean): PlayerKeyCommand? {
        if (hasModifiers) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> PlayerKeyCommand.PLAY_PAUSE
            KeyEvent.KEYCODE_DPAD_LEFT -> PlayerKeyCommand.SKIP_BACK
            KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerKeyCommand.SKIP_FORWARD
            KeyEvent.KEYCODE_ESCAPE -> PlayerKeyCommand.CLOSE
            else -> null
        }
    }

    /** The library group for Meta+/ (the system's keyboard shortcuts list). */
    fun libraryGroup(context: Context, isTelevision: Boolean, navigationHidden: Boolean): KeyboardShortcutGroup {
        val items = buildList {
            if (!isTelevision) {
                add(info(context.getString(if (navigationHidden) R.string.navigation_show else R.string.navigation_hide), KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON))
                add(info(context.getString(R.string.add_local_folder), KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON))
            }
            add(info(context.getString(R.string.add_network_source), KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON))
            add(info(context.getString(R.string.rescan_library), KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON))
            add(info(context.getString(R.string.rescan_library), KeyEvent.KEYCODE_F5, 0))
        }
        return KeyboardShortcutGroup(context.getString(R.string.app_name), items)
    }

    /** The player group for Meta+/. */
    fun playerGroup(context: Context, backSeconds: Int, forwardSeconds: Int): KeyboardShortcutGroup =
        KeyboardShortcutGroup(
            context.getString(R.string.app_name),
            listOf(
                info(context.getString(R.string.shortcut_play_pause), KeyEvent.KEYCODE_SPACE, 0),
                info(context.resources.getQuantityString(R.plurals.shortcut_back_seconds, backSeconds, backSeconds), KeyEvent.KEYCODE_DPAD_LEFT, 0),
                info(context.resources.getQuantityString(R.plurals.shortcut_forward_seconds, forwardSeconds, forwardSeconds), KeyEvent.KEYCODE_DPAD_RIGHT, 0),
                info(context.getString(R.string.shortcut_skip_prompt), KeyEvent.KEYCODE_S, 0),
                info(context.getString(R.string.shortcut_close_panel_then_player), KeyEvent.KEYCODE_ESCAPE, 0),
            ),
        )

    private fun info(label: String, keyCode: Int, modifiers: Int) = KeyboardShortcutInfo(label, keyCode, modifiers)
}

/** MainActivity's side of the library shortcuts: the commands it raises, and what Meta+/ should offer. */
class LibraryKeyboard {
    val commands = MutableSharedFlow<LibraryCommand>(extraBufferCapacity = 8)

    /** Whether the navigation is hidden, so Meta+/ offers Show or Hide. */
    @Volatile var navigationHidden = false
}
