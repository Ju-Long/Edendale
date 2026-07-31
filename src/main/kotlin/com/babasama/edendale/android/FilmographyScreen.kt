package com.babasama.edendale.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.PersonDetail

@Composable
fun FilmographyScreen(
    state: FilmographyUiState,
    isTelevision: Boolean,
    onBack: () -> Unit,
    onOpenDetail: (MediaRef) -> Unit,
) {
    BackHandler(onBack = onBack)
    val windowSize = currentWindowSizeDp()
    val edgeMargin = if (isTelevision || windowSize.width >= 600.dp) 48.dp else 20.dp
    // The Back chip floats in a sibling Box, and D-pad Down does not cross from
    // it into the list, which left the filmography cards unreachable on TV.
    val listFocus = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading && state.detail == null -> ArchiveLoadingState()
            state.errorMessage != null && state.detail == null -> ArchiveEmptyState(
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_cloud_slash),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                },
                title = stringResource(R.string.filmography_error_title),
                message = state.errorMessage,
                action = {
                    ArchiveButton(
                        label = stringResource(R.string.action_go_back),
                        onClick = onBack,
                        kind = ArchiveButtonKind.Primary,
                        isTelevision = isTelevision,
                    )
                },
            )
            state.items.isEmpty() && state.detail == null -> ArchiveEmptyState(
                icon = { Icon(painterResource(id = R.drawable.ic_magnifying_glass_play), contentDescription = null) },
                title = stringResource(R.string.filmography_empty_title),
                message = stringResource(R.string.filmography_empty_message),
                action = {
                    ArchiveButton(
                        label = stringResource(R.string.action_go_back),
                        onClick = onBack,
                        kind = ArchiveButtonKind.Primary,
                        isTelevision = isTelevision,
                    )
                },
            )
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(listFocus)
                        .focusGroup(),
                    contentPadding = PaddingValues(
                        top = 100.dp, // Below the back button
                        bottom = 56.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    item("person") {
                        PersonHeader(
                            detail = state.detail,
                            fallbackName = state.personName,
                            edgeMargin = edgeMargin,
                            stacked = windowSize.width < 600.dp && !isTelevision,
                        )
                    }
                    if (state.items.isNotEmpty()) {
                        item("title") {
                            SectionHeader(
                                title = stringResource(R.string.filmography_title),
                                modifier = Modifier.padding(horizontal = edgeMargin),
                                large = isTelevision,
                            )
                        }
                        item("results") {
                            FilmographyGrid(
                                items = state.items,
                                edgeMargin = edgeMargin,
                                isTelevision = isTelevision,
                                onOpenDetail = onOpenDetail,
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(edgeMargin)
                .statusBarsPadding()
                .focusProperties { if (isTelevision) down = listFocus }
                .tvFocusLift(isTelevision),
            onClick = onBack,
            shape = RoundedCornerShape(50),
            color = EdendaleColors.SurfaceLow.copy(alpha = .9f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(id = R.drawable.ic_chevron_left), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_back))
            }
        }
    }
}

/**
 * Portrait beside the biography, stacking under 600dp so neither column drops
 * below a readable width. Renders from [fallbackName] alone while
 * `/person/{id}` is still in flight.
 */
@Composable
private fun PersonHeader(
    detail: PersonDetail?,
    fallbackName: String?,
    edgeMargin: Dp,
    stacked: Boolean,
) {
    val portraitWidth = if (stacked) 160.dp else 200.dp

    @Composable
    fun portrait() {
        Box(
            modifier = Modifier
                .width(portraitWidth)
                .height(portraitWidth * 1.5f)
                .clip(RoundedCornerShape(12.dp))
                .background(EdendaleColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            val url = detail?.profileUrl()
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_circle_user_fill),
                    contentDescription = null,
                    modifier = Modifier.size(portraitWidth * 0.4f),
                    tint = EdendaleColors.SurfaceHigh,
                )
            }
        }
    }

    @Composable
    fun biography(modifier: Modifier = Modifier) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            detail?.knownForDepartment?.let { department ->
                Text(
                    text = department.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = EdendaleColors.Gold,
                )
            }
            Text(
                text = detail?.name ?: fallbackName.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            detail?.vitals?.let { vitals ->
                Text(
                    text = vitals,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val biography = detail?.biography
            if (biography != null) {
                var expanded by rememberSaveable(detail.id) { mutableStateOf(false) }
                Text(
                    text = biography,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
                Text(
                    text = stringResource(if (expanded) R.string.action_show_less else R.string.action_read_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = EdendaleColors.Gold,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            } else if (detail != null) {
                Text(
                    text = stringResource(R.string.person_no_biography),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = edgeMargin),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            portrait()
            biography()
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = edgeMargin),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            portrait()
            biography(Modifier.weight(1f))
        }
    }
}

@Composable
private fun FilmographyGrid(
    items: List<MediaItem>,
    edgeMargin: Dp,
    isTelevision: Boolean,
    onOpenDetail: (MediaRef) -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = edgeMargin),
    ) {
        val spacing = if (isTelevision) 24.dp else 16.dp
        val minimumWidth = if (isTelevision) 180.dp else 145.dp
        val columnCount = ((maxWidth + spacing) / (minimumWidth + spacing)).toInt().coerceAtLeast(2)
        val cardWidth = (maxWidth - spacing * (columnCount - 1)) / columnCount

        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columnCount).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    rowItems.forEach { item ->
                        PosterCard(
                            item = item,
                            width = cardWidth,
                            isTelevision = isTelevision,
                            onClick = { onOpenDetail(item.ref) },
                        )
                    }
                }
            }
        }
    }
}
