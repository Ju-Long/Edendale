package com.babasama.edendale.android

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
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
        val isTelevision = isTelevisionDevice()
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
                    onRouteConsumed = { pendingRoute.value = null }
                )
            }
        }
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
