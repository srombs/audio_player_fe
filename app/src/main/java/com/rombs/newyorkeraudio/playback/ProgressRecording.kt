package com.rombs.newyorkeraudio.playback

import androidx.media3.common.Player

/** Called by the service as well as before the UI switches away from a track. */
internal fun rememberProgress(player: Player, cache: PlaybackProgressCache, explicitSeek: Boolean = false) {
    val item = player.currentMediaItem ?: return
    // Idle events during setMediaItem/retry can contain a temporary zero position.
    if (player.playbackState == Player.STATE_IDLE && player.currentPosition == 0L && !explicitSeek) return
    val duration = player.duration.takeIf { it > 0 }
        ?: item.mediaMetadata.extras?.getLong(DURATION_METADATA_KEY) ?: 0
    cache.save(item.mediaId, player.currentPosition, duration, completed = player.playbackState == Player.STATE_ENDED,
        speed = player.playbackParameters.speed, article = item.toPlaybackArticle())
}
