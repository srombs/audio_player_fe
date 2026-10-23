package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.data.repository.FakeNewYorkerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackMathTest {
    @Test fun skipsStopAtTheStartAndEndOfAnArticle() {
        assertEquals(0, skipPosition(5_000, 30_000, false))
        assertEquals(30_000, skipPosition(25_000, 30_000, true))
        assertEquals(25_000, skipPosition(10_000, 60_000, true))
        assertEquals(10_000, skipPosition(25_000, 60_000, false))
    }

    @Test fun seeksHandleUnknownDurationAndInvalidPositions() {
        assertEquals(0, clampPosition(-1, 100))
        assertEquals(100, clampPosition(500, 100))
        assertEquals(500, clampPosition(500, 0))
        assertEquals(Long.MAX_VALUE, skipPosition(Long.MAX_VALUE, 0, true))
    }

    @Test fun timeLabelsHandleLongArticlesAndMissingDuration() {
        assertEquals("0:00", formatPlaybackTime(-1))
        assertEquals("27:10", formatPlaybackTime(1_630_000))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
    }

    @Test fun anotherArticleCannotInheritTheActiveTracksProgressOrError() {
        val projected = PlaybackState(
            mediaId = "different", isConnected = true, isPlaying = true, playWhenReady = true,
            positionMs = 12_000, durationMs = 30_000, speed = 2f, canSeek = true, error = PlaybackFailure.STREAM,
        ).forArticle(TestNarration.article)
        assertFalse(projected.isPlaying)
        assertFalse(projected.playWhenReady)
        assertFalse(projected.canSeek)
        assertNull(projected.error)
        assertEquals(0, projected.positionMs)
        assertEquals(1_630_000, projected.durationMs)
    }

    @Test fun theSelectedTrackRetainsItsProgressAndConnectionErrorsRemainVisible() {
        val active = PlaybackState(mediaId = TestNarration.article.id, positionMs = 12_000, speed = 1.5f)
        assertEquals(active, active.forArticle(TestNarration.article))
        assertEquals(PlaybackFailure.CONNECTION, PlaybackState(error = PlaybackFailure.CONNECTION).forArticle(TestNarration.article).error)
    }

    @Test fun injectedTestArticleIsAvailableByIdWithoutChangingTheOtherSampleIssues() = runBlocking {
        val repository = FakeNewYorkerRepository(TestNarration.article)
        assertEquals(TestNarration.article, repository.getArticles(TestNarration.ISSUE_ID).first())
        assertEquals(TestNarration.article, repository.getArticle(TestNarration.article.id))
        assertEquals(4, repository.getArticles("2026-10-05").size)
        assertEquals(17, repository.getMagazines().sumOf { repository.getArticles(it.id).size })
    }
}
