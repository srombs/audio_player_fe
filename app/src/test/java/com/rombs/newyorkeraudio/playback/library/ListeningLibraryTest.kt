package com.rombs.newyorkeraudio.playback.library

import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.data.repository.FakeNewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ListeningLibraryTest {
    private val original = TestNarration.article
    private fun article(slug: String, title: String = slug, author: String = "Writer"): Article {
        val url = "https://www.newyorker.com/magazine/2026/10/12/$slug"
        return original.copy(id = url, articleUrl = url, title = title, author = author)
    }
    private class Progress(var value: String? = null) : ProgressStorage {
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }
    private class Catalog(var value: String? = null) : CatalogStorage {
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }
    private inner class Repository : NewYorkerRepository by FakeNewYorkerRepository() {
        var issues = emptyList<Magazine>()
        var articles = emptyMap<String, List<Article>>()
        val loadedIssues = mutableListOf<String>()
        var metadataCalls = 0
        var offline = false
        override suspend fun getMagazines(): List<Magazine> { check(!offline); return issues }
        override suspend fun getArticles(magazineId: String): List<Article> {
            check(!offline); loadedIssues += magazineId; return articles[magazineId].orEmpty()
        }
        override suspend fun getArticleMetadata(articleId: String): Article {
            metadataCalls++; check(!offline); return articles.values.flatten().first { it.id == articleId }
        }
        override suspend fun resolveAudio(articleId: String, refresh: Boolean): Article = error("Metadata browsing cannot resolve audio")
    }
    private fun exercise(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try { block(scope) } finally { scope.cancel() }
    }

    @Test fun `unfinished metadata survives restart offline strips sources and keeps recency`() = exercise { scope ->
        val storage = Progress()
        val progress = PlaybackProgressCache(storage)
        val first = article("first")
        val second = article("second")
        progress.save(first.id, 100, 500, article = first)
        progress.save(second.id, 100, 500, article = second)
        progress.save(first.id, 200, 500, speed = 1.5f)
        assertFalse(storage.value!!.contains(original.audio!!.audioUrl!!))
        val restored = PlaybackProgressCache(storage)
        val repository = Repository().apply { offline = true }
        val library = ListeningLibrary(CatalogLibrary(repository, scope, Catalog()), repository, restored) { null }
        assertEquals(listOf(first.id, second.id), library.continueNodes().map { it.id })
        assertEquals(200L, library.continueNodes().first().positionMs)
        assertEquals(500L, library.continueNodes().first().durationMs)
        assertEquals("1 min", library.continueNodes().first().displayDescription)
        assertEquals(1.5f, restored.resumeSpeed(first.id))
        assertEquals(0, repository.metadataCalls)
        restored.save(first.id, 500, 500, completed = true)
        assertEquals(listOf(second.id), library.continueNodes().map { it.id })
    }

    @Test fun `v2 metadata hydration preserves speed positions and ordering`() = exercise { scope ->
        val first = article("first")
        val second = article("second")
        val storage = Progress("""{"version":2,"entries":[{"articleId":"${first.id}","positionMs":100,"durationMs":500,"speed":1.5},{"articleId":"${second.id}","positionMs":200,"durationMs":500,"speed":2.0}]}""")
        val progress = PlaybackProgressCache(storage)
        val repository = Repository().apply { articles = mapOf("issue" to listOf(first, second)) }
        val library = ListeningLibrary(CatalogLibrary(repository, scope, Catalog()), repository, progress) { null }
        assertEquals(listOf(second.id, first.id), library.unfinishedArticles().map { it.id })
        assertEquals(100L, progress.resumePosition(first.id))
        assertEquals(1.5f, progress.resumeSpeed(first.id))
        assertTrue(storage.value!!.contains("\"version\":3"))
        assertFalse(storage.value!!.contains(original.audio!!.audioUrl!!))
    }

    @Test fun `offline migration can still display a stable saved article but excludes malformed ids`() = exercise { scope ->
        val saved = article("saved-title")
        val progress = PlaybackProgressCache(Progress())
        progress.save(saved.id, 100, 500)
        progress.save("untrusted-id", 100, 500)
        val repository = Repository().apply { offline = true }
        val library = ListeningLibrary(CatalogLibrary(repository, scope, Catalog()), repository, progress) { null }
        assertEquals("saved title", library.unfinishedArticles().single().title)
    }

    @Test fun `known absence and shortened completed recordings are excluded`() = exercise { scope ->
        val absent = article("absent").copy(audio = null, audioAvailable = false)
        val short = article("short").copy(audio = original.audio!!.copy(durationMs = 100))
        val progress = PlaybackProgressCache(Progress())
        progress.save(absent.id, 100, 500, article = absent)
        progress.save(short.id, 200, 500, article = short)
        val repository = Repository()
        val library = ListeningLibrary(CatalogLibrary(repository, scope, Catalog()), repository, progress) { null }
        assertTrue(library.continueNodes().isEmpty())
    }

    @Test fun `search independently loads exactly five newest issues plus older unfinished articles`() = exercise { scope ->
        val repository = Repository()
        repository.issues = (1..6).map { day ->
            val date = "2026-10-${day.toString().padStart(2, '0')}"
            Magazine(date, date, date, null)
        }
        repository.articles = repository.issues.associate { it.id to listOf(article("story-${it.id}", "Matching ${it.id}")) }
        val old = article("older", "Matching older")
        val progress = PlaybackProgressCache(Progress()).apply { save(old.id, 100, 500, article = old) }
        val catalog = CatalogLibrary(repository, scope, Catalog())
        val library = ListeningLibrary(catalog, repository, progress) { null }
        assertEquals(6, library.search("Matching").size)
        assertEquals(repository.issues.drop(1).map { it.id }.toSet(), repository.loadedIssues.toSet())
        assertEquals(0, repository.metadataCalls)
        repository.offline = true
        assertEquals(6, library.search("Matching").size)
    }

    @Test fun `empty voice request resumes most recent unfinished then last played and reports no history`() = exercise { scope ->
        val repository = Repository().apply { offline = true }
        val progress = PlaybackProgressCache(Progress())
        val saved = article("saved")
        var last: Article? = article("last")
        val library = ListeningLibrary(CatalogLibrary(repository, scope, Catalog()), repository, progress) { last }
        assertEquals(last, library.voiceArticle(""))
        progress.save(saved.id, 100, 500, article = saved)
        assertEquals(saved.id, library.voiceArticle(" ").id)
        progress.save(saved.id, 500, 500, completed = true)
        last = null
        try { library.voiceArticle(""); fail("Expected no history") } catch (_: NoLastPlayedException) {}
        assertTrue(library.search("!!!").isEmpty())
    }

    @Test fun `offline recent search can match saved metadata but never reports an outage as no matches`() = exercise { scope ->
        val repository = Repository().apply { offline = true }
        val progress = PlaybackProgressCache(Progress())
        val saved = article("saved", "Saved story")
        progress.save(saved.id, 100, 500, article = saved)
        val library = ListeningLibrary(CatalogLibrary(repository, scope, Catalog()), repository, progress) { null }
        assertEquals(saved.id, library.voiceArticle("Saved story").id)
        try { library.search("missing"); fail("Expected outage") } catch (_: IllegalStateException) {}
        try { library.search("x".repeat(257)); fail("Expected query limit") } catch (_: VoiceQueryTooLongException) {}
    }

    @Test fun `matching normalizes accents punctuation and case and exact title resolves ambiguous author`() {
        val first = article("first", "Café's Future", "José Writer")
        val second = article("second", "Another future", "José Writer")
        assertEquals(listOf(first), searchMatches("CAFES FUTURE", listOf(second, first)))
        assertEquals(listOf(second, first), searchMatches("jose", listOf(second, first)))
        assertEquals(first, chooseVoiceMatch("Café’s Future", listOf(second, first)))
        assertEquals(second, chooseVoiceMatch("future", listOf(second)))
        try { chooseVoiceMatch("jose", listOf(first, second)); fail("Expected ambiguity") } catch (_: AmbiguousVoiceMatchException) {}
        try { chooseVoiceMatch("missing", emptyList()); fail("Expected no match") } catch (_: NoVoiceMatchException) {}
    }
}
