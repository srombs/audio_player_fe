package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.data.repository.canonicalArticleUrl
import com.rombs.newyorkeraudio.domain.model.Article
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class LastPlayedEntry(val article: Article, val speed: Float = 1f, val completed: Boolean = false)

/** One metadata record, independent of unfinished progress. Expiring streaming URLs are never stored. */
internal class LastPlayedCache(private val storage: ProgressStorage) {
    private val json = Json { ignoreUnknownKeys = true }
    var current: LastPlayedEntry? = restore()
        private set

    fun remember(article: Article, speed: Float, completed: Boolean = false) {
        val metadata = metadataOnly(article) ?: return
        val next = LastPlayedEntry(metadata, validPlaybackSpeed(speed), completed)
        if (next == current) return
        current = next
        persist()
    }

    fun updateAvailability(article: Article) {
        val previous = current?.takeIf { it.article.id == article.id } ?: return
        remember(article, previous.speed, previous.completed)
    }

    private fun metadataOnly(article: Article): Article? = try {
        if (canonicalArticleUrl(article.id) != article.id || article.title.isBlank()) null
        else article.copy(title = article.title.take(256), author = article.author?.take(256),
            section = article.section?.take(256), articleUrl = article.id, audio = article.audio?.copy(audioUrl = null))
    } catch (_: IllegalArgumentException) { null }

    private fun restore(): LastPlayedEntry? = try {
        val snapshot = storage.read()?.let { json.decodeFromString<LastPlayedSnapshot>(it) }
        snapshot?.takeIf { it.version == 1 }?.entry?.let { entry ->
            metadataOnly(entry.article)?.let { entry.copy(article = it, speed = validPlaybackSpeed(entry.speed)) }
        }
    } catch (_: Exception) { null }

    private fun persist() = storage.write(json.encodeToString(LastPlayedSnapshot(entry = current)))
}

@Serializable
private data class LastPlayedSnapshot(val version: Int = 1, val entry: LastPlayedEntry?)

internal class NoLastPlayedException : Exception("There is no recently played article.")

internal fun resumptionRequest(entry: LastPlayedEntry?): PlaybackSelectionRequest {
    val previous = entry ?: throw NoLastPlayedException()
    return PlaybackSelectionRequest(previous.article.id, startPositionMs = if (previous.completed) 0 else null,
        resumeSpeed = previous.speed)
}
