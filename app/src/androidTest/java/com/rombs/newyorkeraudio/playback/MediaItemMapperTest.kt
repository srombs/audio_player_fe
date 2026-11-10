package com.rombs.newyorkeraudio.playback

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.playback.library.toLibraryMediaItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaItemMapperTest {
    @Test fun issueGridCarriesProgressInTitlesWhilePreservingIssueIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artwork = com.rombs.newyorkeraudio.playback.artwork.ArtworkCache(context)
        val title = "An issue with a very long title ".repeat(12)
        val node = com.rombs.newyorkeraudio.playback.library.LibraryNode("ny:issue:2026-10-12", title,
            issueProgress = com.rombs.newyorkeraudio.playback.library.IssueListeningSummary(3, 12))
        val item = node.toLibraryMediaItem(artwork)
        assertTrue(item.mediaMetadata.title.toString().endsWith(" · 3/12 listened"))
        assertTrue(item.mediaMetadata.displayTitle.toString().endsWith(" · 3/12 listened"))
        assertEquals(256, item.mediaMetadata.displayTitle!!.length)
        assertEquals(title, node.title)
        assertEquals("3/12 listened", item.mediaMetadata.subtitle.toString())
        assertEquals(node.id, item.mediaId)
        assertEquals(true, item.mediaMetadata.isBrowsable)
        assertEquals(false, item.mediaMetadata.isPlayable)
        assertNull(item.localConfiguration)
    }

    @Test fun carBrowseSubtitleIncludesKnownDurationBeforeLongDescriptions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artwork = com.rombs.newyorkeraudio.playback.artwork.ArtworkCache(context)
        for ((duration, label) in listOf(null to null, 0L to null, -1L to null,
            1_000L to "1 min", 60_000L to "1 min", 1_630_000L to "27 min")) {
            val node = com.rombs.newyorkeraudio.playback.library.LibraryNode("article", "Title", "Author",
                playable = true, browsable = false, durationMs = duration, description = "Description ".repeat(60), completed = true)
            val item = node.toLibraryMediaItem(artwork)
            val expected = (if (label == null) "Listened · " else "Listened · $label · ") + node.description
            assertEquals(expected.take(512), item.mediaMetadata.subtitle.toString())
            assertEquals(expected.take(512), item.mediaMetadata.description.toString())
            assertEquals("Title", item.mediaMetadata.title.toString())
            assertEquals("Author", item.mediaMetadata.artist.toString())
            assertEquals("article", item.mediaId)
            assertNull(item.localConfiguration)
            val withoutDescription = node.copy(description = null, completed = false).toLibraryMediaItem(artwork)
            assertEquals(label, withoutDescription.mediaMetadata.subtitle?.toString())
        }
    }

    @Test fun carCompletionIndicatorPreservesDescriptionAndMetadataOnlyBrowsing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val node = com.rombs.newyorkeraudio.playback.library.LibraryNode("article", "Title", "Author",
            playable = true, browsable = false, description = "Description", completed = true)
        val item = node.toLibraryMediaItem(com.rombs.newyorkeraudio.playback.artwork.ArtworkCache(context))
        assertNull(item.localConfiguration)
        assertEquals("Listened · Description", item.mediaMetadata.subtitle.toString())
        assertEquals("Listened · Description", item.mediaMetadata.description.toString())
        assertEquals(androidx.media3.session.MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
            item.mediaMetadata.extras!!.getInt(androidx.media3.session.MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
    }

    @Test fun mediaItemContainsNarrationMetadataAndDecodableArtwork() {
        val article = TestNarration.article.copy(description = "An article description for the car display.")
        val item = article.toMediaItem(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(article.id, item.mediaId)
        assertEquals(article.audio?.audioUrl, item.localConfiguration?.uri.toString())
        assertEquals(article.title, item.mediaMetadata.title.toString())
        assertEquals(article.author, item.mediaMetadata.artist.toString())
        assertEquals(article.title, item.mediaMetadata.displayTitle.toString())
        assertEquals(article.description, item.mediaMetadata.subtitle.toString())
        assertEquals(article.description, item.mediaMetadata.description.toString())
        assertEquals(article.imageUrl, item.mediaMetadata.artworkUri.toString())
        assertEquals(1_630_000L, item.mediaMetadata.extras?.getLong(DURATION_METADATA_KEY))
        assertNotNull(item.mediaMetadata.artworkData)
        assertEquals(0x89, item.mediaMetadata.artworkData!![0].toInt() and 0xff)
        assertEquals(article, item.toPlaybackArticle())
    }
}
