package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.data.repository.FakeNewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionTest {
    private val article = TestNarration.article
    private val cache = PlaybackProgressCache(object : ProgressStorage {
        override fun read(): String? = null
        override fun write(value: String) {}
    })
    private val requests = mutableListOf<Pair<String, Boolean>>()
    private var response = article
    private val repository = object : NewYorkerRepository by FakeNewYorkerRepository() {
        override suspend fun resolveAudio(articleId: String, refresh: Boolean): Article {
            requests += articleId to refresh
            return response
        }
    }

    @Test fun `new selection resolves once and restores service progress`() = runBlocking {
        cache.save(article.id, 12_000, article.audio!!.durationMs!!)
        val selected = prepare(PlaybackSelectionRequest(article.id))
        assertEquals(listOf(article.id to false), requests)
        assertEquals(12_000L, selected.positionMs)
        assertEquals(1f, selected.speed)
        assertFalse(selected.reuseActive)
    }

    @Test fun `explicit zero overrides saved progress`() = runBlocking {
        cache.save(article.id, 12_000, article.audio!!.durationMs!!)
        assertEquals(0L, prepare(PlaybackSelectionRequest(article.id, startPositionMs = 0)).positionMs)
    }

    @Test fun `healthy current article reuses source position and speed without network`() = runBlocking {
        val selected = prepare(PlaybackSelectionRequest(article.id), active = article)
        assertTrue(requests.isEmpty())
        assertTrue(selected.reuseActive)
        assertEquals(15_000L, selected.positionMs)
        assertEquals(1.5f, selected.speed)
    }

    @Test fun `failed source retry bypasses backend cache and keeps progress and speed`() = runBlocking {
        response = article.copy(audio = article.audio!!.copy(audioUrl = "https://audio.example/fresh.mp3"))
        val selected = prepare(PlaybackSelectionRequest(article.id, refresh = true), active = article, healthy = false)
        assertEquals(listOf(article.id to true), requests)
        assertEquals(response, selected.article)
        assertEquals(15_000L, selected.positionMs)
        assertEquals(1.5f, selected.speed)
        assertFalse(selected.reuseActive)
    }

    @Test fun `explicit position takes precedence over active progress`() = runBlocking {
        assertEquals(2_000L, prepare(PlaybackSelectionRequest(article.id, startPositionMs = 2_000), active = article).positionMs)
    }

    @Test fun `shortened recording discards an out of range saved position`() = runBlocking {
        cache.save(article.id, 90_000, 120_000)
        response = article.copy(audio = article.audio!!.copy(durationMs = 60_000))
        assertEquals(0L, prepare(PlaybackSelectionRequest(article.id)).positionMs)
    }

    @Test fun `no narration cannot replace a playable selection`() = runBlocking {
        response = article.copy(audio = null, audioAvailable = false)
        try {
            prepare(PlaybackSelectionRequest(article.id))
            fail("Expected absent narration")
        } catch (_: NarrationUnavailableException) {}
    }

    @Test fun `invalid explicit position is rejected before resolution`() = runBlocking {
        try {
            prepare(PlaybackSelectionRequest(article.id, startPositionMs = -1))
            fail("Expected invalid position")
        } catch (_: IllegalArgumentException) {}
        assertTrue(requests.isEmpty())
    }

    @Test fun `resolver response for another article is rejected`() = runBlocking {
        response = article.copy(id = "another-article")
        try { prepare(PlaybackSelectionRequest(article.id)); fail("Expected mismatched identity") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun `switching articles restores the saved speed as well as position`() = runBlocking {
        cache.save(article.id, 12_000, article.audio!!.durationMs!!, speed = 1.5f)
        val selected = prepare(PlaybackSelectionRequest(article.id))
        assertEquals(12_000L, selected.positionMs)
        assertEquals(1.5f, selected.speed)
    }

    @Test fun `shortened retry starts at zero while preserving active speed`() = runBlocking {
        response = article.copy(audio = article.audio!!.copy(durationMs = 10_000))
        val selected = prepare(PlaybackSelectionRequest(article.id, refresh = true), active = article, healthy = false)
        assertEquals(0L, selected.positionMs)
        assertEquals(1.5f, selected.speed)
    }

    private suspend fun prepare(request: PlaybackSelectionRequest, active: Article? = null, healthy: Boolean = true) =
        preparePlaybackSelection(request, repository, cache, active, 15_000, 1.5f, healthy)
}
