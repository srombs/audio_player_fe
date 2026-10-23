package com.rombs.newyorkeraudio.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SelectionPlayerTest {
    @Test fun pauseAndStopCancelEvenWhenThePlayerHasNeverStarted() = onPlayerThread {
        var cancellations = 0
        val player = SelectionPlayer(it) { cancellations++ }
        assertFalse(player.playWhenReady)
        player.pause()
        assertEquals(1, cancellations)
        player.setPlayWhenReady(false)
        assertEquals(2, cancellations)
        player.stop()
        assertEquals(3, cancellations)
        player.play()
        player.setPlayWhenReady(true)
        assertEquals(3, cancellations)
    }

    @Test fun allMedia3SetVariantsReuseTheActiveSourcePositionAndSpeed() = onPlayerThread { engine ->
        val player = SelectionPlayer(engine) {}
        val active = item()
        engine.setMediaItem(active, 12_000)
        engine.setPlaybackSpeed(1.5f)
        val reused = active.buildUpon().setTag(ActiveSelectionReuse(null)).build()
        player.setMediaItem(reused)
        player.setMediaItem(reused, 0)
        player.setMediaItem(reused, true)
        player.setMediaItems(listOf(reused))
        player.setMediaItems(listOf(reused), true)
        player.setMediaItems(listOf(reused), 0, 0)
        assertSame(active, player.currentMediaItem)
        assertEquals(12_000L, player.currentPosition)
        assertEquals(1.5f, player.playbackParameters.speed)
    }

    @Test fun reuseHonorsAnExplicitSeekWithoutReplacingTheSource() = onPlayerThread { engine ->
        val player = SelectionPlayer(engine) {}
        val active = item()
        engine.setMediaItem(active, 12_000)
        player.setMediaItems(listOf(active.buildUpon().setTag(ActiveSelectionReuse(4_000)).build()), 0, 4_000)
        assertSame(active, player.currentMediaItem)
        assertEquals(4_000L, player.currentPosition)
    }

    @Test fun aFreshSourceForTheSameArticleReplacesTheOldItem() = onPlayerThread { engine ->
        val player = SelectionPlayer(engine) {}
        engine.setMediaItem(item())
        val fresh = item("https://audio.example/fresh.mp3")
        player.setMediaItems(listOf(fresh), 0, 8_000)
        assertSame(fresh, player.currentMediaItem)
        assertEquals(8_000L, player.currentPosition)
        assertEquals(Player.STATE_IDLE, player.playbackState)
    }

    @Test fun stopAndReplacementSaveIdlePositionAndSpeedBeforeAnyReset() = onPlayerThread { engine ->
        val storage = MemoryStorage()
        val cache = PlaybackProgressCache(storage)
        val player = SelectionPlayer(engine,
            beforeItemChange = { rememberProgress(engine, cache) },
            afterItemChange = { rememberProgress(engine, cache) },
            cancelSelection = { rememberProgress(engine, cache) },
        )
        player.setMediaItem(item(), 12_000)
        engine.setPlaybackSpeed(1.5f)
        player.stop()
        assertEquals(12_000L, PlaybackProgressCache(storage).resumePosition(item().mediaId))
        assertEquals(1.5f, PlaybackProgressCache(storage).resumeSpeed(item().mediaId))
        player.setMediaItem(item().buildUpon().setMediaId("another-article").build(), 0)
        assertEquals(12_000L, cache.resumePosition(item().mediaId))
        assertFalse(player.playWhenReady)
    }

    @Test fun explicitSeekToZeroClearsIdleProgress() = onPlayerThread { engine ->
        val cache = PlaybackProgressCache(MemoryStorage())
        val player = SelectionPlayer(engine, afterSeek = { rememberProgress(engine, cache, explicitSeek = true) }, cancelSelection = {})
        player.setMediaItem(item(), 12_000)
        cache.save(item().mediaId, 12_000, 0)
        player.seekTo(0)
        assertEquals(0L, cache.resumePosition(item().mediaId))
    }

    @Test fun failedColdResumptionCanSuppressFallbackPlayWithoutSettingPlayIntent() = onPlayerThread { engine ->
        val player = SelectionPlayer(engine, handlePlayRequest = { true }, cancelSelection = {})
        player.play()
        player.setPlayWhenReady(true)
        assertFalse(player.playWhenReady)
        assertNull(player.currentMediaItem)
    }

    private class MemoryStorage(var value: String? = null) : ProgressStorage {
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    private fun item(uri: String = "https://audio.example/current.mp3") =
        MediaItem.Builder().setMediaId("https://www.newyorker.com/magazine/test-article").setUri(uri).build()

    private fun onPlayerThread(block: (ExoPlayer) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(instrumentation.targetContext).build()
            try { block(player) } finally { player.release() }
        }
    }
}
