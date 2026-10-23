package com.rombs.newyorkeraudio.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises Media3 1.8.0's real cold-Play callback context and cancellation fallback, without networking. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackResumptionTest {
    @Test fun explicitColdPlayHasCallerContextAndCancelledResumptionDoesNotStartPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requested = CountDownLatch(1)
        val hasCaller = AtomicBoolean(false)
        val pending = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        lateinit var engine: ExoPlayer
        lateinit var session: MediaLibrarySession
        lateinit var connection: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            engine = ExoPlayer.Builder(context).build()
            val player = SelectionPlayer(engine, handlePlayRequest = { engine.currentMediaItem == null },
                cancelSelection = { pending.cancel(false) })
            session = MediaLibrarySession.Builder(context, player, object : MediaLibrarySession.Callback {
                override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo)
                    : ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    hasCaller.set(mediaSession.controllerForCurrentRequest != null)
                    requested.countDown()
                    return pending
                }
            }).setId("cold-resumption-test").build()
            connection = MediaController.Builder(context, session.token).buildAsync()
        }
        try {
            val controller = connection.get(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { controller.play() }
            assertTrue("Cold Play did not request resumption", requested.await(5, TimeUnit.SECONDS))
            assertTrue("Explicit Play must be distinguishable from boot metadata lookup", hasCaller.get())
            instrumentation.runOnMainSync {
                pending.cancel(false)
                assertFalse(engine.playWhenReady)
                assertNull(engine.currentMediaItem)
            }
        } finally {
            instrumentation.runOnMainSync {
                pending.cancel(false)
                MediaController.releaseFuture(connection)
                session.release()
                engine.release()
            }
        }
    }
}
