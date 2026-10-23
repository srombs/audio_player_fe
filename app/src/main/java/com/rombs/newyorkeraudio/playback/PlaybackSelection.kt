package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article

/** Shared by phone commands and car media-ID requests. No controller-provided stream is used. */
data class PlaybackSelectionRequest(
    val articleId: String,
    val startPositionMs: Long? = null,
    val refresh: Boolean = false,
    val resumeSpeed: Float? = null,
)

data class PreparedPlaybackSelection(val article: Article, val positionMs: Long, val speed: Float, val reuseActive: Boolean)

class NarrationUnavailableException : Exception("This article has no narration.")

internal suspend fun preparePlaybackSelection(
    request: PlaybackSelectionRequest,
    repository: NewYorkerRepository,
    progressCache: PlaybackProgressCache,
    activeArticle: Article?,
    activePositionMs: Long,
    activeSpeed: Float,
    activeHealthy: Boolean,
): PreparedPlaybackSelection {
    require(request.articleId.isNotBlank())
    require(request.startPositionMs == null || request.startPositionMs >= 0)
    val sameArticle = activeArticle?.id == request.articleId
    val reuse = sameArticle && activeHealthy && !request.refresh
    val article = if (reuse) requireNotNull(activeArticle) else repository.resolveAudio(request.articleId, request.refresh)
    require(article.id == request.articleId) { "Resolved audio does not match the selected article." }
    if (article.audio?.audioUrl.isNullOrBlank()) throw NarrationUnavailableException()
    val duration = article.audio?.durationMs ?: 0
    val position = request.startPositionMs
        ?: if (sameArticle) activePositionMs else progressCache.resumePosition(article.id, duration)
    val start = if (request.startPositionMs == null) resumePlaybackPosition(position, duration) else clampPosition(position, duration)
    val speed = if (sameArticle) activeSpeed else request.resumeSpeed ?: progressCache.resumeSpeed(article.id)
    return PreparedPlaybackSelection(article, start, validPlaybackSpeed(speed), reuse)
}
