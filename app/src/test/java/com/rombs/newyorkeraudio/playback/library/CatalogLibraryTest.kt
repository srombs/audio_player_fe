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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
        var articleGate: CompletableDeferred<Unit>? = null
        val requestedDates = mutableListOf<String>()
        override suspend fun getMagazines(): List<Magazine> { issueCalls++; gate?.await(); failure?.let { throw it }; return issueList }
        override suspend fun getArticles(magazineId: String): List<Article> {
            articleCalls++; requestedDates.add(magazineId)
            val response = articleList
            articleGate?.await()
            failure?.let { throw it }; return response
        }
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

    @Test(timeout = 5000) fun `issue progress distinguishes unloaded membership and counts distinct eligible articles`() = exercise { scope ->
        val repository = Repository().apply {
            articleList = listOf(article, article,
                article.copy(id = article.id + "-unknown", audioAvailable = null),
                article.copy(id = article.id + "-none", audio = null, audioAvailable = false))
        }
        val catalog = CatalogLibrary(repository, scope, Storage())
        assertEquals("Progress unavailable", catalog.item(issueMediaId(date)).displayDescription)
        catalog.children(issueMediaId(date), 0, 1)
        val node = catalog.item(issueMediaId(date))
        assertEquals(issue.title, node.title)
        assertEquals("0/2 listened", node.displayDescription)
    }

    @Test(timeout = 5000) fun `history snapshots update every affected issue and replay keeps the same count`() = exercise { scope ->
        val otherDate = "2026-10-05"
        val second = article.copy(id = article.id + "-second")
        val repository = Repository().apply {
            issueList = listOf(issue, issue.copy(id = otherDate, date = otherDate))
            articleList = listOf(article, second)
        }
        val catalog = CatalogLibrary(repository, scope, Storage())
        catalog.children(LIBRARY_ISSUES_ID, 0, 100)
        catalog.children(issueMediaId(date), 0, 100)
        catalog.children(issueMediaId(otherDate), 0, 100)
        val changes = mutableListOf<CatalogChange>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { catalog.changes.collect { changes.add(it) } }
        catalog.listeningHistoryChanged(setOf(article.id, second.id, article.id + "-unrelated"))
        yield()
        assertEquals(setOf(LIBRARY_ISSUES_ID, issueMediaId(date), issueMediaId(otherDate)), changes.map { it.parentId }.toSet())
        assertEquals("2/2 listened", catalog.item(issueMediaId(date)).displayDescription)
        assertEquals("2/2 listened", catalog.item(issueMediaId(otherDate)).displayDescription)
        changes.clear()
        catalog.listeningHistoryChanged(setOf(second.id, article.id, article.id + "-unrelated"))
        yield()
        assertTrue(changes.isEmpty())
        assertEquals("${issue.title} · 2/2 listened", catalog.item(issueMediaId(date)).displayTitle)
        collector.cancel()
    }

    @Test(timeout = 10000) fun `whole issue progress survives display caps eviction restart and later narration changes`() = exercise { scope ->
        val repository = Repository().apply {
            issueList = (0L..24L).map { offset ->
                val day = java.time.LocalDate.parse(date).minusDays(offset).toString()
                issue.copy(id = day, date = day)
            }
            articleList = (0..124).map { index -> article.copy(id = article.id + "-$index", audioAvailable = if (index == 0) false else null) }
        }
        val completed = repository.articleList.last()
        val storage = Storage()
        val catalog = CatalogLibrary(repository, scope, storage, initialListenedIds = setOf(completed.id)) { 1000 }
        catalog.children(LIBRARY_ISSUES_ID, 0, 1)
        for (heading in repository.issueList) catalog.children(issueMediaId(heading.id), 0, 100)
        assertNull(catalog.cachedArticle(completed.id))
        assertEquals("1/124 listened", catalog.item(issueMediaId(date)).displayDescription)
        assertFalse(storage.value!!.contains(article.audio!!.audioUrl!!))
        repository.failure = IllegalStateException("Offline")
        val restored = CatalogLibrary(repository, scope, storage, initialListenedIds = setOf(completed.id)) { 2000 }
        assertEquals("1/124 listened", restored.item(issueMediaId(date)).displayDescription)
        restored.updateArticle(completed.copy(audio = null, audioAvailable = false))
        assertEquals("0/123 listened", restored.item(issueMediaId(date)).displayDescription)
        val again = CatalogLibrary(repository, scope, storage, initialListenedIds = setOf(completed.id)) { 2000 }
        assertEquals("0/123 listened", again.item(issueMediaId(date)).displayDescription)
        repository.failure = null
        val refreshed = CatalogLibrary(repository, scope, storage, initialListenedIds = setOf(completed.id)) { 400_000 }
        refreshed.children(issueMediaId(date), 0, 100)
        assertEquals("0/123 listened", refreshed.item(issueMediaId(date)).displayDescription)
    }

    @Test(timeout = 10000) fun `old and corrupt membership keep catalog and listened history while rebuilding exact totals`() = exercise { scope ->
        val repository = Repository()
        val original = Storage()
        val catalog = CatalogLibrary(repository, scope, original) { 1000 }
        catalog.children(LIBRARY_ISSUES_ID, 0, 100)
        catalog.children(issueMediaId(date), 0, 100)
        val saved = Json.parseToJsonElement(original.value!!).jsonObject
        val fixtures = listOf(
            JsonObject((saved - "issueMembership") + ("version" to JsonPrimitive(1))),
            JsonObject(saved + ("issueMembership" to JsonPrimitive("corrupt index"))),
            JsonObject(saved + ("issueMembership" to JsonArray(listOf(JsonObject(mapOf(
                "date" to JsonPrimitive(date), "updatedAt" to JsonPrimitive(1000),
                "articles" to JsonObject(mapOf("invalid-id" to JsonPrimitive(true)))))))))
        )
        for (fixture in fixtures) {
            val storage = Storage(fixture.toString())
            val restored = CatalogLibrary(repository, scope, storage, initialListenedIds = setOf(article.id)) { 2000 }
            assertEquals("Progress unavailable", restored.item(issueMediaId(date)).displayDescription)
            assertEquals(article.title, restored.item(article.id).title)
            val rebuilt = scope.async(start = CoroutineStart.UNDISPATCHED) {
                restored.changes.first { it.parentId == LIBRARY_ISSUES_ID }
            }
            assertEquals(article.id, restored.children(issueMediaId(date), 0, 100).single().id)
            withTimeout(2000) { rebuilt.await() }
            assertEquals("1/1 listened", restored.item(issueMediaId(date)).displayDescription)
        }
    }

    @Test(timeout = 5000) fun `issue page returns before bounded background hydration and refreshes magazine summaries`() = exercise { scope ->
        val gate = CompletableDeferred<Unit>()
        val repository = Repository().apply {
            articleGate = gate
            issueList = (0L..199L).map { offset ->
                val day = java.time.LocalDate.parse(date).minusDays(offset).toString()
                issue.copy(id = day, date = day)
            }
        }
        val catalog = CatalogLibrary(repository, scope, Storage())
        val page = catalog.children(LIBRARY_ISSUES_ID, 5, 2)
        assertTrue(page.all { it.displayDescription == "Progress unavailable" })
        try {
            withTimeout(1000) { while (repository.articleCalls < 2) yield() }
            repeat(5) { yield() }
            assertEquals(2, repository.articleCalls)
            assertTrue(repository.requestedDates.all { it in repository.issueList.take(5).map { issue -> issue.id } })
            val hydrated = scope.async(start = CoroutineStart.UNDISPATCHED) {
                catalog.changes.first { it.parentId == LIBRARY_ISSUES_ID &&
                    catalog.item(page.last().id).issueProgress != null }
            }
            gate.complete(Unit)
            withTimeout(2000) { hydrated.await() }
            assertEquals("0/1 listened", catalog.item(page.last().id).displayDescription)
            assertEquals(7, repository.articleCalls)
        } finally { gate.complete(Unit) }
    }

    @Test(timeout = 5000) fun `late browse response cannot undo a newer narration resolution`() = exercise { scope ->
        var now = 1000L
        val repository = Repository()
        val catalog = CatalogLibrary(repository, scope, Storage()) { now }
        catalog.children(LIBRARY_ISSUES_ID, 0, 100)
        catalog.children(issueMediaId(date), 0, 100)
        now += 300_001
        val gate = CompletableDeferred<Unit>()
        repository.articleGate = gate
        repository.articleList = listOf(article.copy(title = "Updated title", audioAvailable = true))
        try {
            catalog.children(issueMediaId(date), 0, 100)
            withTimeout(1000) { while (repository.articleCalls < 2) yield() }
            catalog.updateArticle(article.copy(audio = null, audioAvailable = false))
            val refreshed = scope.async(start = CoroutineStart.UNDISPATCHED) {
                catalog.changes.first { it.parentId == issueMediaId(date) }
            }
            gate.complete(Unit)
            withTimeout(2000) { refreshed.await() }
            assertTrue(catalog.children(issueMediaId(date), 0, 100).isEmpty())
            assertEquals("Updated title", catalog.item(article.id).title)
            assertEquals("0/0 listened", catalog.item(issueMediaId(date)).displayDescription)
        } finally { gate.complete(Unit) }
    }

    @Test(timeout = 15000) fun `all 200 retained headings survive restart and receive completion notifications`() = exercise { scope ->
        val repository = Repository().apply {
            issueList = (0L..199L).map { offset ->
                val day = java.time.LocalDate.parse(date).minusDays(offset).toString()
                issue.copy(id = day, date = day)
            }
        }
        val storage = Storage()
        val catalog = CatalogLibrary(repository, scope, storage) { 1000 }
        catalog.children(LIBRARY_ISSUES_ID, 0, 1)
        for (heading in repository.issueList) catalog.children(issueMediaId(heading.id), 0, 100)
        repository.failure = IllegalStateException("Offline")
        val restored = CatalogLibrary(repository, scope, storage) { 2000 }
        assertTrue(restored.children(LIBRARY_ISSUES_ID, 0, Int.MAX_VALUE).all { it.displayDescription == "0/1 listened" })
        val changes = mutableListOf<CatalogChange>()
        val received = CompletableDeferred<Unit>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            restored.changes.collect {
                yield()
                changes.add(it)
                // A notification consumer can read the catalog without blocking its writer.
                if (it.parentId != LIBRARY_ISSUES_ID) assertEquals("1/1 listened", restored.item(it.parentId).displayDescription)
                if (changes.size == 201) received.complete(Unit)
            }
        }
        restored.listeningHistoryChanged(setOf(article.id))
        withTimeout(3000) { received.await() }
        assertEquals(200, changes.count { it.parentId != LIBRARY_ISSUES_ID })
        assertEquals(200, changes.single { it.parentId == LIBRARY_ISSUES_ID }.count)
        collector.cancel()
    }

    private fun exercise(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try { block(scope) } finally { scope.cancel() }
    }
}
