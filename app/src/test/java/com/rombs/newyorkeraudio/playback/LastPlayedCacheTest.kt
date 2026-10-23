package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.data.repository.FakeNewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LastPlayedCacheTest {
    private val article = TestNarration.article.let { it.copy(id = it.articleUrl!!) }

    @Test fun `last played survives restart without retaining a streaming url`() {
        val storage = MemoryStorage()
        LastPlayedCache(storage).remember(article, 1.5f)
        val restored = LastPlayedCache(storage).current!!
        assertEquals(article.id, restored.article.id)
        assertEquals(article.title, restored.article.title)
        assertNull(restored.article.audio!!.audioUrl)
        assertEquals(1.5f, restored.speed)
        assertFalse(storage.value!!.contains(article.audio!!.audioUrl!!))
    }

    @Test fun `completed last article remains resumable at zero independently of progress`() = runBlocking {
        val storage = MemoryStorage()
        val progress = PlaybackProgressCache(MemoryStorage())
        progress.save(article.id, 10_000, article.audio!!.durationMs!!)
        progress.save(article.id, article.audio!!.durationMs!!, article.audio!!.durationMs!!, completed = true)
        LastPlayedCache(storage).remember(article, 1.5f, completed = true)
        val request = resumptionRequest(LastPlayedCache(storage).current)
        assertEquals(0L, progress.resumePosition(article.id))
        assertEquals(0L, request.startPositionMs)
        val selected = preparePlaybackSelection(request, repository(), progress, null, 0, 1f, true)
        assertEquals(0L, selected.positionMs)
        assertEquals(1.5f, selected.speed)
    }

    @Test fun `cold Play resolves a new source and restores unfinished position and speed`() = runBlocking {
        val last = LastPlayedCache(MemoryStorage()).apply { remember(article, 1.5f) }
        val progress = PlaybackProgressCache(MemoryStorage()).apply { save(article.id, 12_000, 50_000, speed = 1.5f) }
        var calls = 0
        val fresh = article.copy(audio = article.audio!!.copy(audioUrl = "https://audio.example/fresh.mp3"))
        val repository = object : NewYorkerRepository by repository() {
            override suspend fun resolveAudio(articleId: String, refresh: Boolean): Article {
                calls++
                assertEquals(article.id, articleId)
                return fresh
            }
        }
        val selected = preparePlaybackSelection(resumptionRequest(last.current), repository, progress, null, 0, 1f, true)
        assertEquals(1, calls)
        assertEquals(fresh.audio!!.audioUrl, selected.article.audio!!.audioUrl)
        assertEquals(12_000L, selected.positionMs)
        assertEquals(1.5f, selected.speed)
    }

    @Test fun `no last article reports absence rather than inventing a playlist`() {
        try { resumptionRequest(null); fail("Expected no previous article") }
        catch (_: NoLastPlayedException) {}
    }

    @Test fun `unknown version corruption and invalid identity do not restore`() {
        for (value in listOf("broken", """{"version":99,"entry":null}""")) {
            assertNull(LastPlayedCache(MemoryStorage(value)).current)
        }
        val cache = LastPlayedCache(MemoryStorage())
        cache.remember(article.copy(id = "https://evil.example/audio.mp3"), 1f)
        assertNull(cache.current)
    }

    @Test fun `one last played record follows actual selections and does not rewrite unchanged metadata`() {
        val storage = MemoryStorage()
        val cache = LastPlayedCache(storage)
        cache.remember(article, 1f)
        cache.remember(article, 1f)
        assertEquals(1, storage.writes)
        val next = article.copy(id = "https://www.newyorker.com/magazine/another-article", title = "Next")
        cache.remember(next, 2f)
        assertEquals(next.id, LastPlayedCache(storage).current!!.article.id)
    }

    @Test fun `confirmed unavailable narration updates last metadata without retaining its source`() {
        val cache = LastPlayedCache(MemoryStorage()).apply { remember(article, 1f) }
        cache.updateAvailability(article.copy(audio = null, audioAvailable = false))
        assertEquals(false, cache.current!!.article.audioAvailable)
        assertNull(cache.current!!.article.audio)
    }

    private fun repository() = object : NewYorkerRepository by FakeNewYorkerRepository() {
        override suspend fun resolveAudio(articleId: String, refresh: Boolean) = article
    }

    private class MemoryStorage(var value: String? = null) : ProgressStorage {
        var writes = 0
        override fun read() = value
        override fun write(value: String) { this.value = value; writes++ }
    }
}
