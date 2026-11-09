package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.data.repository.FakeNewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.ui.player.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerCommandsTest {
    private val repository = FakeNewYorkerRepository(TestNarration.article)

    @Test(timeout = 3000) fun openingAnArticleDoesNotResetOrAutoPlayTheSession() = exercise { scope ->
        val port = FakeController(PlaybackState(mediaId = TestNarration.article.id, positionMs = 123_000, playWhenReady = true))
        val model = PlayerViewModel(repository, TestNarration.article.id, scope, port)
        yield()
        assertEquals(123_000, model.playbackState.value.positionMs)
        assertEquals(0, port.plays.size)
    }

    @Test(timeout = 3000) fun playPauseUsesTheSelectedArticleAndCurrentSessionIntent() = exercise { scope ->
        val port = FakeController()
        val model = PlayerViewModel(repository, TestNarration.article.id, scope, port)
        yield()
        model.playPause()
        assertEquals(listOf(TestNarration.article), port.plays)
        port.state.value = PlaybackState(mediaId = TestNarration.article.id, isConnected = true, playWhenReady = true)
        model.playPause()
        assertEquals(1, port.pauses)
    }

    @Test(timeout = 3000) fun retryAfterAStreamFailurePreparesAgainInsteadOfPausing() = exercise { scope ->
        val port = FakeController(PlaybackState(mediaId = TestNarration.article.id, playWhenReady = true, error = PlaybackFailure.STREAM))
        val model = PlayerViewModel(repository, TestNarration.article.id, scope, port)
        yield()
        model.playPause()
        yield()
        assertEquals(1, port.plays.size)
        assertEquals(0, port.pauses)
    }

    @Test(timeout = 3000) fun controlsCannotSeekOrChangeTheSpeedOfAnotherArticle() = exercise { scope ->
        val port = FakeController(PlaybackState(mediaId = "different", isConnected = true))
        val model = PlayerViewModel(repository, TestNarration.article.id, scope, port)
        yield()
        model.seekTo(30_000); model.skipBackward(); model.skipForward(); model.setSpeed(1.5f)
        assertEquals(0, port.seeks.size + port.backwards + port.forwards + port.speeds.size)
    }

    @Test(timeout = 3000) fun loadedCurrentArticleDelegatesSeekSkipsAndSupportedSpeeds() = exercise { scope ->
        val port = FakeController(PlaybackState(mediaId = TestNarration.article.id, isConnected = true))
        val model = PlayerViewModel(repository, TestNarration.article.id, scope, port)
        model.seekTo(99_000)
        assertTrue(port.seeks.isEmpty())
        yield()
        model.seekTo(30_000); model.skipBackward(); model.skipForward(); model.setSpeed(1.5f); model.setSpeed(-1f)
        assertEquals(listOf(30_000L), port.seeks)
        assertEquals(1, port.backwards)
        assertEquals(1, port.forwards)
        assertEquals(listOf(1.5f), port.speeds)
    }

    @Test(timeout = 3000) fun unresolvedSampleArticlesDoNotRequestPlayback() = exercise { scope ->
        val port = FakeController()
        val model = PlayerViewModel(repository, "2026-10-12-city", scope, port)
        yield()
        model.playPause()
        assertTrue(port.plays.isEmpty())
    }

    @Test(timeout = 3000) fun confirmedNarrationAbsenceRejectsAllControlsWhileScreenMetadataIsStillNarrated() = exercise { scope ->
        val port = FakeController(PlaybackState(mediaId = TestNarration.article.id, isConnected = true,
            error = PlaybackFailure.NO_NARRATION))
        val model = PlayerViewModel(repository, TestNarration.article.id, scope, port)
        yield()
        model.playPause()
        model.retryAudio()
        model.seekTo(30_000)
        model.skipBackward()
        model.skipForward()
        model.setSpeed(1.5f)
        assertEquals(0, port.plays.size + port.seeks.size + port.backwards + port.forwards + port.speeds.size)
    }

    @Test(timeout = 3000) fun anotherArticlesNarrationAbsenceDoesNotPreventPlayingTheSelectedArticle() = exercise { scope ->
        val port = FakeController(PlaybackState(mediaId = "another", isConnected = true,
            error = PlaybackFailure.NO_NARRATION))
        val model = PlayerViewModel(repository, TestNarration.article.id, scope, port)
        yield()
        model.playPause()
        assertEquals(listOf(TestNarration.article), port.plays)
    }

    private fun exercise(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try { block(scope) } finally { scope.cancel() }
    }

    private class FakeController(initial: PlaybackState = PlaybackState()) : PlaybackController {
        override val state = MutableStateFlow(initial)
        val plays = mutableListOf<Article>()
        var pauses = 0
        val seeks = mutableListOf<Long>()
        var backwards = 0
        var forwards = 0
        val speeds = mutableListOf<Float>()
        override fun play(article: Article) { plays += article }
        override fun pause() { pauses++ }
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun skipBackward() { backwards++ }
        override fun skipForward() { forwards++ }
        override fun setSpeed(speed: Float) { speeds += speed }
    }
}
