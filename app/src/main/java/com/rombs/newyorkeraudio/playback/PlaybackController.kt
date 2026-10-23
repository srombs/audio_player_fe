package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.ui.common.LoadFailure
import kotlinx.coroutines.flow.StateFlow

interface PlaybackController {
    val state: StateFlow<PlaybackState>
    fun play(article: Article)
    fun retry(article: Article) = play(article)
    fun cancelPendingPlayback() {}
    fun cancelPendingPlaybackFor(articleId: String) = cancelPendingPlayback()
    fun pause()
    fun seekTo(positionMs: Long)
    fun skipBackward()
    fun skipForward()
    fun setSpeed(speed: Float)
    fun savedPosition(article: Article): Long = 0
}

enum class PlaybackFailure { CONNECTION, STREAM, RESOLUTION, NO_NARRATION }

data class PlaybackState(
    val mediaId: String? = null,
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val canSeek: Boolean = false,
    val error: PlaybackFailure? = null,
    val article: Article? = null,
    val resolutionFailure: LoadFailure? = null,
)

/** A different article must not display the active session's progress or error. */
fun PlaybackState.forArticle(article: Article, savedPositionMs: Long = 0): PlaybackState = if (mediaId == article.id) this else copy(
    mediaId = null,
    isPlaying = false,
    playWhenReady = false,
    isBuffering = false,
    positionMs = clampPosition(savedPositionMs, article.audio?.durationMs ?: 0),
    durationMs = article.audio?.durationMs?.coerceAtLeast(0) ?: 0,
    speed = 1f,
    canSeek = false,
    error = error?.takeIf { it == PlaybackFailure.CONNECTION },
    article = null,
    resolutionFailure = null,
)
