package com.rombs.newyorkeraudio.ui

import com.rombs.newyorkeraudio.data.network.BackendFailure
import com.rombs.newyorkeraudio.data.network.BackendFailureKind
import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.data.repository.FakeNewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.playback.PlaybackController
import com.rombs.newyorkeraudio.playback.PlaybackFailure
import com.rombs.newyorkeraudio.playback.PlaybackState
import com.rombs.newyorkeraudio.ui.common.LoadFailure
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.magazines.MagazineGridViewModel
import com.rombs.newyorkeraudio.ui.player.PlayerViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NetworkScreensTest {
    private val article = TestNarration.article.copy(id = requireNotNull(TestNarration.article.articleUrl))

    @Test(timeout = 3000) fun `opening selected article loads metadata without autoplay`() = exercise { scope ->
        val response = CompletableDeferred<Article>()
        val repository = Repository { response.await() }
        val controller = Controller()
        val model = PlayerViewModel(repository, article.id, scope, controller)
        yield()
        assertEquals(1, repository.requests)
        assertEquals(LoadState.Loading, model.state.value)
        response.complete(article)
        yield()
        assertEquals(LoadState.Content(article), model.state.value)
        assertTrue(controller.plays.isEmpty())
    }

    @Test(timeout = 3000) fun `returning to active article needs no network and preserves playback`() = exercise { scope ->
        val repository = Repository { error("Network must not be used") }
        val controller = Controller(active())
        val model = PlayerViewModel(repository, article.id, scope, controller)
        yield()
        assertEquals(LoadState.Content(article), model.state.value)
        assertEquals(0, repository.requests)
        assertEquals(120_000L, model.playbackState.value.positionMs)
        assertEquals(1.5f, model.playbackState.value.speed)
        assertTrue(controller.plays.isEmpty())
        model.playPause()
        assertEquals(1, controller.pauses)
    }

    @Test(timeout = 3000) fun `reconnection completes before resolving metadata for restored player`() = exercise { scope ->
        val repository = Repository { error("Existing service supplies metadata") }
        val controller = Controller(PlaybackState(isConnecting = true))
        val model = PlayerViewModel(repository, article.id, scope, controller)
        yield()
        assertEquals(LoadState.Loading, model.state.value)
        assertEquals(0, repository.requests)
        controller.state.value = active()
        yield()
        assertEquals(LoadState.Content(article), model.state.value)
        assertEquals(0, repository.requests)
    }

    @Test(timeout = 3000) fun `another active track cannot replace selected article metadata`() = exercise { scope ->
        val repository = Repository { article }
        val controller = Controller(active().copy(mediaId = "another", article = article.copy(id = "another")))
        val model = PlayerViewModel(repository, article.id, scope, controller)
        yield()
        assertEquals(1, repository.requests)
        assertEquals(LoadState.Content(article), model.state.value)
        assertTrue(controller.plays.isEmpty())
    }

    @Test(timeout = 3000) fun `stream retry delegates fresh resolution to service without a screen request`() = exercise { scope ->
        val repository = Repository { error("The active session supplies metadata") }
        val controller = Controller(active())
        val model = PlayerViewModel(repository, article.id, scope, controller)
        yield()
        controller.state.value = active().copy(error = PlaybackFailure.STREAM)
        model.retryAudio()
        assertEquals(listOf(article), controller.retries)
        assertEquals(0, repository.requests)
        assertTrue(controller.plays.isEmpty())
    }

    @Test(timeout = 3000) fun `leaving player cancels service selection`() = exercise { scope ->
        val controller = Controller(active())
        val model = PlayerViewModel(Repository { article }, article.id, scope, controller)
        yield()
        model.cancelPendingPlayback()
        assertEquals(1, controller.cancellations)
    }

    @Test(timeout = 3000) fun `play selects unresolved catalog metadata without fetching audio in the screen`() = exercise { scope ->
        val summary = article.copy(audio = article.audio!!.copy(audioUrl = null), audioAvailable = null)
        val repository = Repository { summary }
        val controller = Controller()
        val model = PlayerViewModel(repository, article.id, scope, controller)
        yield()
        assertEquals(LoadState.Content(summary), model.state.value)
        assertTrue(controller.plays.isEmpty())
        model.playPause()
        assertEquals(listOf(summary), controller.plays)
        assertEquals(1, repository.requests)
    }

    @Test(timeout = 3000) fun `resolved absence shows metadata and never plays`() = exercise { scope ->
        val absent = article.copy(audio = null, audioAvailable = false)
        val controller = Controller()
        val model = PlayerViewModel(Repository { absent }, article.id, scope, controller)
        yield()
        assertEquals(LoadState.Empty(absent), model.state.value)
        model.playPause()
        assertTrue(controller.plays.isEmpty())
    }

    @Test(timeout = 3000) fun `connection retry uses loaded source without repeating resolution`() = exercise { scope ->
        val repository = Repository { article }
        val controller = Controller(PlaybackState(error = PlaybackFailure.CONNECTION))
        val model = PlayerViewModel(repository, article.id, scope, controller)
        yield()
        model.retryAudio()
        assertEquals(1, repository.requests)
        assertEquals(listOf(article), controller.plays)
    }

    @Test(timeout = 3000) fun `resuming paused current track uses session source instead of stale screen source`() = exercise { scope ->
        val controller = Controller()
        val model = PlayerViewModel(Repository { article }, article.id, scope, controller)
        yield()
        val current = article.copy(audio = article.audio!!.copy(audioUrl = "https://audio.example/current.mp3"))
        controller.state.value = active().copy(article = current, playWhenReady = false)
        model.playPause()
        assertEquals(listOf(current), controller.plays)
    }

    @Test(timeout = 3000) fun `resolution error retry reloads selected article without autoplay`() = exercise { scope ->
        var unavailable = true
        val controller = Controller()
        val repository = Repository {
            if (unavailable) throw BackendFailure(BackendFailureKind.HTTP, 502, "audio_resolution_failed", "retry")
            article
        }
        val model = PlayerViewModel(repository, article.id, scope, controller)
        yield()
        assertEquals(LoadState.Failure(LoadFailure.AUDIO_UNAVAILABLE), model.state.value)
        unavailable = false
        model.refresh()
        yield()
        assertEquals(LoadState.Content(article), model.state.value)
        assertEquals(2, repository.requests)
        assertTrue(controller.plays.isEmpty())
    }

    @Test(timeout = 3000) fun `network and backend failures reach specific screen states`() = exercise { scope ->
        val cases = listOf(
            BackendFailure(BackendFailureKind.CONNECTION) to LoadFailure.CONNECTION,
            BackendFailure(BackendFailureKind.TIMEOUT) to LoadFailure.TIMEOUT,
            BackendFailure(BackendFailureKind.INVALID_RESPONSE) to LoadFailure.INVALID_RESPONSE,
            BackendFailure(BackendFailureKind.HTTP, 503, "newyorker_session_missing", "reconnect") to LoadFailure.SESSION_RECONNECT,
            BackendFailure(BackendFailureKind.HTTP, 503, "newyorker_session_reconnect_required", "reconnect") to LoadFailure.SESSION_RECONNECT,
            BackendFailure(BackendFailureKind.HTTP, 403, "newyorker_session_not_subscribed", "check_subscription") to LoadFailure.SUBSCRIPTION,
            BackendFailure(BackendFailureKind.HTTP, 503, "newyorker_session_unverifiable", "contact_operator") to LoadFailure.SESSION_UNAVAILABLE,
            BackendFailure(BackendFailureKind.HTTP, 503, "issue_index_missing", "sync_issue_index") to LoadFailure.LIBRARY_UNAVAILABLE,
            BackendFailure(BackendFailureKind.HTTP, 404) to LoadFailure.NOT_FOUND,
            BackendFailure(BackendFailureKind.HTTP, 404, "audio_endpoint_unavailable", "contact_operator") to LoadFailure.AUDIO_SERVICE_UNAVAILABLE,
            BackendFailure(BackendFailureKind.HTTP, 502) to LoadFailure.SERVER_UNAVAILABLE,
        )
        for ((error, reason) in cases) {
            val repository = object : NewYorkerRepository by FakeNewYorkerRepository() {
                override suspend fun getMagazines(): List<com.rombs.newyorkeraudio.domain.model.Magazine> = throw error
            }
            val model = MagazineGridViewModel(repository, scope)
            yield()
            assertEquals(LoadState.Failure(reason), model.state.value)
        }
    }

    private fun active() = PlaybackState(mediaId = article.id, article = article, isConnected = true,
        positionMs = 120_000, speed = 1.5f, playWhenReady = true)

    private class Repository(private val resolve: suspend () -> Article) : NewYorkerRepository by FakeNewYorkerRepository() {
        var requests = 0
        override suspend fun getArticleMetadata(articleId: String): Article { requests++; return resolve() }
        override suspend fun resolveAudio(articleId: String, refresh: Boolean): Article = error("Screens must not resolve audio")
    }

    private class Controller(initial: PlaybackState = PlaybackState()) : PlaybackController {
        override val state = MutableStateFlow(initial)
        val plays = mutableListOf<Article>()
        var pauses = 0
        var cancellations = 0
        val retries = mutableListOf<Article>()
        override fun play(article: Article) { plays += article }
        override fun pause() { pauses++ }
        override fun retry(article: Article) { retries += article }
        override fun cancelPendingPlayback() { cancellations++ }
        override fun seekTo(positionMs: Long) = Unit
        override fun skipBackward() = Unit
        override fun skipForward() = Unit
        override fun setSpeed(speed: Float) = Unit
    }

    private fun exercise(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try { block(scope) } finally { scope.cancel() }
    }
}
