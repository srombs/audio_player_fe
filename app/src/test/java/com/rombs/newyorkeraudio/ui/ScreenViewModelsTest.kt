package com.rombs.newyorkeraudio.ui

import com.rombs.newyorkeraudio.data.repository.FakeNewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.ui.articles.ArticleListViewModel
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.magazines.MagazineGridViewModel
import com.rombs.newyorkeraudio.ui.player.PlayerViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScreenViewModelsTest {
    private val catalog = FakeNewYorkerRepository()

    @Test(timeout = 3000)
    fun issuesStayLoadingUntilTheRepositoryReturns() = exercise { scope ->
        val result = CompletableDeferred<List<Magazine>>()
        val repository = ControlledRepository().apply { magazines = { result.await() } }
        val model = MagazineGridViewModel(repository, scope)
        yield()
        assertEquals(LoadState.Loading, model.state.value)
        val issues = catalog.getMagazines()
        result.complete(issues)
        yield()
        assertEquals(LoadState.Content(issues), model.state.value)
    }

    @Test(timeout = 3000)
    fun anEmptyIssueResponseHasItsOwnState() = exercise { scope ->
        val repository = ControlledRepository().apply { magazines = { emptyList() } }
        val model = MagazineGridViewModel(repository, scope)
        yield()
        assertEquals(LoadState.Empty(emptyList<Magazine>()), model.state.value)
    }

    @Test(timeout = 3000)
    fun failedIssuesCanBeRetriedSuccessfully() = exercise { scope ->
        var fail = true
        val repository = ControlledRepository().apply {
            magazines = { if (fail) throw IOException("unavailable") else catalog.getMagazines() }
        }
        val model = MagazineGridViewModel(repository, scope)
        yield()
        assertEquals(LoadState.Error, model.state.value)
        fail = false
        model.refresh()
        assertEquals(LoadState.Loading, model.state.value)
        yield()
        assertEquals(LoadState.Content(catalog.getMagazines()), model.state.value)
    }

    @Test(timeout = 3000)
    fun articleLoadingUsesTheSelectedIssueId() = exercise { scope ->
        val result = CompletableDeferred<List<Article>>()
        var requestedId: String? = null
        val repository = ControlledRepository().apply {
            articles = { id -> requestedId = id; result.await() }
        }
        val model = ArticleListViewModel(repository, "2026-10-05", scope)
        yield()
        assertEquals("2026-10-05", requestedId)
        assertEquals(LoadState.Loading, model.state.value)
        result.complete(catalog.getArticles("2026-10-05"))
        yield()
        val content = (model.state.value as LoadState.Content).value
        assertEquals(catalog.getMagazine("2026-10-05"), content.magazine)
        assertEquals(catalog.getArticles("2026-10-05"), content.articles)
    }

    @Test(timeout = 3000)
    fun anEmptyArticleListRetainsItsIssueHeader() = exercise { scope ->
        val repository = ControlledRepository().apply { articles = { emptyList() } }
        val model = ArticleListViewModel(repository, "2026-10-12", scope)
        yield()
        val empty = (model.state.value as LoadState.Empty).value
        assertEquals("October 12, 2026", empty.magazine.title)
        assertTrue(empty.articles.isEmpty())
    }

    @Test(timeout = 3000)
    fun failedArticlesCanBeRetriedSuccessfully() = exercise { scope ->
        var fail = true
        val repository = ControlledRepository().apply {
            articles = { id -> if (fail) throw IOException("unavailable") else catalog.getArticles(id) }
        }
        val model = ArticleListViewModel(repository, "2026-10-12", scope)
        yield()
        assertEquals(LoadState.Error, model.state.value)
        fail = false
        model.refresh()
        assertEquals(LoadState.Loading, model.state.value)
        yield()
        assertTrue(model.state.value is LoadState.Content)
    }

    @Test(timeout = 3000)
    fun anUnknownIssueIsAnErrorRatherThanAnEmptyList() = exercise { scope ->
        val model = ArticleListViewModel(catalog, "missing", scope)
        yield()
        assertEquals(LoadState.Error, model.state.value)
    }

    @Test(timeout = 3000)
    fun playerLoadingUsesTheSelectedArticleId() = exercise { scope ->
        val result = CompletableDeferred<Article>()
        var requestedId: String? = null
        val repository = ControlledRepository().apply {
            article = { id -> requestedId = id; result.await() }
        }
        val model = PlayerViewModel(repository, "2026-10-05-river", scope)
        yield()
        assertEquals("2026-10-05-river", requestedId)
        assertEquals(LoadState.Loading, model.state.value)
        val article = catalog.getArticle("2026-10-05-river")
        result.complete(article)
        yield()
        assertEquals(LoadState.Content(article), model.state.value)
    }

    @Test(timeout = 3000)
    fun failedPlayerMetadataCanBeRetriedSuccessfully() = exercise { scope ->
        var fail = true
        val repository = ControlledRepository().apply {
            article = { id -> if (fail) throw IOException("unavailable") else catalog.getArticle(id) }
        }
        val model = PlayerViewModel(repository, "2026-10-12-city", scope)
        yield()
        assertEquals(LoadState.Error, model.state.value)
        fail = false
        model.refresh()
        assertEquals(LoadState.Loading, model.state.value)
        yield()
        assertEquals(LoadState.Content(catalog.getArticle("2026-10-12-city")), model.state.value)
    }

    @Test(timeout = 3000)
    fun anUnnarratedArticleHasAnEmptyPlayerStateWithMetadata() = exercise { scope ->
        val model = PlayerViewModel(catalog, "2026-10-12-notes", scope)
        yield()
        assertEquals(LoadState.Empty(catalog.getArticle("2026-10-12-notes")), model.state.value)
    }

    @Test(timeout = 3000)
    fun cancellingTheScreenDoesNotTurnIntoAnError() = exercise { scope ->
        val result = CompletableDeferred<List<Magazine>>()
        val repository = ControlledRepository().apply { magazines = { result.await() } }
        val model = MagazineGridViewModel(repository, scope)
        yield()
        scope.cancel()
        yield()
        assertEquals(LoadState.Loading, model.state.value)
    }

    @Test(timeout = 3000)
    fun aCancelledRequestCannotOverwriteANewerResponse() = exercise { scope ->
        val oldResult = CompletableDeferred<List<Magazine>>()
        var calls = 0
        val latest = catalog.getMagazines().takeLast(1)
        val repository = ControlledRepository().apply {
            magazines = {
                if (++calls == 1) withContext(NonCancellable) { oldResult.await() } else latest
            }
        }
        val model = MagazineGridViewModel(repository, scope)
        try {
            yield()
            model.refresh()
            yield()
            assertEquals(LoadState.Content(latest), model.state.value)
            oldResult.complete(catalog.getMagazines())
            yield()
            yield()
            assertEquals(LoadState.Content(latest), model.state.value)
        } finally {
            oldResult.complete(emptyList())
        }
    }

    private fun exercise(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try { block(scope) } finally { scope.cancel() }
    }

    private class ControlledRepository : NewYorkerRepository by FakeNewYorkerRepository() {
        private val catalog = FakeNewYorkerRepository()
        var magazines: suspend () -> List<Magazine> = { catalog.getMagazines() }
        var articles: suspend (String) -> List<Article> = { catalog.getArticles(it) }
        var article: suspend (String) -> Article = { catalog.getArticle(it) }

        override suspend fun getMagazines() = magazines()
        override suspend fun getArticles(magazineId: String) = articles(magazineId)
        override suspend fun getArticle(articleId: String) = article(articleId)
        override suspend fun getArticleMetadata(articleId: String) = article(articleId)
    }
}
