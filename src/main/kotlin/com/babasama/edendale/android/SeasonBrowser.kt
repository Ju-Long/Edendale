package com.babasama.edendale.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.babasama.edendale.domain.SeasonSummary
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.tmdbImageUrl
import com.babasama.edendale.tmdb.TmdbEpisodeDetail

/**
 * Season picker plus episode shelf for shows browsed on TMDB — the Android
 * counterpart of Apple's `TMDBSeasonBrowser`.
 *
 * Seasons arrive with the show's detail response (already ordered
 * numbered-ascending with Specials last by the TMDB mapping); each season's
 * episode list is fetched on demand and cached by `BrowseViewModel`. Episodes
 * have no local file behind them, so a card toggles watch state rather than
 * starting playback.
 */
@Composable
fun SeasonBrowser(
    seasons: List<SeasonSummary>,
    selectedSeason: Int?,
    episodes: List<TmdbEpisodeDetail>?,
    isLoading: Boolean,
    errorMessage: String?,
    watchedEpisodeIds: Set<Int>,
    isTelevision: Boolean,
    edgeMargin: androidx.compose.ui.unit.Dp,
    onSelectSeason: (Int) -> Unit,
    onToggleWatched: (TmdbEpisodeDetail) -> Unit,
) {
    if (seasons.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = stringResource(R.string.section_episodes),
            modifier = Modifier.padding(horizontal = edgeMargin),
            large = isTelevision,
        )

        LazyRow(
            contentPadding = PaddingValues(
                horizontal = edgeMargin,
                vertical = if (isTelevision) 12.dp else 0.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(seasons, key = { it.seasonNumber }) { season ->
                ArchiveFilterChip(
                    selected = season.seasonNumber == selectedSeason,
                    onClick = { onSelectSeason(season.seasonNumber) },
                    label = { Text(seasonTitle(season).uppercase()) },
                    isTelevision = isTelevision,
                )
            }
        }

        when {
            episodes != null && episodes.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(
                    horizontal = edgeMargin,
                    vertical = if (isTelevision) 12.dp else 0.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(episodes, key = { it.id }) { episode ->
                    EpisodeCard(
                        episode = episode,
                        isWatched = episode.id in watchedEpisodeIds,
                        isTelevision = isTelevision,
                        onClick = { onToggleWatched(episode) },
                    )
                }
            }

            episodes != null -> Message(
                stringResource(R.string.season_no_episodes),
                edgeMargin,
            )

            isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = edgeMargin, vertical = 24.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }

            errorMessage != null -> Message(errorMessage, edgeMargin)
        }
    }
}

/** "Season 2" for numbered seasons, TMDB's own name for Specials. */
@Composable
private fun seasonTitle(season: SeasonSummary): String =
    if (season.seasonNumber == 0) season.name
    else stringResource(R.string.season_number, season.seasonNumber)

@Composable
private fun EpisodeCard(
    episode: TmdbEpisodeDetail,
    isWatched: Boolean,
    isTelevision: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(if (isTelevision) 420.dp else 280.dp)
            .tvFocusLift(isTelevision),
        shape = RoundedCornerShape(EdendaleRadii.Card.dp),
        colors = CardDefaults.cardColors(containerColor = EdendaleColors.SurfaceLow),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            val still = tmdbImageUrl(episode.stillPath, TmdbImageSize.BACKDROP)
            if (still != null) {
                AsyncImage(
                    model = still,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_tv),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center),
                    tint = EdendaleColors.SurfaceHigh,
                )
            }

            if (isWatched) {
                // Same scrim + gold tick the poster cards use for watched items.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, EdendaleColors.Background),
                            ),
                        ),
                )
                Icon(
                    painter = painterResource(id = R.drawable.ic_check),
                    contentDescription = stringResource(R.string.watched),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(18.dp),
                    tint = EdendaleColors.Gold,
                )
            }
        }

        Column(Modifier.padding(12.dp)) {
            Text(
                text = episode.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle(episode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun subtitle(episode: TmdbEpisodeDetail): String {
    val code = episodeCode(episode)
    val runtime = episode.runtimeMinutes?.takeIf { it > 0 } ?: return code
    return stringResource(
        R.string.metadata_separator,
        code,
        stringResource(R.string.runtime_minutes, runtime),
    )
}

/** "S01E02", matching the parser's episode identity. */
@Composable
private fun episodeCode(episode: TmdbEpisodeDetail): String {
    val season = episode.seasonNumber
        ?: return stringResource(R.string.episode_number, episode.episodeNumber ?: 0)
    val number = episode.episodeNumber ?: return stringResource(R.string.season_number, season)
    return stringResource(R.string.episode_code, season, number)
}

@Composable
private fun Message(text: String, edgeMargin: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = edgeMargin, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
