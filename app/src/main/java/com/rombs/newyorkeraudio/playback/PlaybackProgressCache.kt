package com.rombs.newyorkeraudio.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.data.repository.canonicalArticleUrl
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface ProgressStorage {
    fun read(): String?
    fun write(value: String)
}

/** A bounded, persisted cache keyed by stable article identity, independently of streaming URLs. */
class PlaybackProgressCache(private val storage: ProgressStorage, private val maxEntries: Int = 100) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entries = linkedMapOf<String, ProgressEntry>()
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = mutableChanges.asSharedFlow()

    init {
        require(maxEntries > 0)
        val saved = try {
            storage.read()?.let { json.decodeFromString<ProgressSnapshot>(it) }
        } catch (_: SerializationException) {
            null
        }
        if (saved != null && saved.version in listOf(1, 2, 3)) {
            saved.entries.forEach { entry ->
                if (entry.articleId.isNotBlank() && entry.positionMs > 0 && entry.durationMs >= 0 &&
                    (entry.durationMs == 0L || entry.positionMs < entry.durationMs)) {
                    entries.remove(entry.articleId)
                    entries[entry.articleId] = entry.copy(speed = validPlaybackSpeed(entry.speed),
                        article = metadataOnly(entry.article, entry.articleId))
                }
            }
            trim()
        }
    }

    @Synchronized
    fun resumePosition(articleId: String, durationMs: Long = 0): Long {
        val saved = entries[articleId]?.positionMs ?: return 0
        // A completed or shortened recording starts again, rather than seeking to its end.
        return resumePlaybackPosition(saved, durationMs)
    }

    @Synchronized
    fun resumeSpeed(articleId: String): Float = entries[articleId]?.speed ?: 1f

    /** Persisted insertion/update order preserves recency even for migrated entries without timestamps. */
    @Synchronized
    fun unfinished(): List<UnfinishedPlayback> = entries.values.toList().asReversed().map {
        UnfinishedPlayback(it.articleId, it.positionMs, it.durationMs, it.speed, it.article)
    }

    @Synchronized
    fun articleMetadata(articleId: String): Article? = entries[articleId]?.article

    @Synchronized
    fun attachMetadata(article: Article) {
        val entry = entries[article.id] ?: return
        val metadata = metadataOnly(article, article.id) ?: return
        if (entry.article == metadata) return
        // Metadata hydration must not reorder the user's listening history.
        entries[article.id] = entry.copy(article = metadata)
        persist()
    }

    @Synchronized
    fun save(articleId: String, positionMs: Long, durationMs: Long, completed: Boolean = false, speed: Float = 1f,
        article: Article? = null) {
        if (articleId.isBlank()) return
        val duration = durationMs.coerceAtLeast(0)
        val position = clampPosition(positionMs, duration)
        if (completed || position == 0L || duration > 0 && position >= duration) {
            if (entries.remove(articleId) != null) persist()
            return
        }
        val entry = ProgressEntry(articleId, position, duration, validPlaybackSpeed(speed),
            metadataOnly(article, articleId) ?: entries[articleId]?.article)
        if (entries[articleId] == entry) return
        entries.remove(articleId)
        entries[articleId] = entry
        trim()
        persist()
    }

    private fun trim() {
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    private fun metadataOnly(article: Article?, id: String): Article? = try {
        article?.takeIf { it.id == id && canonicalArticleUrl(id) == id && it.title.isNotBlank() }
            ?.copy(title = article.title.take(256), author = article.author?.take(256), section = article.section?.take(256),
                articleUrl = id, audio = article.audio?.copy(audioUrl = null))
    } catch (_: IllegalArgumentException) { null }

    private fun persist() {
        storage.write(json.encodeToString(ProgressSnapshot(entries = entries.values.toList())))
        mutableChanges.tryEmit(Unit)
    }
}

data class UnfinishedPlayback(val articleId: String, val positionMs: Long, val durationMs: Long,
    val speed: Float, val article: Article?)

@Serializable
private data class ProgressSnapshot(val version: Int = 3, val entries: List<ProgressEntry>)

@Serializable
private data class ProgressEntry(val articleId: String, val positionMs: Long, val durationMs: Long, val speed: Float = 1f,
    val article: Article? = null)
