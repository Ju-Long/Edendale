package com.babasama.edendale.android

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.babasama.edendale.domain.AppRoute
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingRoute = MutableStateFlow<AppRoute?>(null)
    private val keyboard = LibraryKeyboard()
    private var isTelevision = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val dataString = intent.dataString
            if (dataString != null) {
                AppRoute.parse(dataString)?.let {
                    pendingRoute.value = it
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        isTelevision = isTelevisionDevice()
        applyTelevisionWindow(isTelevision)

        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            val route by pendingRoute.collectAsState()
            EdendaleTheme(isTelevision = isTelevision) {
                EdendaleApp(
                    isTelevision = isTelevision,
                    pendingRoute = route,
                    onRouteConsumed = { pendingRoute.value = null },
                    keyboard = keyboard,
                )
            }
        }
    }

    /**
     * Library shortcuts (J.3) for keys nothing on screen handled, so a text
     * field keeps its own keys. The shell and the Downloaded page act on the
     * commands that apply to what's showing.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            KeyboardShortcuts.libraryCommand(event)?.let { command ->
                if (keyboard.commands.tryEmit(command)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Lists the library shortcuts in the system's keyboard shortcuts list (Meta+/). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.add(KeyboardShortcuts.libraryGroup(this, isTelevision, keyboard.navigationHidden))
    }

    private fun applyTelevisionWindow(isTelevision: Boolean) {

        if (isTelevision) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}

/**
 * Whether this device drives the app with a remote rather than touch. Shared
 * by [MainActivity] and the player, which is its own activity — two private
 * copies of this predicate would drift.
 */
internal fun Context.isTelevisionDevice(): Boolean {
    val modeType = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return modeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
