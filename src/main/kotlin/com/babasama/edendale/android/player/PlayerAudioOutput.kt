package com.babasama.edendale.android.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import com.babasama.edendale.android.R

/**
 * Audio Output (C.6.3, handhelds only): opens the system output switcher,
 * which lists the speaker, wired and Bluetooth headsets, and cast targets for
 * this app's media session. Some builds have no switcher; the row then says so
 * instead of doing nothing.
 */
@Composable
internal fun AudioOutputRow(chrome: PlayerChromeState) {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    PanelRow(
        title = stringResource(R.string.player_audio_output),
        onClick = {
            chrome.noteInteraction()
            unavailable = !SystemOutputSwitcherDialogController.showDialog(context)
        },
    )
    if (unavailable) {
        SecondaryPanelCopy(stringResource(R.string.player_audio_output_unavailable))
    }
}
