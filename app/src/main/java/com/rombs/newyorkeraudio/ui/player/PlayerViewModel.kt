package com.rombs.newyorkeraudio.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.ui.common.ScreenLoader
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.playback.PlaybackController
import com.rombs.newyorkeraudio.playback.PlaybackState
import com.rombs.newyorkeraudio.playback.PLAYBACK_SPEEDS
import com.rombs.newyorkeraudio.playback.PlaybackFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Controls the shared session; leaving this ViewModel never releases the service's player. */
class PlayerViewModel(
    private val repository: NewYorkerRepository,
    private val articleId: String,
    loadScope: CoroutineScope? = null,
    private val controller: PlaybackController? = null,
) : ViewModel() {
    private val loader = ScreenLoader<Article>(
        scope = loadScope ?: viewModelScope,
        load = {
            val session = controller?.state?.first { !it.isConnecting }
            session?.article?.takeIf {
                session.isConnected && session.mediaId == articleId && it.id == articleId
            } ?: repository.getArticleMetadata(articleId)
        },
        isEmpty = { it.audio == null },
    )
    val state = loader.state
    val playbackState = controller?.state ?: MutableStateFlow(PlaybackState()).asStateFlow()
    val savedPositionMs: Long get() = selectedArticle()?.let { controller?.savedPosition(it) } ?: 0

    init { refresh() }

    fun refresh() {
        loader.refresh()
    }

    /** The service owns resolution and fresh-URL retry, including position and speed. */
    fun retryAudio() {
        val article = selectedArticle() ?: return
        val current = playbackState.value
        if (current.mediaId == article.id && current.error == PlaybackFailure.STREAM) {
            controller?.retry(article)
        } else if (canRequestPlayback(article)) {
            controller?.play(article)
        }
    }

    fun cancelPendingPlayback() { controller?.cancelPendingPlaybackFor(articleId) }

    fun playPause() {
        val article = selectedArticle() ?: return
        if (!canRequestPlayback(article)) return
        val current = playbackState.value
        if (current.mediaId == article.id && current.error == PlaybackFailure.STREAM) {
            retryAudio()
            return
        }
        if (current.mediaId == article.id && current.playWhenReady && current.error == null) controller?.pause()
        else controller?.play(current.article?.takeIf {
            current.isConnected && current.mediaId == article.id && current.error == null && it.id == article.id
        } ?: article)
    }

    fun seekTo(positionMs: Long) {
        if (controlsCurrentArticle()) controller?.seekTo(positionMs)
    }
    fun skipBackward() { if (controlsCurrentArticle()) controller?.skipBackward() }
    fun skipForward() { if (controlsCurrentArticle()) controller?.skipForward() }
    fun setSpeed(speed: Float) { if (controlsCurrentArticle() && speed in PLAYBACK_SPEEDS) controller?.setSpeed(speed) }

    private fun controlsCurrentArticle(): Boolean {
        val article = selectedArticle() ?: return false
        return article.id == playbackState.value.mediaId && playbackState.value.isConnected
    }

    private fun canRequestPlayback(article: Article): Boolean = article.audioAvailable != false &&
        (!article.audio?.audioUrl.isNullOrBlank() || article.articleUrl != null)

    private fun selectedArticle(): Article? = when (val current = state.value) {
        is LoadState.Content -> current.value
        is LoadState.Empty -> current.value
        else -> null
    }
}
