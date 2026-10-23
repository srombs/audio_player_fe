package com.rombs.newyorkeraudio.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException

class PlaybackSelectionCoordinatorTest {
    @Test fun `latest phone or car selection wins even when old resolver ignores cancellation`() = runBlocking {
        val selections = PlaybackSelectionCoordinator(this)
        val releaseOld = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        val phone = selections.select { request ->
            withContext(NonCancellable) {
                releaseOld.await()
                request.ensureCurrent()
                committed += "phone"
            }
        }
        yield()
        val car = selections.select { request -> request.ensureCurrent(); committed += "car" }
        assertTrue("Superseded callback must finish before upstream returns", phone.isCancelled)
        yield()
        assertTrue(car.isDone)
        releaseOld.complete(Unit)
        yield()
        assertEquals(listOf("car"), committed)
    }

    @Test fun `pause or stop during cold resolution cancels callback and prevents delayed play`() = runBlocking {
        val selections = PlaybackSelectionCoordinator(this)
        val release = CompletableDeferred<Unit>()
        var playing = false
        val result = selections.select { request ->
            withContext(NonCancellable) { release.await(); request.ensureCurrent(); playing = true }
        }
        yield()
        selections.cancel()
        assertTrue(result.isCancelled)
        release.complete(Unit)
        yield()
        assertFalse(playing)
        val next = selections.select { request -> request.ensureCurrent(); playing = true }
        yield()
        assertTrue(next.isDone)
        assertTrue(playing)
    }

    @Test fun `caller future cancellation also prevents noncooperative late mutation`() = runBlocking {
        val selections = PlaybackSelectionCoordinator(this)
        val release = CompletableDeferred<Unit>()
        var committed = false
        val result = selections.select { request ->
            withContext(NonCancellable) { release.await(); request.ensureCurrent(); committed = true }
        }
        yield()
        result.cancel(false)
        release.complete(Unit)
        yield()
        assertFalse(committed)
    }

    @Test fun `failed replacement keeps the active track and a following selection can succeed`() = runBlocking {
        val selections = PlaybackSelectionCoordinator(this)
        var active = "existing"
        val failed = selections.select<Unit> { throw IllegalStateException("Resolver unavailable") }
        yield()
        assertTrue(failed.isDone)
        try { failed.get(); fail("Expected resolution error") }
        catch (error: ExecutionException) { assertTrue(error.cause is IllegalStateException) }
        assertEquals("existing", active)
        val next = selections.select { request -> request.ensureCurrent(); active = "replacement" }
        yield()
        assertTrue(next.isDone)
        assertEquals("replacement", active)
    }

    @Test fun `service shutdown finishes pending callbacks before upstream completes`() = runBlocking {
        val service = CoroutineScope(coroutineContext + Job())
        val selections = PlaybackSelectionCoordinator(service)
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var committed = false
        val result = selections.select { request ->
            try {
                withContext(NonCancellable) { release.await(); request.ensureCurrent(); committed = true }
            } finally { stopped.complete(Unit) }
        }
        yield()
        selections.cancel()
        service.cancel()
        assertTrue(result.isCancelled)
        release.complete(Unit)
        stopped.await()
        assertFalse(committed)
    }

    @Test fun `requests in an already destroyed service finish cancelled`() = runBlocking {
        val service = CoroutineScope(coroutineContext + Job())
        service.cancel()
        val result = PlaybackSelectionCoordinator(service).select { "never" }
        assertTrue(result.isCancelled)
    }
}
