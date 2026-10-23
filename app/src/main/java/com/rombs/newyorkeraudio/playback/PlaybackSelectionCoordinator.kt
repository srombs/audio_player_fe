package com.rombs.newyorkeraudio.playback

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/** Owned by the player thread. Cancellation completes the caller even if upstream work ignores it. */
internal class PlaybackSelectionCoordinator(private val scope: CoroutineScope) {
    private var generation = 0L
    private var pending: Pending? = null
    val hasPending: Boolean get() = pending != null

    internal class Request(private val isCurrent: () -> Boolean) {
        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("Playback selection was superseded.")
        }
    }

    private class Pending(val job: Job, val future: SettableFuture<*>)

    fun cancel() {
        generation++
        val previous = pending
        pending = null
        previous?.future?.cancel(false)
        previous?.job?.cancel()
    }

    fun <T> select(block: suspend (Request) -> T): ListenableFuture<T> {
        cancel()
        val number = generation
        val result = SettableFuture.create<T>()
        val request = Request { generation == number && !result.isCancelled }
        // Register before starting: a reused article can finish synchronously on Main.immediate.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val selected = block(request)
                request.ensureCurrent()
                result.set(selected)
            } catch (_: CancellationException) {
                result.cancel(false)
            } catch (error: Exception) {
                result.setException(error)
            } finally {
                if (generation == number) pending = null
            }
        }
        pending = Pending(job, result)
        job.invokeOnCompletion { error ->
            // Also handles scope shutdown before this lazy coroutine can start.
            if (error is CancellationException) result.cancel(false)
            else if (error != null) result.setException(error)
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, Executor { it.run() })
        job.start()
        return result
    }
}
