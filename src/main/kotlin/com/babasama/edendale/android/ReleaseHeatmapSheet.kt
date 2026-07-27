package com.babasama.edendale.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.babasama.edendale.domain.ReleaseYearGrid
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseHeatmapSheet(
    heatmapCache: Map<Int, Map<String, Int>>,
    onLoadHeatmap: (Int) -> Unit,
    selectedRange: Pair<String, String>?,
    onRangeSelected: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    var currentYear by remember { mutableIntStateOf(thisYear) }
    var startSelection by remember { mutableStateOf<String?>(null) }
    var endSelection by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedRange) {
        if (selectedRange != null && startSelection == null && endSelection == null) {
            startSelection = selectedRange.first
            endSelection = selectedRange.second
            selectedRange.first.take(4).toIntOrNull()?.let {
                currentYear = it
            }
        }
    }

    LaunchedEffect(currentYear) {
        onLoadHeatmap(currentYear)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { currentYear -= 1 },
                    enabled = currentYear > 1874
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.heatmap_previous_year))
                }
                Text(
                    text = currentYear.toString(),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(
                    onClick = { currentYear += 1 },
                    enabled = currentYear < thisYear
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.heatmap_next_year))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val grid = remember(currentYear) { ReleaseYearGrid(currentYear) }
            val counts = heatmapCache[currentYear] ?: emptyMap()

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(grid.columns) { col ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = col.monthLabel ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.height(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        for (slot in col.slots) {
                            if (slot == null) {
                                Spacer(modifier = Modifier.size(16.dp))
                            } else {
                                val count = counts[slot.dateKey] ?: 0
                                val isSelected = (startSelection == slot.dateKey) || (endSelection == slot.dateKey)
                                val isInRange = startSelection != null && endSelection != null && slot.dateKey > startSelection!! && slot.dateKey < endSelection!!

                                val bgColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else if (isInRange) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    when {
                                        count == 0 -> EdendaleColors.SurfaceLow
                                        count == 1 -> EdendaleColors.HeatLow
                                        count > 1 -> EdendaleColors.HeatMid
                                        else -> EdendaleColors.SurfaceLow
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(bgColor)
                                        .border(
                                            width = 1.dp,
                                            color = Color.White,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .clickable {
                                            val dateStr = slot.dateKey
                                            if (startSelection == null || (startSelection != null && endSelection != null)) {
                                                startSelection = dateStr
                                                endSelection = null
                                            } else {
                                                val s = startSelection!!
                                                if (dateStr < s) {
                                                    startSelection = dateStr
                                                    endSelection = s
                                                } else {
                                                    endSelection = dateStr
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = startSelection != null && endSelection != null,
                onClick = {
                    if (startSelection != null && endSelection != null) {
                        onRangeSelected(startSelection!!, endSelection!!)
                    }
                }
            ) {
                Text(stringResource(R.string.heatmap_apply_range))
            }
        }
    }
}
