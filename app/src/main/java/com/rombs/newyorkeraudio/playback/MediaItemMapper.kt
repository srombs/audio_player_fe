package com.rombs.newyorkeraudio.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.AudioInfo
import java.net.URI
import java.io.ByteArrayOutputStream

const val DURATION_METADATA_KEY = "duration_ms"
private const val ARTICLE_URL_METADATA_KEY = "article_url"
private const val IMAGE_METADATA_KEY = "article_image"
private const val SECTION_METADATA_KEY = "article_section"
internal const val SOURCE_METADATA_KEY = "audio_source"

fun Article.toMediaItem(context: Context, localArtworkUri: Uri? = null): MediaItem {
    val source = requireNotNull(audio?.audioUrl?.takeIf { it.isNotBlank() }) { "No streaming source." }
    val uri = URI(source)
    require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank()) { "Invalid streaming source." }
    val extras = Bundle().apply {
        putLong(DURATION_METADATA_KEY, audio?.durationMs ?: 0)
        putString(ARTICLE_URL_METADATA_KEY, articleUrl)
        putString(IMAGE_METADATA_KEY, imageUrl)
        putString(SECTION_METADATA_KEY, section)
        putString(SOURCE_METADATA_KEY, source)
    }
    val artworkUri = localArtworkUri ?: Uri.parse(imageUrl ?: "android.resource://${context.packageName}/drawable/sample_artwork")
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(author)
        .setDisplayTitle(title)
        .setSubtitle(description?.take(512))
        .setDescription(description)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setArtworkUri(artworkUri)
        .setExtras(extras)
    // Notification bitmap loaders cannot decode XML vectors as raw image bytes.
    if (artworkUri.scheme == "android.resource" && artworkUri.authority == context.packageName) {
        val resource = context.resources.getIdentifier(artworkUri.path?.trimStart('/'), null, context.packageName)
        if (resource != 0) {
            context.getDrawable(resource)?.let { drawable ->
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 256, 256)
                drawable.draw(Canvas(bitmap))
                val bytes = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                bitmap.recycle()
                metadata.setArtworkData(bytes.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }
    }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(source)
        .setMediaMetadata(metadata.build())
        .build()
}

/** Rebuilds the current article after reconnecting, without depending on the repository cache. */
internal fun MediaItem.toPlaybackArticle(): Article? {
    val metadata = mediaMetadata
    val extras = metadata.extras ?: return null
    val source = extras.getString(SOURCE_METADATA_KEY)?.takeIf { it.isNotBlank() } ?: return null
    val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
    return Article(
        id = mediaId,
        title = title,
        author = metadata.artist?.toString(),
        articleUrl = extras.getString(ARTICLE_URL_METADATA_KEY),
        imageUrl = extras.getString(IMAGE_METADATA_KEY),
        section = extras.getString(SECTION_METADATA_KEY),
        audio = AudioInfo(extras.getLong(DURATION_METADATA_KEY).takeIf { it > 0 }, source),
        description = metadata.description?.toString(),
    )
}
