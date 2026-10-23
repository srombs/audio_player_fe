package com.rombs.newyorkeraudio.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rombs.newyorkeraudio.R
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.ui.common.CatalogArtwork
import com.rombs.newyorkeraudio.ui.common.CatalogScaffold
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.common.LoadedContent
import com.rombs.newyorkeraudio.ui.common.NarrationMetadata
import com.rombs.newyorkeraudio.ui.common.articlePlaceholder
import com.rombs.newyorkeraudio.playback.PlaybackState

@Composable
fun PlayerScreen(
    state: LoadState<Article>,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    playback: PlaybackState = PlaybackState(),
    onPlayPause: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    onSpeed: (Float) -> Unit = {},
    onRetryAudio: () -> Unit = onPlayPause,
    savedPositionMs: Long = 0,
) {
    CatalogScaffold(title = stringResource(R.string.player_title), onBack = onBack) { padding ->
        LoadedContent(state, onRetry, Modifier.padding(padding).consumeWindowInsets(padding),
            loadingMessage = stringResource(R.string.loading)) { article, modifier ->
            LazyColumn(
                modifier = modifier.fillMaxSize().testTag("player:${article.id}"),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "artwork") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CatalogArtwork(
                            imageUrl = article.imageUrl,
                            placeholder = articlePlaceholder(article.section),
                            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)).testTag("artwork:${article.id}"),
                        )
                    }
                }
                item(key = "metadata") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        article.section?.let {
                            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(article.title, style = MaterialTheme.typography.headlineLarge)
                        article.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NarrationMetadata(article.audio)
                    }
                }
                if (article.audio != null) {
                    item(key = "controls") {
                        PlayerControls(article, playback, onPlayPause, onSeek, onSkipBack, onSkipForward, onSpeed, onRetryAudio, savedPositionMs)
                    }
                }
                if (article.audio == null) item(key = "playback-status") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.no_narration), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}
