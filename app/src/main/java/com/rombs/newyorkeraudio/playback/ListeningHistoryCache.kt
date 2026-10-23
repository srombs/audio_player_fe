package com.rombs.newyorkeraudio.playback

import android.content.Context
import com.rombs.newyorkeraudio.data.repository.canonicalArticleUrl
import com.rombs.newyorkeraudio.domain.model.Article
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ListeningHistoryEntry(val article: Article, val completedAt: Long)

/** One durable completion record per article; replaying does not erase listened status. */
class ListeningHistoryCache(private val storage: ProgressStorage, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableEntries = MutableStateFlow(restore())
    val entries = mutableEntries.asStateFlow()

    fun isListened(id: String): Boolean = entries.value.any { it.article.id == id }
    fun article(id: String): Article? = entries.value.firstOrNull { it.article.id == id }?.article

    @Synchronized fun markCompleted(article: Article) {
        val metadata = sanitize(article) ?: return
        val next = (listOf(ListeningHistoryEntry(metadata, clock())) + entries.value.filterNot { it.article.id == metadata.id })
        storage.write(json.encodeToString(HistorySnapshot(entries = next)))
        mutableEntries.value = next
    }

    private fun sanitize(article: Article): Article? = try {
        require(canonicalArticleUrl(article.id) == article.id && article.title.isNotBlank())
        article.copy(articleUrl = article.id, title = article.title.take(256), author = article.author?.take(256),
            description = article.description?.take(4096), section = article.section?.take(256),
            audio = article.audio?.copy(audioUrl = null))
    } catch (_: IllegalArgumentException) { null }

    private fun restore(): List<ListeningHistoryEntry> = try {
        val saved = storage.read()?.let { json.decodeFromString<HistorySnapshot>(it) }
        saved?.takeIf { it.version == 1 }?.entries.orEmpty().filter { it.completedAt >= 0 }
            .sortedByDescending { it.completedAt }.mapNotNull { entry -> sanitize(entry.article)?.let { entry.copy(article = it) } }
            .distinctBy { it.article.id }
    } catch (_: Exception) { emptyList() }
}

@Serializable
private data class HistorySnapshot(val version: Int = 1, val entries: List<ListeningHistoryEntry>)

class PreferencesListeningHistoryStorage(context: Context) : ProgressStorage {
    private val preferences = context.applicationContext.getSharedPreferences("listening_history", Context.MODE_PRIVATE)
    override fun read(): String? = try { preferences.getString("entries", null) } catch (_: ClassCastException) { null }
    override fun write(value: String) { preferences.edit().putString("entries", value).apply() }
}

/** A position-based threshold; short recordings require at least 95% progress. */
internal fun qualifiesAsListened(positionMs: Long, durationMs: Long, ended: Boolean): Boolean {
    if (ended) return true
    if (durationMs <= 0 || positionMs <= 0) return false
    val remainingMs = (durationMs - positionMs.coerceAtMost(durationMs)).coerceAtLeast(0)
    return remainingMs <= minOf(60_000L, durationMs / 20)
}

/** Repeated near-end/ended samples describe one finish, not new history entries. */
internal class CompletionRecorder(private val history: ListeningHistoryCache) {
    private var recordedId: String? = null
    fun record(article: Article, ended: Boolean, positionMs: Long = 0, durationMs: Long = 0) {
        if (!qualifiesAsListened(positionMs, durationMs, ended)) recordedId = null
        else if (recordedId != article.id) {
            history.markCompleted(article)
            recordedId = article.id
        }
    }
}

enum class ArticleListenFilter { ALL, UNLISTENED, LISTENED }

fun filterListenedArticles(articles: List<Article>, listenedIds: Set<String>, filter: ArticleListenFilter): List<Article> =
    articles.filter { when (filter) {
        ArticleListenFilter.ALL -> true
        ArticleListenFilter.UNLISTENED -> it.id !in listenedIds
        ArticleListenFilter.LISTENED -> it.id in listenedIds
    } }
