package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.data.repository.TestNarration
import org.junit.Assert.*
import org.junit.Test

class ListeningHistoryCacheTest {
    private val article = TestNarration.article.copy(id = TestNarration.article.articleUrl!!,
        description = "A saved description.")
    private class Storage(var value: String? = null) : ProgressStorage {
        var writes = 0
        override fun read() = value
        override fun write(value: String) { this.value = value; writes++ }
    }

    @Test fun `completion survives restart with descriptions and no signed sources`() {
        val storage = Storage()
        ListeningHistoryCache(storage) { 100 }.markCompleted(article)
        val restored = ListeningHistoryCache(storage)
        assertTrue(restored.isListened(article.id))
        assertEquals(100L, restored.entries.value.single().completedAt)
        assertEquals(article.description, restored.article(article.id)!!.description)
        assertNull(restored.article(article.id)!!.audio!!.audioUrl)
        assertFalse(storage.value!!.contains(article.audio!!.audioUrl!!))
    }

    @Test fun `repeated ended samples do not rewrite completion while a finished replay updates recency`() {
        var now = 100L
        val storage = Storage()
        val history = ListeningHistoryCache(storage) { now }
        val recorder = CompletionRecorder(history)
        recorder.record(article, false)
        assertTrue(history.entries.value.isEmpty())
        recorder.record(article, true)
        now = 200
        recorder.record(article, true)
        assertEquals(1, storage.writes)
        assertEquals(100L, history.entries.value.single().completedAt)
        val other = article.copy(id = article.id + "-other", title = "Other")
        recorder.record(other, false)
        recorder.record(other, true)
        assertEquals(listOf(other.id, article.id), history.entries.value.map { it.article.id })
        recorder.record(article, false)
        assertTrue(history.isListened(article.id))
        now = 300
        recorder.record(article, true)
        assertEquals(listOf(article.id, other.id), history.entries.value.map { it.article.id })
        assertEquals(2, history.entries.value.size)
        assertEquals(300L, ListeningHistoryCache(storage).entries.value.first().completedAt)
    }

    @Test fun `history remains after unfinished progress is removed and subsequent articles start`() {
        val history = ListeningHistoryCache(Storage())
        val progress = PlaybackProgressCache(Storage())
        progress.save(article.id, 100, 500, article = article)
        history.markCompleted(article)
        progress.save(article.id, 500, 500, completed = true)
        progress.save(article.id + "-next", 100, 500)
        assertEquals(0L, progress.resumePosition(article.id))
        assertTrue(history.isListened(article.id))
    }

    @Test fun `long audio becomes listened at exactly one minute remaining`() {
        val duration = 30 * 60_000L
        assertFalse(qualifiesAsListened(duration - 60_001, duration, false))
        assertTrue(qualifiesAsListened(duration - 60_000, duration, false))
        assertTrue(qualifiesAsListened(duration, duration, false))
    }

    @Test fun `short audio requires 95 percent progress rather than one minute remaining`() {
        val duration = 5 * 60_000L
        assertFalse(qualifiesAsListened(0, duration, false))
        assertFalse(qualifiesAsListened(duration - 60_000, duration, false))
        assertFalse(qualifiesAsListened(duration - 15_001, duration, false))
        assertTrue(qualifiesAsListened(duration - 15_000, duration, false))
        assertFalse(qualifiesAsListened(0, 30_000, false))
        assertTrue(qualifiesAsListened(28_500, 30_000, false))
    }

    @Test fun `unknown durations and invalid positions need an actual ended event`() {
        assertFalse(qualifiesAsListened(1_000_000, 0, false))
        assertFalse(qualifiesAsListened(1_000_000, -1, false))
        assertFalse(qualifiesAsListened(-1, 300_000, false))
        assertTrue(qualifiesAsListened(1_000_000, 0, true))
        assertFalse(qualifiesAsListened(0, 1, false))
        assertTrue(qualifiesAsListened(1, 1, false))
    }

    @Test fun `near-end marks history once but retains progress until the actual end`() {
        var now = 100L
        val storage = Storage()
        val history = ListeningHistoryCache(storage) { now }
        val recorder = CompletionRecorder(history)
        val progress = PlaybackProgressCache(Storage())
        val duration = 1_800_000L
        val position = duration - 60_000
        progress.save(article.id, position, duration, article = article)
        recorder.record(article, false, position, duration)
        assertTrue(history.isListened(article.id))
        assertEquals(position, progress.resumePosition(article.id))
        assertEquals(1, progress.unfinished().size)
        now = 200
        recorder.record(article, false, position + 5_000, duration)
        recorder.record(article, true, duration, duration)
        assertEquals(1, storage.writes)
        assertEquals(100L, history.entries.value.single().completedAt)
        progress.save(article.id, duration, duration, completed = true)
        assertTrue(progress.unfinished().isEmpty())
        recorder.record(article, false, 0, duration)
        now = 300
        recorder.record(article, false, position, duration)
        assertEquals(300L, history.entries.value.single().completedAt)
        assertEquals(2, storage.writes)
    }

    @Test fun `corrupt history is recoverable and invalid article identity is not persisted`() {
        for (value in listOf("broken", """{"version":99,"entries":[]}""")) {
            val storage = Storage(value)
            val history = ListeningHistoryCache(storage)
            assertTrue(history.entries.value.isEmpty())
            history.markCompleted(article.copy(id = "invalid"))
            assertEquals(0, storage.writes)
            history.markCompleted(article)
            assertTrue(ListeningHistoryCache(storage).isListened(article.id))
        }
    }

    @Test fun `filters retain catalog order and distinguish completed from unfinished or never played`() {
        val unfinished = article.copy(id = article.id + "-unfinished")
        val neverPlayed = article.copy(id = article.id + "-never")
        val articles = listOf(unfinished, article, neverPlayed)
        val listened = setOf(article.id)
        assertEquals(articles, filterListenedArticles(articles, listened, ArticleListenFilter.ALL))
        assertEquals(listOf(article), filterListenedArticles(articles, listened, ArticleListenFilter.LISTENED))
        assertEquals(listOf(unfinished, neverPlayed), filterListenedArticles(articles, listened, ArticleListenFilter.UNLISTENED))
        assertTrue(filterListenedArticles(articles, emptySet(), ArticleListenFilter.LISTENED).isEmpty())
    }
}
