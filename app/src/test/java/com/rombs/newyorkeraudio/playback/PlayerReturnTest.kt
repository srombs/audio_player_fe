package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.data.repository.ArticleMetadataCache
import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.domain.model.AudioInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerReturnTest {
    @Test fun `returning after browsing an issue retains the saved seek fraction without resolving audio`() {
        val resolved = TestNarration.article.copy(id = TestNarration.article.articleUrl!!)
        val metadata = ArticleMetadataCache(null)
        val progress = PlaybackProgressCache(object : ProgressStorage {
            override fun read(): String? = null
            override fun write(value: String) {}
        })
        metadata.putAll(listOf(resolved))
        progress.save(resolved.id, 120_000, resolved.audio!!.durationMs!!, article = resolved)

        // Issue listings contain narration hints, without duration or a playable source.
        metadata.putAll(listOf(resolved.copy(audio = AudioInfo(null, null))))
        val reopened = metadata.get(resolved.id)!!
        val displayed = PlaybackState().forArticle(reopened, progress.resumePosition(reopened.id))

        assertEquals(120_000L, displayed.positionMs)
        assertEquals(1_630_000L, displayed.durationMs)
        assertEquals(120_000f / 1_630_000f, displayed.positionMs.toFloat() / displayed.durationMs, 0.0001f)
        assertNull(reopened.audio!!.audioUrl)
    }
}
