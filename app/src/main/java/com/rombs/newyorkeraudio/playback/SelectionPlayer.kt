package com.rombs.newyorkeraudio.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** A service-created local tag; controller-provided items cannot request source reuse. */
internal data class ActiveSelectionReuse(val seekPositionMs: Long?)

/** Intercepts actual Pause/Stop commands, including Pause when playWhenReady is already false. */
@OptIn(UnstableApi::class)
internal class SelectionPlayer(
    player: Player,
    private val beforeItemChange: () -> Unit = {},
    private val afterItemChange: () -> Unit = {},
    private val afterSeek: () -> Unit = {},
    private val handlePlayRequest: () -> Boolean = { false },
    private val cancelSelection: () -> Unit,
) : ForwardingPlayer(player) {
    override fun play() {
        if (!handlePlayRequest()) super.play()
    }

    override fun pause() {
        cancelSelection()
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!playWhenReady) cancelSelection()
        else if (handlePlayRequest()) return
        super.setPlayWhenReady(playWhenReady)
    }

    override fun stop() {
        cancelSelection()
        super.stop()
        super.pause()
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        if (!reuse(mediaItem)) replace { super.setMediaItem(mediaItem) }
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (!reuse(mediaItem)) replace { super.setMediaItem(mediaItem, startPositionMs) }
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (!reuse(mediaItem)) replace { super.setMediaItem(mediaItem, resetPosition) }
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        if (!reuse(mediaItems.singleOrNull())) replace { super.setMediaItems(mediaItems) }
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (!reuse(mediaItems.singleOrNull())) replace { super.setMediaItems(mediaItems, resetPosition) }
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (startIndex != 0 || !reuse(mediaItems.singleOrNull())) replace { super.setMediaItems(mediaItems, startIndex, startPositionMs) }
    }

    override fun clearMediaItems() = replace { super.clearMediaItems() }
    override fun seekTo(positionMs: Long) { super.seekTo(positionMs); afterSeek() }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) { super.seekTo(mediaItemIndex, positionMs); afterSeek() }
    override fun seekToDefaultPosition() { super.seekToDefaultPosition(); afterSeek() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) { super.seekToDefaultPosition(mediaItemIndex); afterSeek() }
    override fun seekBack() { super.seekBack(); afterSeek() }
    override fun seekForward() { super.seekForward(); afterSeek() }

    private inline fun replace(action: () -> Unit) {
        beforeItemChange()
        action()
        afterItemChange()
    }

    private fun reuse(item: MediaItem?): Boolean {
        val reuse = item?.localConfiguration?.tag as? ActiveSelectionReuse ?: return false
        val current = currentMediaItem ?: return false
        if (playerError != null || current.mediaId != item.mediaId || current.localConfiguration?.uri != item.localConfiguration?.uri) return false
        reuse.seekPositionMs?.let { seekTo(it) }
        return true
    }
}
