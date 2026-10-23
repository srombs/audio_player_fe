package com.rombs.newyorkeraudio.data.repository

import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.Magazine

/** Shared contract for the sample catalog and FastAPI implementations. */
interface NewYorkerRepository {
    suspend fun getMagazines(): List<Magazine>
    suspend fun getMagazine(magazineId: String): Magazine
    suspend fun getArticles(magazineId: String): List<Article>
    suspend fun getArticle(articleId: String): Article
    /** Catalog metadata only: must not request a streaming URL. */
    suspend fun getArticleMetadata(articleId: String): Article
    /** Only playback requests resolve audio; refresh bypasses the backend's URL cache. */
    suspend fun resolveAudio(articleId: String, refresh: Boolean = false): Article = getArticle(articleId)
}
