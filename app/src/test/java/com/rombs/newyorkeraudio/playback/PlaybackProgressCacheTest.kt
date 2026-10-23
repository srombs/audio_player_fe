package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.data.repository.TestNarration
import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressCacheTest {
    @Test fun `progress survives cache reconstruction and articles stay independent`() {
        val storage = MemoryStorage()
        val cache = PlaybackProgressCache(storage)
        cache.save("article-one", 120_000, 500_000)
        cache.save("article-two", 30_000, 400_000)
        val restored = PlaybackProgressCache(storage)
        assertEquals(120_000L, restored.resumePosition("article-one"))
        assertEquals(30_000L, restored.resumePosition("article-two"))
        assertEquals(0L, restored.resumePosition("missing"))
    }

    @Test fun `stable article url resumes even when streaming url changes`() {
        val storage = MemoryStorage()
        val original = TestNarration.article.copy(id = TestNarration.article.articleUrl!!)
        val fresh = original.copy(audio = original.audio!!.copy(audioUrl = "https://audio.example/new.mp3"))
        val cache = PlaybackProgressCache(storage)
        cache.save(original.id, 100_000, original.audio!!.durationMs!!)
        assertEquals(100_000L, cache.resumePosition(fresh.id, fresh.audio!!.durationMs!!))
        assertFalse(storage.value!!.contains(original.audio!!.audioUrl!!))
        assertFalse(storage.value!!.contains(fresh.audio!!.audioUrl!!))
    }

    @Test fun `completed narration clears saved progress`() {
        val storage = MemoryStorage()
        val cache = PlaybackProgressCache(storage)
        cache.save("article", 100, 500)
        cache.save("article", 500, 500, completed = true)
        assertEquals(0L, PlaybackProgressCache(storage).resumePosition("article"))
    }

    @Test fun `completion without known duration also clears progress`() {
        val storage = MemoryStorage()
        val cache = PlaybackProgressCache(storage)
        cache.save("article", 100, 0)
        cache.save("article", 200, 0, completed = true)
        assertEquals(0L, PlaybackProgressCache(storage).resumePosition("article"))
    }

    @Test fun `seeking backward updates progress and seeking to start clears it`() {
        val storage = MemoryStorage()
        val cache = PlaybackProgressCache(storage)
        cache.save("article", 400, 500)
        cache.save("article", 150, 500)
        assertEquals(150L, PlaybackProgressCache(storage).resumePosition("article"))
        cache.save("article", 0, 500)
        assertEquals(0L, PlaybackProgressCache(storage).resumePosition("article"))
    }

    @Test fun `a shorter new recording never resumes at or beyond its end`() {
        val cache = PlaybackProgressCache(MemoryStorage())
        cache.save("article", 400, 500)
        assertEquals(0L, cache.resumePosition("article", 300))
        assertEquals(0L, cache.resumePosition("article", 400))
        assertEquals(400L, cache.resumePosition("article", 600))
    }

    @Test fun `cache evicts oldest updated article and persists its size limit`() {
        val storage = MemoryStorage()
        val cache = PlaybackProgressCache(storage, maxEntries = 2)
        cache.save("first", 100, 500)
        cache.save("second", 100, 500)
        cache.save("first", 200, 500)
        cache.save("third", 100, 500)
        val restored = PlaybackProgressCache(storage, maxEntries = 2)
        assertEquals(200L, restored.resumePosition("first"))
        assertEquals(0L, restored.resumePosition("second"))
        assertEquals(100L, restored.resumePosition("third"))
    }

    @Test fun `unchanged samples do not rewrite storage`() {
        val storage = MemoryStorage()
        val cache = PlaybackProgressCache(storage)
        cache.save("article", 100, 500)
        cache.save("article", 100, 500)
        assertEquals(1, storage.writes)
    }

    @Test fun `corrupted or unsupported cache starts empty and can recover`() {
        for (value in listOf("broken json", """{"version":999,"entries":[]}""", """{"entries":null}""")) {
            val storage = MemoryStorage(value)
            val cache = PlaybackProgressCache(storage)
            assertEquals(0L, cache.resumePosition("article"))
            cache.save("article", 100, 500)
            assertEquals(100L, PlaybackProgressCache(storage).resumePosition("article"))
        }
    }

    @Test fun `v1 entries migrate without losing positions and acquire a default speed`() {
        for (prefix in listOf("", "\"version\":1,")) {
            val storage = MemoryStorage("""{${prefix}"entries":[{"articleId":"old","positionMs":12000,"durationMs":50000}]}""")
            val cache = PlaybackProgressCache(storage)
            assertEquals(12_000L, cache.resumePosition("old"))
            assertEquals(1f, cache.resumeSpeed("old"))
            cache.save("old", 13_000, 50_000, speed = 1.5f)
            assertTrue(storage.value!!.contains("\"version\":3"))
            val restored = PlaybackProgressCache(storage)
            assertEquals(13_000L, restored.resumePosition("old"))
            assertEquals(1.5f, restored.resumeSpeed("old"))
        }
    }

    @Test fun `speed changes persist without requiring position to advance`() {
        val storage = MemoryStorage()
        val cache = PlaybackProgressCache(storage)
        cache.save("article", 12_000, 50_000, speed = 1f)
        cache.save("article", 12_000, 50_000, speed = 2f)
        assertEquals(2f, PlaybackProgressCache(storage).resumeSpeed("article"))
    }

    @Test fun `invalid speeds become a safe default`() {
        val cache = PlaybackProgressCache(MemoryStorage())
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 10f)) {
            cache.save("article", 100, 500, speed = speed)
            assertEquals(1f, cache.resumeSpeed("article"))
        }
    }

    @Test fun `negative positions and positions at duration cannot become resume points`() {
        val cache = PlaybackProgressCache(MemoryStorage())
        cache.save("negative", -10, 500)
        cache.save("end", 500, 500)
        cache.save("beyond", 600, 500)
        assertEquals(0L, cache.resumePosition("negative"))
        assertEquals(0L, cache.resumePosition("end"))
        assertEquals(0L, cache.resumePosition("beyond"))
    }

    @Test fun `saved progress is displayed before playback without changing active session`() {
        val article = TestNarration.article
        val empty = PlaybackState().forArticle(article, savedPositionMs = 120_000)
        assertEquals(120_000L, empty.positionMs)
        assertFalse(empty.playWhenReady)
        assertFalse(empty.canSeek)
        val active = PlaybackState(mediaId = article.id, positionMs = 150_000, speed = 1.5f)
        assertEquals(active, active.forArticle(article, savedPositionMs = 120_000))
    }

    private class MemoryStorage(var value: String? = null) : ProgressStorage {
        var writes = 0
        override fun read() = value
        override fun write(value: String) { this.value = value; writes++ }
    }
}
