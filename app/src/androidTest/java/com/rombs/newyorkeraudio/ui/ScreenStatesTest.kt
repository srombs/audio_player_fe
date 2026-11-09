package com.rombs.newyorkeraudio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.AudioInfo
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.ui.articles.ArticleListContent
import com.rombs.newyorkeraudio.ui.articles.ArticleListScreen
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.common.LoadFailure
import com.rombs.newyorkeraudio.ui.magazines.MagazineGridScreen
import com.rombs.newyorkeraudio.ui.player.PlayerScreen
import com.rombs.newyorkeraudio.ui.theme.NewYorkerAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenStatesTest {
    @get:Rule val compose = createComposeRule()
    private val issue = Magazine("2026-10-12", "October 12, 2026", "2026-10-12", null)
    private val article = Article("sample", "The City After Midnight", "Alex Morgan", null, null, "Reporting", AudioInfo(27 * 60_000L, null))

    @Test
    fun loadingIssuesShowsProgressWithoutOldContentOrRetry() {
        compose.setContent {
            NewYorkerAudioTheme { MagazineGridScreen(LoadState.Loading, {}, {}) }
        }
        compose.onNodeWithTag("loading").assertIsDisplayed()
        compose.onNodeWithTag("retry").assertDoesNotExist()
        compose.onNodeWithTag("magazines").assertDoesNotExist()
    }

    @Test
    fun issueErrorRetryInvokesTheCallbackAndReplacesTheError() {
        var state by mutableStateOf<LoadState<List<Magazine>>>(LoadState.Error)
        compose.setContent {
            NewYorkerAudioTheme {
                MagazineGridScreen(state, onRetry = { state = LoadState.Content(listOf(issue)) }, onIssueClick = {})
            }
        }
        compose.onNodeWithTag("error").assertIsDisplayed()
        compose.onNodeWithTag("retry").performClick()
        compose.onNodeWithTag("issue:${issue.id}").assertIsDisplayed()
        compose.onNodeWithTag("error").assertDoesNotExist()
    }

    @Test
    fun emptyIssuesCanRefreshIntoContent() {
        var state by mutableStateOf<LoadState<List<Magazine>>>(LoadState.Empty(emptyList()))
        compose.setContent {
            NewYorkerAudioTheme {
                MagazineGridScreen(state, onRetry = { state = LoadState.Content(listOf(issue)) }, onIssueClick = {})
            }
        }
        compose.onNodeWithText("No issues available.").assertIsDisplayed()
        compose.onNodeWithTag("refresh").performClick()
        compose.onNodeWithTag("empty").assertDoesNotExist()
        compose.onNodeWithTag("issue:${issue.id}").assertIsDisplayed()
    }

    @Test
    fun emptyArticlesKeepTheSelectedIssueAndSupportRefresh() {
        var state by mutableStateOf<LoadState<ArticleListContent>>(LoadState.Empty(ArticleListContent(issue, emptyList())))
        compose.setContent {
            NewYorkerAudioTheme {
                ArticleListScreen(
                    state,
                    onRetry = { state = LoadState.Content(ArticleListContent(issue, listOf(article))) },
                    onBack = {}, onArticleClick = {},
                )
            }
        }
        compose.onNodeWithText(issue.title).assertIsDisplayed()
        compose.onNodeWithText("No articles in this issue.").assertIsDisplayed()
        compose.onNodeWithTag("refresh").performClick()
        compose.onNodeWithTag("article:${article.id}").assertIsDisplayed()
        compose.onNodeWithTag("empty").assertDoesNotExist()
    }

    @Test
    fun articleLoadingTransitionsToErrorWhileBackRemainsAvailable() {
        var state by mutableStateOf<LoadState<ArticleListContent>>(LoadState.Loading)
        compose.setContent {
            NewYorkerAudioTheme { ArticleListScreen(state, {}, {}, {}) }
        }
        compose.onNodeWithTag("loading").assertIsDisplayed()
        compose.runOnIdle { state = LoadState.Error }
        compose.onNodeWithTag("loading").assertDoesNotExist()
        compose.onNodeWithTag("retry").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun failedPlayerMetadataCanRetryIntoTheSelectedArticle() {
        var state by mutableStateOf<LoadState<Article>>(LoadState.Error)
        compose.setContent {
            NewYorkerAudioTheme { PlayerScreen(state, onRetry = { state = LoadState.Content(article) }, onBack = {}) }
        }
        compose.onNodeWithTag("retry").performClick()
        compose.onNodeWithTag("player:${article.id}").performScrollToNode(hasText(article.title))
        compose.onNodeWithText(article.title).assertIsDisplayed()
        compose.onNodeWithTag("error").assertDoesNotExist()
    }

    @Test
    fun unnarratedPlayerShowsAnUnavailableMessage() {
        compose.setContent {
            NewYorkerAudioTheme { PlayerScreen(LoadState.Empty(article.copy(audio = null)), {}, {}) }
        }
        compose.onNodeWithTag("player:${article.id}")
            .performScrollToNode(hasText("No narration is available for this article."))
        compose.onNodeWithText("No narration is available for this article.").assertIsDisplayed()
        for (tag in listOf("seek", "play-pause", "skip-back", "skip-forward", "speed")) {
            compose.onNodeWithTag("player:${article.id}")
                .performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
    }

    @Test fun unknownNarrationRemainsSelectableWithoutClaimingItIsNarrated() {
        val unknown = article.copy(audioAvailable = null, audio = AudioInfo(null, null))
        compose.setContent {
            NewYorkerAudioTheme {
                ArticleListScreen(LoadState.Content(ArticleListContent(issue, listOf(unknown))), {}, {}, {})
            }
        }
        compose.onNodeWithTag("article:${article.id}").assertHasClickAction()
        compose.onNodeWithText("Select to check narration").assertIsDisplayed()
        compose.onNodeWithText("Sample library").assertDoesNotExist()
    }

    @Test fun backendSessionFailureExplainsRecoveryAndAllowsRetry() {
        var retries = 0
        compose.setContent {
            NewYorkerAudioTheme {
                MagazineGridScreen(LoadState.Failure(LoadFailure.SESSION_RECONNECT), { retries++ }, {})
            }
        }
        compose.onNodeWithText("The service needs to reconnect to The New Yorker. Please try again later.").assertIsDisplayed()
        compose.onNodeWithTag("retry").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, retries) }
    }

    @Test fun openingPlayerExplainsNarrationResolution() {
        compose.setContent {
            NewYorkerAudioTheme { PlayerScreen(LoadState.Loading, {}, {}) }
        }
        compose.onNodeWithText("Checking narration…").assertIsDisplayed()
        compose.onNodeWithTag("player-controls").assertDoesNotExist()
    }

    @Test fun missingAudioServiceDoesNotClaimArticleWasRemoved() {
        compose.setContent {
            NewYorkerAudioTheme { PlayerScreen(LoadState.Failure(LoadFailure.AUDIO_SERVICE_UNAVAILABLE), {}, {}) }
        }
        compose.onNodeWithText("The audio service is unavailable. Please try again later.").assertIsDisplayed()
        compose.onNodeWithText("This content is no longer available. Refresh the issue list and try again.").assertDoesNotExist()
        compose.onNodeWithTag("retry").assertIsDisplayed()
    }
}
