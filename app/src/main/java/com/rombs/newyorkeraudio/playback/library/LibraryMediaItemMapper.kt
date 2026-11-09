package com.rombs.newyorkeraudio.playback.library

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.os.Bundle
import androidx.media3.session.MediaConstants
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.rombs.newyorkeraudio.playback.artwork.ArtworkCache

@OptIn(UnstableApi::class)
fun LibraryNode.toLibraryMediaItem(artwork: ArtworkCache): MediaItem {
    return MediaItem.Builder().setMediaId(id)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title.take(256)).setArtist(author?.take(256))
        .setDisplayTitle(title.take(256)).setSubtitle(displayDescription?.take(512)).setDescription(displayDescription?.take(512))
        .setIsBrowsable(browsable).setIsPlayable(playable)
        .setMediaType(if (browsable) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
        .setArtworkUri(artwork.uri(artworkUrl))
        .setExtras(Bundle().apply {
            // Style the Issues folder's children as cover tiles; the host controls sizing.
            if (id == LIBRARY_ISSUES_ID) {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            }
            if (browsable) {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            if (completed) {
                putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
                putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, 1.0)
            } else positionMs?.let { position ->
                putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                    if ((durationMs ?: 0) > 0) (position.toDouble() / durationMs!!).coerceIn(0.0, 1.0) else 0.0)
            }
        }).build()).build()
}
