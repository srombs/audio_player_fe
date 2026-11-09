package com.rombs.newyorkeraudio.data.repository

import org.junit.Assert.*
import org.junit.Test

class ArticleMetadataCacheTest {
    private val article = TestNarration.article.copy(id = "https://www.newyorker.com/magazine/2026/10/19/story",
        description = "An article description retained across restarts.")
    private class Storage(var value: String? = null) : ArticleMetadataStorage {
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    @Test fun `cold metadata retains display information and duration but never a stream url`() {
        val storage = Storage()
        ArticleMetadataCache(storage).putAll(listOf(article))
        assertFalse(storage.value!!.contains(article.audio!!.audioUrl!!))
        val restored = ArticleMetadataCache(storage).get(article.id)!!
        assertEquals(article.title, restored.title)
        assertEquals(article.description, restored.description)
        assertEquals(article.audio!!.durationMs, restored.audio!!.durationMs)
        assertNull(restored.audio!!.audioUrl)
    }

    @Test fun `known absence and unknown availability survive process recreation`() {
        val storage = Storage()
        val unknown = article.copy(id = article.id + "-unknown", audioAvailable = null)
        val absent = article.copy(audio = null, audioAvailable = false)
        ArticleMetadataCache(storage).putAll(listOf(unknown, absent))
        val cache = ArticleMetadataCache(storage)
        assertNull(cache.get(unknown.id)!!.audioAvailable)
        assertNotNull(cache.get(unknown.id)!!.audio)
        assertEquals(false, cache.get(absent.id)!!.audioAvailable)
        assertNull(cache.get(absent.id)!!.audio)
    }

    @Test fun `bounded cache evicts oldest and refresh keeps recent metadata`() {
        val storage = Storage()
        val cache = ArticleMetadataCache(storage, maxEntries = 2)
        val second = article.copy(id = article.id + "-second")
        val third = article.copy(id = article.id + "-third")
        cache.putAll(listOf(article, second))
        cache.putAll(listOf(article.copy(title = "Updated"), third))
        assertNull(cache.get(second.id))
        assertEquals("Updated", ArticleMetadataCache(storage, maxEntries = 2).get(article.id)!!.title)
    }

    @Test fun `corrupt and unsupported snapshots are ignored`() {
        for (value in listOf("bad json", """{"version":2,"articles":[]}""")) {
            assertNull(ArticleMetadataCache(Storage(value)).get(article.id))
        }
    }

    @Test fun `new resolved durations replace old ones and confirmed absence clears narration`() {
        val cache = ArticleMetadataCache(null)
        cache.putAll(listOf(article))
        cache.putAll(listOf(article.copy(audio = article.audio!!.copy(durationMs = 60_000))))
        assertEquals(60_000L, cache.get(article.id)!!.audio!!.durationMs)
        cache.putAll(listOf(article.copy(audio = null, audioAvailable = false)))
        assertNull(cache.get(article.id)!!.audio)
    }
}
