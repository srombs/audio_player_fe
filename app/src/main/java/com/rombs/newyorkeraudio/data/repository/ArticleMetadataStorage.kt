package com.rombs.newyorkeraudio.data.repository

import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.AudioInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface ArticleMetadataStorage {
    fun read(): String?
    fun write(value: String)
}

/** Retains phone route metadata across process death without retaining signed audio URLs. */
internal class ArticleMetadataCache(private val storage: ArticleMetadataStorage?, private val maxEntries: Int = 200) {
    private val json = Json { ignoreUnknownKeys = true }
    private val entries = linkedMapOf<String, Article>()

    init {
        require(maxEntries > 0)
        try {
            val saved = storage?.read()?.let { json.decodeFromString<MetadataSnapshot>(it) }
            if (saved?.version == 1) saved.articles.takeLast(maxEntries).forEach { item ->
                val id = canonicalArticleUrl(item.id)
                require(item.title.isNotBlank())
                require(item.durationMs == null || item.durationMs > 0)
                entries[id] = Article(id, item.title, item.author, id, item.imageUrl, item.section,
                    if (item.audioAvailable == false) null else AudioInfo(item.durationMs, null), item.audioAvailable,
                    description = item.description)
            }
        } catch (_: Exception) {
            entries.clear()
        }
    }

    fun get(id: String): Article? = entries[id]

    fun putAll(articles: List<Article>) {
        articles.forEach { article ->
            entries.remove(article.id)
            entries[article.id] = article.copy(audio = article.audio?.copy(audioUrl = null))
        }
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        storage?.write(json.encodeToString(MetadataSnapshot(articles = entries.values.map {
            CachedArticle(it.id, it.title, it.author, it.imageUrl, it.section, it.audioAvailable, it.audio?.durationMs, it.description)
        })))
    }
}

@Serializable
private data class MetadataSnapshot(val version: Int = 1, val articles: List<CachedArticle>)

@Serializable
private data class CachedArticle(
    val id: String,
    val title: String,
    val author: String?,
    val imageUrl: String?,
    val section: String?,
    val audioAvailable: Boolean?,
    val durationMs: Long?,
    val description: String? = null,
)
