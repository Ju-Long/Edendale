package com.babasama.edendale.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * Chooses what the Downloaded page shows (27.1): the whole library, or
 * Continue Watching, Movies, or TV Shows on its own — the choices of Apple's
 * DownloadedSectionPicker, and the same sections extended navigation lists as
 * rows (J.1), which follow whatever is chosen here. Shown while
 * [DownloadedSection.isPickerOffered]. A phone stretches it across the
 * page; wider windows and TV keep it at the top left at its own width.
 */
@Composable
internal fun DownloadedSectionPicker(
    sections: List<DownloadedSection>,
    selection: DownloadedSection?,
    onSelect: (DownloadedSection?) -> Unit,
    fillWidth: Boolean,
    isTelevision: Boolean,
    modifier: Modifier = Modifier,
) {
    ArchiveSegmentedButtons(
        options = DownloadedSection.pickerChoices(sections),
        selected = selection,
        onSelect = onSelect,
        label = { choice -> stringResource(choice?.shortTitle ?: R.string.section_all) },
        description = { choice -> stringResource(choice?.title ?: R.string.section_all) },
        modifier = modifier,
        fillWidth = fillWidth,
        isTelevision = isTelevision,
    )
}
