package com.rombs.newyorkeraudio.playback.library

import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.data.repository.FakeNewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.Magazine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CatalogLibraryTest {
    private val date = "2026-10-12"
    private val article = TestNarration.article.copy(id = "https://www.newyorker.com/magazine/2026/10/12/story")
    private val issue = Magazine(date, "October 12, 2026", date, "https://media.newyorker.com/cover.jpg")
    private class Storage(var value: String? = null) : CatalogStorage {
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }
    private inner class Repository : NewYorkerRepository by FakeNewYorkerRepository() {
        var issueCalls = 0
        var articleCalls = 0
        var issueList = listOf(issue)
        var articleList = listOf(article)
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun getMagazines(): List<Magazine> { issueCalls++; gate?.await(); failure?.let { throw it }; return issueList }
        override suspend fun getArticles(magazineId: String): List<Article> { articleCalls++; failure?.let { throw it }; return articleList }
        override suspend fun getArticleMetadata(articleId: String): Article = articleList.first { it.id == articleId }
        override suspend fun resolveAudio(articleId: String, refresh: Boolean): Article = error("Browsing must not resolve audio")
    }

    @Test(timeout = 5000) fun `root is local and opens issues and continue listening`() = exercise { scope ->
        val repository = Repository()
        val catalog = CatalogLibrary(repository, scope, Storage())
        assertEquals(LIBRARY_ROOT_ID, libraryRoot().id)
        assertEquals(listOf(issuesFolder(), continueFolder()), catalog.children(LIBRARY_ROOT_ID, 0, Int.MAX_VALUE))
        assertEquals(0, repository.issueCalls + repository.articleCalls)
    }

    @Test(timeout = 5000) fun `issues are newest first with stable pagination`() = exercise { scope ->
        val repository = Repository().apply { issueList = listOf(issue.copy(id = "2026-10-05", date = "2026-10-05"), issue) }
        val catalog = CatalogLibrary(repository, scope, Storage())
        assertEquals(issueMediaId(date), catalog.children(LIBRARY_ISSUES_ID, 0, 1).single().id)
        assertEquals(issueMediaId("2026-10-05"), catalog.children(LIBRARY_ISSUES_ID, 1, 1).single().id)
        assertEquals(1, repository.issueCalls)
    }

    @Test(timeout = 5000) fun `unknown narration is playable and known absence is excluded`() = exercise { scope ->
        val repository = Repository().apply { articleList = listOf(article,
            article.copy(id = article.id + "-unknown", audioAvailable = null), article.copy(id = article.id + "-none", audio = null, audioAvailable = false)) }
        val catalog = CatalogLibrary(repository, scope, Storage())
        val nodes = catalog.children(issueMediaId(date), 0, 100)
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.playable && !it.browsable })
        assertEquals(article.id, nodes.first().id)
        assertEquals(1, repository.articleCalls)
        val absent = catalog.item(article.id + "-none")
        assertFalse(absent.playable)
        assertFalse(absent.browsable)
    }

    @Test(timeout = 5000) fun `overlapping requests share one backend load`() = exercise { scope ->
        val gate = CompletableDeferred<Unit>()
        val repository = Repository().apply { this.gate = gate }
        val catalog = CatalogLibrary(repository, scope, Storage())
        val first = scope.async { catalog.children(LIBRARY_ISSUES_ID, 0, 10) }
        val second = scope.async { catalog.children(LIBRARY_ISSUES_ID, 0, 10) }
        try {
            withTimeout(1000) { while (repository.issueCalls == 0) yield() }
            yield()
            assertEquals(1, repository.issueCalls)
            gate.complete(Unit)
            assertEquals(first.await(), second.await())
        } finally { gate.complete(Unit) }
    }

    @Test(timeout = 5000) fun `restart serves fresh persisted metadata without network or stream urls`() = exercise { scope ->
        val repository = Repository()
        val storage = Storage()
        val catalog = CatalogLibrary(repository, scope, storage) { 1000 }
        catalog.children(LIBRARY_ISSUES_ID, 0, 100)
        catalog.children(issueMediaId(date), 0, 100)
        assertFalse(storage.value!!.contains(article.audio!!.audioUrl!!))
        repository.failure = IllegalStateException("Offline")
        val restored = CatalogLibrary(repository, scope, storage) { 2000 }
        assertEquals(issueMediaId(date), restored.children(LIBRARY_ISSUES_ID, 0, 100).single().id)
        assertEquals(article.title, restored.item(article.id).title)
        assertEquals(article.id, restored.children(issueMediaId(date), 0, 100).single().id)
        assertEquals(1, repository.issueCalls)
        assertEquals(1, repository.articleCalls)
    }

    @Test(timeout = 5000) fun `stale metadata returns immediately and refresh notifies browsers`() = exercise { scope ->
        var now = 1000L
        val repository = Repository()
        val catalog = CatalogLibrary(repository, scope, Storage()) { now }
        catalog.children(LIBRARY_ISSUES_ID, 0, 100)
        now += 300_001
        repository.issueList = listOf(issue.copy(title = "Updated"))
        val event = scope.async(start = CoroutineStart.UNDISPATCHED) { catalog.changes.first() }
        assertEquals(issue.title, catalog.children(LIBRARY_ISSUES_ID, 0, 100).single().title)
        assertEquals(LIBRARY_ISSUES_ID, withTimeout(2000) { event.await() }.parentId)
        assertEquals("Updated", catalog.children(LIBRARY_ISSUES_ID, 0, 100).single().title)
        assertEquals(2, repository.issueCalls)
    }

    @Test(timeout = 5000) fun `outage leaves stale metadata browsable`() = exercise { scope ->
        val storage = Storage()
        val repository = Repository()
        CatalogLibrary(repository, scope, storage) { 1000 }.children(LIBRARY_ISSUES_ID, 0, 100)
        repository.failure = IllegalStateException("Offline")
        val restored = CatalogLibrary(repository, scope, storage) { 400_000 }
        assertEquals(1, restored.children(LIBRARY_ISSUES_ID, 0, 100).size)
        yield()
    }

    @Test(timeout = 5000) fun `empty library and failed cold load remain distinct`() = exercise { scope ->
        val repository = Repository().apply { failure = IllegalStateException("Unavailable") }
        val catalog = CatalogLibrary(repository, scope, Storage())
        try { catalog.children(LIBRARY_ISSUES_ID, 0, 100); fail("Expected error") } catch (_: IllegalStateException) {}
        repository.failure = null
        repository.issueList = emptyList()
        assertTrue(catalog.children(LIBRARY_ISSUES_ID, 0, 100).isEmpty())
    }

    @Test(timeout = 5000) fun `resolved absence updates the car list and persists`() = exercise { scope ->
        val storage = Storage()
        val catalog = CatalogLibrary(Repository(), scope, storage) { 1000 }
        catalog.children(issueMediaId(date), 0, 100)
        catalog.updateArticle(article.copy(audio = null, audioAvailable = false))
        assertTrue(catalog.children(issueMediaId(date), 0, 100).isEmpty())
        assertTrue(CatalogLibrary(Repository(), scope, storage) { 1000 }.children(issueMediaId(date), 0, 100).isEmpty())
    }

    @Test fun `page limits reject invalid input and avoid overflow`() {
        assertTrue(libraryPage(listOf(1, 2), Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertEquals(200, libraryPage((1..500).toList(), 0, Int.MAX_VALUE).size)
        for ((page, size) in listOf(-1 to 5, 0 to 0)) {
            try { libraryPage(listOf(1), page, size); fail("Expected invalid page") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test(timeout = 5000) fun `unknown refreshed summaries retain confirmed absence until a positive result arrives`() = exercise { scope ->
        var now = 1000L
        val repository = Repository()
        val storage = Storage()
        val catalog = CatalogLibrary(repository, scope, storage) { now }
        catalog.children(issueMediaId(date), 0, 100)
        catalog.updateArticle(article.copy(audio = null, audioAvailable = false))
        now += 300_001
        repository.articleList = listOf(article.copy(title = "Updated title", audio = null, audioAvailable = null))
        val refreshed = scope.async(start = CoroutineStart.UNDISPATCHED) { catalog.changes.first() }
        assertTrue(catalog.children(issueMediaId(date), 0, 100).isEmpty())
        assertEquals(0, withTimeout(2000) { refreshed.await() }.count)
        assertFalse(catalog.item(article.id).playable)
        assertEquals("Updated title", catalog.item(article.id).title)
        assertTrue(CatalogLibrary(repository, scope, storage) { now }.children(issueMediaId(date), 0, 100).isEmpty())
        now += 300_001
        repository.articleList = listOf(article.copy(audioAvailable = true))
        val available = scope.async(start = CoroutineStart.UNDISPATCHED) { catalog.changes.first() }
        assertTrue(catalog.children(issueMediaId(date), 0, 100).isEmpty())
        assertEquals(1, withTimeout(2000) { available.await() }.count)
        assertEquals(article.id, catalog.children(issueMediaId(date), 0, 100).single().id)
    }

    @Test(timeout = 5000) fun `malformed folders do not fetch and corrupt storage is discarded`() = exercise { scope ->
        val repository = Repository()
        val catalog = CatalogLibrary(repository, scope, Storage("bad json"))
        try { catalog.children("ny:issue:bad", 0, 100); fail("Expected invalid date") } catch (_: java.time.DateTimeException) {}
        assertEquals(0, repository.issueCalls + repository.articleCalls)
        assertEquals(1, catalog.children(LIBRARY_ISSUES_ID, 0, 100).size)
    }

    @Test(timeout = 5000) fun `article duration and fresh descriptions survive hinted refresh and restart`() = exercise { scope ->
        for (availability in listOf(true, null)) {
            var now = 1000L
            val repository = Repository()
            val storage = Storage()
            val catalog = CatalogLibrary(repository, scope, storage) { now }
            val initial = catalog.children(issueMediaId(date), 0, 100).single()
            assertEquals(article.audio!!.durationMs, initial.durationMs)
            now += 300_001
            repository.articleList = listOf(article.copy(title = "Updated title", description = "Updated description",
                audioAvailable = availability, audio = com.rombs.newyorkeraudio.domain.model.AudioInfo(null, null)))
            val event = scope.async(start = CoroutineStart.UNDISPATCHED) { catalog.changes.first() }
            catalog.children(issueMediaId(date), 0, 100)
            withTimeout(2000) { event.await() }
            val updated = catalog.children(issueMediaId(date), 0, 100).single()
            assertEquals("Updated title", updated.title)
            assertEquals("27 min · Updated description", updated.displayDescription)
            assertEquals("Listened · 27 min · Updated description", updated.copy(completed = true).displayDescription)
            assertEquals(article.audio!!.durationMs, catalog.item(article.id).durationMs)
            assertNull(catalog.cachedArticle(article.id)!!.audio!!.audioUrl)
            val restored = CatalogLibrary(repository, scope, storage) { now }
            assertEquals(updated, restored.children(issueMediaId(date), 0, 100).single())
            assertEquals(2, repository.articleCalls)
        }
    }

    @Test(timeout = 5000) fun `browse rows omit unknown durations and display short audio as one minute`() = exercise { scope ->
        val repository = Repository().apply {
            articleList = listOf(null, 0L, -1L, 1_000L).mapIndexed { index, duration ->
                article.copy(id = article.id + "-$index", description = null,
                    audio = article.audio!!.copy(durationMs = duration))
            }
        }
        val nodes = CatalogLibrary(repository, scope, Storage()).children(issueMediaId(date), 0, 100)
        assertEquals(listOf(null, null, null, "1 min"), nodes.map { it.displayDescription })
        assertEquals(listOf("Listened", "Listened", "Listened", "Listened · 1 min"),
            nodes.map { it.copy(completed = true).displayDescription })
    }

    private fun exercise(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try { block(scope) } finally { scope.cancel() }
    }
}
