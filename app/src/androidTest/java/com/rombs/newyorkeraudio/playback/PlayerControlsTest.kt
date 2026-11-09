package com.rombs.newyorkeraudio.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.data.repository.ArticleMetadataCache
import com.rombs.newyorkeraudio.domain.model.AudioInfo
import com.rombs.newyorkeraudio.ui.player.PlayerControls
import com.rombs.newyorkeraudio.ui.theme.NewYorkerAudioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerControlsTest {
    @get:Rule val compose = createComposeRule()
    private val article = TestNarration.article
    private val active = PlaybackState(mediaId = article.id, isConnected = true, positionMs = 10_000, durationMs = 100_000, canSeek = true)

    @Test fun buttonsShowTheSessionIntentAndSendPlayPauseAndSkipActions() {
        var state by mutableStateOf(active)
        var toggles = 0
        var backwards = 0
        var forwards = 0
        compose.setContent {
            NewYorkerAudioTheme {
                PlayerControls(article, state, { toggles++ }, {}, { backwards++ }, { forwards++ }, {})
            }
        }
        compose.onNodeWithContentDescription("Play").performClick()
        compose.runOnIdle { state = active.copy(playWhenReady = true, isPlaying = true) }
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithTag("skip-back").performClick()
        compose.onNodeWithTag("skip-forward").performClick()
        compose.runOnIdle {
            assertEquals(2, toggles)
            assertEquals(1, backwards)
            assertEquals(1, forwards)
        }
    }

    @Test fun progressLabelsAndAccessibleSeekUseMilliseconds() {
        var requested: Long? = null
        compose.setContent {
            NewYorkerAudioTheme { PlayerControls(article, active, {}, { requested = it }, {}, {}, {}) }
        }
        compose.onNodeWithTag("elapsed").assertTextEquals("0:10")
        compose.onNodeWithTag("duration").assertTextEquals("1:40")
        compose.onNodeWithTag("seek").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { assertEquals(50_000L, requested) }
    }

    @Test fun playbackSpeedSelectionInvokesTheCallback() {
        var requested: Float? = null
        compose.setContent {
            NewYorkerAudioTheme { PlayerControls(article, active, {}, {}, {}, {}, { requested = it }) }
        }
        compose.onNodeWithTag("speed").performClick()
        compose.onNodeWithText("1.5×").performClick()
        compose.runOnIdle { assertEquals(1.5f, requested) }
    }

    @Test fun aSampleWithoutAStreamingUrlCannotSendPlayerCommands() {
        val unresolved = article.copy(audio = article.audio?.copy(audioUrl = null))
        compose.setContent {
            NewYorkerAudioTheme { PlayerControls(unresolved, active, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("play-pause").assertIsNotEnabled()
        compose.onNodeWithTag("seek").assertIsNotEnabled()
        compose.onNodeWithTag("skip-forward").assertIsNotEnabled()
    }

    @Test fun streamFailureUsesAudioRetryCallback() {
        var retried = 0
        var toggled = 0
        compose.setContent {
            NewYorkerAudioTheme {
                PlayerControls(article, active.copy(error = PlaybackFailure.STREAM),
                    { toggled++ }, {}, {}, {}, {}, onRetryAudio = { retried++ })
            }
        }
        compose.onNodeWithTag("retry-audio").performClick()
        compose.runOnIdle {
            assertEquals(1, retried)
            assertEquals(0, toggled)
        }
    }

    @Test fun savedProgressAppearsBeforeStartingPlayback() {
        compose.setContent {
            NewYorkerAudioTheme {
                PlayerControls(article, PlaybackState(), {}, {}, {}, {}, {}, savedPositionMs = 120_000)
            }
        }
        compose.onNodeWithTag("elapsed").assertTextEquals("2:00")
        compose.onNodeWithTag("seek").assertIsNotEnabled()
    }

    @Test fun returningAfterCatalogRefreshDisplaysSavedElapsedTimeAndSliderPosition() {
        val metadata = ArticleMetadataCache(null)
        metadata.putAll(listOf(article))
        var showingPlayer by mutableStateOf(true)
        compose.setContent {
            NewYorkerAudioTheme {
                if (showingPlayer) PlayerControls(metadata.get(article.id)!!, PlaybackState(), {}, {}, {}, {}, {},
                    savedPositionMs = 120_000)
            }
        }
        compose.runOnIdle { showingPlayer = false }
        compose.waitForIdle()
        metadata.putAll(listOf(article.copy(audio = AudioInfo(null, null))))
        compose.runOnIdle { showingPlayer = true }
        compose.onNodeWithTag("elapsed").assertTextEquals("2:00")
        compose.onNodeWithTag("duration").assertTextEquals("27:10")
        val progress = compose.onNodeWithTag("seek").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(120_000f / 1_630_000f, progress, 0.0001f)
    }

    @Test fun knownNarrationAbsenceDisablesEveryControlDespiteStaleSourceMetadata() {
        compose.setContent {
            NewYorkerAudioTheme {
                PlayerControls(article.copy(audioAvailable = false), active, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("No narration is available for this article.").assertExists()
        for (tag in listOf("seek", "play-pause", "skip-back", "skip-forward", "speed")) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        compose.onNodeWithTag("retry-audio").assertDoesNotExist()
    }

    @Test fun unknownNarrationKeepsPlayAvailableToCheckForAudio() {
        val unknown = article.copy(audioAvailable = null, audio = AudioInfo(null, null))
        compose.setContent {
            NewYorkerAudioTheme { PlayerControls(unknown, PlaybackState(), {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("play-pause").assertIsEnabled()
        compose.onNodeWithText("No narration is available for this article.").assertDoesNotExist()
    }

    @Test fun discoveringNoNarrationDisablesControlsAndRemovesRetryWithoutReopeningScreen() {
        var playback by mutableStateOf(active)
        compose.setContent {
            NewYorkerAudioTheme { PlayerControls(article, playback, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("play-pause").assertIsEnabled()
        compose.onNodeWithTag("seek").assertIsEnabled()
        compose.runOnIdle { playback = active.copy(error = PlaybackFailure.NO_NARRATION) }
        compose.onNodeWithText("No narration is available for this article.").assertExists()
        for (tag in listOf("seek", "play-pause", "skip-back", "skip-forward", "speed")) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        compose.onNodeWithTag("retry-audio").assertDoesNotExist()
    }
}
