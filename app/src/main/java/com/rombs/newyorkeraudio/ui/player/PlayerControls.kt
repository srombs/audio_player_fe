package com.rombs.newyorkeraudio.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rombs.newyorkeraudio.R
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.playback.PLAYBACK_SPEEDS
import com.rombs.newyorkeraudio.playback.PlaybackFailure
import com.rombs.newyorkeraudio.playback.PlaybackState
import com.rombs.newyorkeraudio.playback.clampPosition
import com.rombs.newyorkeraudio.playback.forArticle
import com.rombs.newyorkeraudio.playback.formatPlaybackTime
import com.rombs.newyorkeraudio.ui.common.messageResource

@Composable
fun PlayerControls(
    article: Article,
    playback: PlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeed: (Float) -> Unit,
    onRetryAudio: () -> Unit = onPlayPause,
    savedPositionMs: Long = 0,
) {
    val current = playback.forArticle(article, savedPositionMs)
    val hasSource = !current.article?.audio?.audioUrl.isNullOrBlank() || !article.audio?.audioUrl.isNullOrBlank()
    val canPlay = article.audioAvailable != false && (hasSource || article.articleUrl != null)
    val canSeek = hasSource && current.isConnected && current.canSeek && current.durationMs > 0 && current.error == null
    val pause = current.playWhenReady && current.error == null
    var dragging by remember(article.id) { mutableStateOf<Float?>(null) }
    var speedMenu by remember(article.id) { mutableStateOf(false) }
    val fraction = dragging ?: if (current.durationMs > 0) (current.positionMs.toFloat() / current.durationMs).coerceIn(0f, 1f) else 0f
    val elapsed = dragging?.let { (it * current.durationMs).toLong() } ?: current.positionMs
    val progressLabel = stringResource(R.string.playback_progress)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("player-controls")) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(clampPosition((it * current.durationMs).toLong(), current.durationMs)) }
                dragging = null
            },
            enabled = canSeek,
            modifier = Modifier.fillMaxWidth().testTag("seek").semantics { contentDescription = progressLabel },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(elapsed), modifier = Modifier.testTag("elapsed"), style = MaterialTheme.typography.bodyMedium)
            Text(formatPlaybackTime(current.durationMs), modifier = Modifier.testTag("duration"), style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSkipBack, enabled = canSeek, modifier = Modifier.testTag("skip-back")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_skip_back), stringResource(R.string.skip_back))
                    Text("15", style = MaterialTheme.typography.labelSmall)
                }
            }
            FilledIconButton(
                onClick = onPlayPause,
                enabled = canPlay && !current.isConnecting && current.error != PlaybackFailure.NO_NARRATION,
                modifier = Modifier.size(64.dp).testTag("play-pause"),
            ) {
                Icon(
                    painterResource(if (pause) R.drawable.ic_pause else R.drawable.ic_play),
                    stringResource(if (pause) R.string.pause else R.string.play),
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onSkipForward, enabled = canSeek, modifier = Modifier.testTag("skip-forward")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_skip_forward), stringResource(R.string.skip_forward))
                    Text("15", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Box(Modifier.align(Alignment.CenterHorizontally)) {
            TextButton(
                onClick = { speedMenu = true },
                enabled = hasSource && current.isConnected && current.mediaId == article.id && current.error == null,
                modifier = Modifier.testTag("speed"),
            ) { Text(stringResource(R.string.playback_speed, current.speed.toString())) }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                PLAYBACK_SPEEDS.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playback_speed, speed.toString())) },
                        onClick = { speedMenu = false; onSpeed(speed) },
                    )
                }
            }
        }
        when {
            current.error != null -> Column {
                Text(stringResource(current.resolutionFailure?.messageResource() ?: when (current.error) {
                    PlaybackFailure.CONNECTION -> R.string.player_connection_error
                    PlaybackFailure.NO_NARRATION -> R.string.no_narration
                    else -> R.string.audio_stream_error
                }))
                if (current.error != PlaybackFailure.NO_NARRATION) {
                    TextButton(onClick = onRetryAudio, modifier = Modifier.testTag("retry-audio")) { Text(stringResource(R.string.retry_audio)) }
                }
            }
            current.isConnecting || current.isBuffering -> Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(if (current.isConnecting) R.string.connecting_player else R.string.buffering_audio))
            }
            !canPlay -> Text(stringResource(R.string.audio_source_unavailable))
        }
    }
}
