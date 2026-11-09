package com.rombs.newyorkeraudio.data.repository

import com.rombs.newyorkeraudio.data.network.AudioRequest
import com.rombs.newyorkeraudio.data.network.BackendApi
import com.rombs.newyorkeraudio.data.network.BackendFailure
import com.rombs.newyorkeraudio.data.network.BackendFailureKind
import com.rombs.newyorkeraudio.data.network.backendRequest
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.Magazine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class NetworkNewYorkerRepository(private val api: BackendApi, metadataStorage: ArticleMetadataStorage? = null,
    private val historyMetadata: (String) -> Article? = { null }) : NewYorkerRepository {
    private val mutex = Mutex()
    private val magazines = mutableMapOf<String, Magazine>()
    private val summaries = ArticleMetadataCache(metadataStorage)

    override suspend fun getMagazines(): List<Magazine> {
        val result = backendRequest { api.getIssues() }.issues.map { it.toMagazine() }
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            magazines.clear()
            result.forEach { magazines[it.id] = it }
        }
        return result
    }

    override suspend fun getMagazine(magazineId: String): Magazine {
        requireIssueDate(magazineId)
        return mutex.withLock { magazines[magazineId] }
            ?: getMagazines().firstOrNull { it.id == magazineId }
            ?: throw NoSuchElementException("Unknown issue: $magazineId")
    }

    override suspend fun getArticles(magazineId: String): List<Article> {
        requireIssueDate(magazineId)
        val response = backendRequest { api.getIssueArticles(magazineId) }
        if (response.issue.issueDate != magazineId) throw BackendFailure(BackendFailureKind.INVALID_RESPONSE)
        val magazine = response.issue.toMagazine()
        val articles = response.articles.map { it.toArticle() }.distinctBy { it.id }
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            magazines[magazine.id] = magazine
            summaries.putAll(articles)
        }
    }

    /** URL-based IDs allow a restored player to resolve audio even with an empty summary cache. */
    override suspend fun getArticleMetadata(articleId: String): Article {
        val url = canonicalArticleUrl(articleId)
        return mutex.withLock { summaries.get(url) }
            ?: historyMetadata(url)
            ?: throw NoSuchElementException("Article metadata is not cached: $url")
    }

    override suspend fun getArticle(articleId: String): Article = resolveAudio(articleId)

    override suspend fun resolveAudio(articleId: String, refresh: Boolean): Article {
        val url = canonicalArticleUrl(articleId)
        val response = try {
            backendRequest { api.resolveAudio(AudioRequest(url, refresh)) }
        } catch (error: BackendFailure) {
            // This resolver returns 200/null for no narration and 502 for upstream failures.
            // An unstructured 404 here can mean the deployed audio route is missing.
            if (error.httpStatus == 404 && error.code == null) {
                throw BackendFailure(BackendFailureKind.HTTP, 404, "audio_endpoint_unavailable", "contact_operator", error)
            }
            throw error
        }
        val summary = mutex.withLock { summaries.get(url) } ?: historyMetadata(url)
        val article = response.toArticle(url, summary)
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            // Retain duration and availability, but never cache an expiring source here.
            summaries.putAll(listOf(article))
        }
        return article
    }

    private fun requireIssueDate(value: String) {
        require(LocalDate.parse(value).toString() == value) { "Expected an ISO issue date." }
    }
}
