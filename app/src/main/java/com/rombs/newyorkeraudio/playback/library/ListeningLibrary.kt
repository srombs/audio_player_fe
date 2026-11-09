package com.rombs.newyorkeraudio.playback.library

import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.canonicalArticleUrl
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.playback.NoLastPlayedException
import com.rombs.newyorkeraudio.playback.PlaybackProgressCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** Reads bounded metadata/progress only. Resolving a source remains the playback workflow's job. */
internal class ListeningLibrary(private val catalog: CatalogLibrary, private val repository: NewYorkerRepository,
    private val progress: PlaybackProgressCache, private val lastArticle: () -> Article?) {
    suspend fun unfinishedArticles(): List<Article> {
        for (entry in progress.unfinished()) {
            val cached = catalog.cachedArticle(entry.articleId)
            val metadata = cached ?: entry.article ?: try { repository.getArticleMetadata(entry.articleId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { lastArticle()?.takeIf { it.id == entry.articleId } }
            if (metadata != null) {
                val known = entry.article
                progress.attachMetadata(if (metadata.audioAvailable == null && known?.audioAvailable != null)
                    metadata.copy(audioAvailable = known.audioAvailable, audio = known.audio) else metadata)
            }
        }
        return progress.unfinished().mapNotNull { entry ->
            val article = entry.article ?: fallbackMetadata(entry.articleId)
            article?.takeIf { it.audioAvailable != false &&
                (entry.durationMs == 0L || entry.positionMs < entry.durationMs) &&
                ((it.audio?.durationMs ?: 0) <= 0 || entry.positionMs < it.audio!!.durationMs!!) }
        }
    }

    suspend fun continueNodes(): List<LibraryNode> = unfinishedArticles().map { article ->
        val entry = progress.unfinished().first { it.articleId == article.id }
        LibraryNode(article.id, article.title, article.author, article.imageUrl, playable = true, browsable = false,
            positionMs = entry.positionMs,
            durationMs = entry.durationMs.takeIf { it > 0 } ?: article.audio?.durationMs?.takeIf { it > 0 },
            description = article.description)
    }

    suspend fun search(query: String): List<Article> {
        if (query.length > 256) throw VoiceQueryTooLongException()
        val unfinished = unfinishedArticles()
        if (query.isBlank()) return unfinished.ifEmpty {
            listOfNotNull(lastArticle()?.takeIf { it.audioAvailable != false })
        }
        if (normalizeSearch(query).isEmpty()) return emptyList()
        val recent = try { withTimeout(10_000) { catalog.recentArticles() } }
            catch (timeout: TimeoutCancellationException) { RecentArticles(emptyList(), java.io.IOException("Search metadata timed out.", timeout)) }
        val candidates = (unfinished + recent.articles).distinctBy { it.id }.filter { it.audioAvailable != false }
        val matches = withContext(Dispatchers.Default) { searchMatches(query, candidates).take(100) }
        if (matches.isEmpty() && recent.failure != null) throw recent.failure
        return matches
    }

    suspend fun voiceArticle(query: String): Article {
        val matches = search(query)
        if (query.isBlank()) return matches.firstOrNull() ?: throw NoLastPlayedException()
        return chooseVoiceMatch(query, matches)
    }

    private fun fallbackMetadata(id: String): Article? = try {
        val canonical = canonicalArticleUrl(id)
        require(canonical == id)
        val title = URI(canonical).path.substringAfterLast('/').replace('-', ' ').take(256).ifBlank { "Saved article" }
        Article(canonical, title, null, canonical, null, null, null, audioAvailable = null)
    } catch (_: IllegalArgumentException) { null }
}

internal fun normalizeSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("['’]"), "")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

internal fun searchMatches(query: String, candidates: List<Article>): List<Article> {
    val text = normalizeSearch(query)
    if (text.isEmpty()) return emptyList()
    val words = text.split(' ').filter { it.isNotEmpty() }
    return candidates.mapIndexedNotNull { index, article ->
        val title = normalizeSearch(article.title)
        val author = normalizeSearch(article.author.orEmpty())
        val rank = when {
            title == text -> 0
            title.contains(text) -> 1
            words.all { title.contains(it) } -> 2
            author.contains(text) -> 3
            words.all { "$title $author".contains(it) } -> 4
            else -> return@mapIndexedNotNull null
        }
        Triple(rank, index, article)
    }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
}

internal fun chooseVoiceMatch(query: String, matches: List<Article>): Article {
    if (matches.isEmpty()) throw NoVoiceMatchException()
    val exact = matches.filter { normalizeSearch(it.title) == normalizeSearch(query) }
    if (exact.size == 1) return exact.single()
    if (matches.size == 1) return matches.single()
    throw AmbiguousVoiceMatchException()
}

internal class NoVoiceMatchException : IllegalArgumentException("No matching article in recent issues or Continue Listening.")
internal class AmbiguousVoiceMatchException : IllegalArgumentException("Several articles match. Try a more specific title.")
internal class VoiceQueryTooLongException : IllegalArgumentException("Try a shorter title or author.")
