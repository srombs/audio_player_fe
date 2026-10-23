package com.rombs.newyorkeraudio.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.playback.ListeningHistoryCache
import com.rombs.newyorkeraudio.playback.ProgressStorage
import com.rombs.newyorkeraudio.ui.articles.*
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.magazines.MagazineGridScreen
import com.rombs.newyorkeraudio.ui.theme.NewYorkerAudioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningHistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val article = TestNarration.article.copy(id = TestNarration.article.articleUrl!!, description = "Saved history description.")
    private class Storage : ProgressStorage {
        private var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    @Test fun activeUnlistenedFilterUpdatesWhenPlaybackCompletesWithoutRefreshingTheIssue() {
        val history = ListeningHistoryCache(Storage())
        var selected: String? = null
        compose.setContent {
            val entries by history.entries.collectAsState()
            NewYorkerAudioTheme {
                ArticleListScreen(LoadState.Content(ArticleListContent(Magazine("2026-10-12", "Issue", null, null), listOf(article))),
                    {}, {}, { selected = it }, listenedIds = entries.map { it.article.id }.toSet())
            }
        }
        compose.onNodeWithTag("filter:UNLISTENED").performClick()
        compose.onNodeWithTag("article:${article.id}").assertExists()
        compose.runOnIdle { history.markCompleted(article) }
        compose.onNodeWithTag("article:${article.id}").assertDoesNotExist()
        compose.onNodeWithText("No articles match this filter.").assertIsDisplayed()
        compose.onNodeWithTag("filter:LISTENED").performClick()
        compose.onNodeWithTag("articles:2026-10-12").performScrollToNode(hasTestTag("article:${article.id}"))
        compose.onNodeWithTag("listened:${article.id}", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("article:${article.id}").performClick()
        compose.runOnIdle { assertEquals(article.id, selected) }
    }

    @Test fun restoredHistoryDisplaysMetadataAndLetsTheUserOpenAnArticle() {
        val storage = Storage()
        ListeningHistoryCache(storage) { 1000 }.markCompleted(article)
        val restored = ListeningHistoryCache(storage)
        var selected: String? = null
        compose.setContent {
            val entries by restored.entries.collectAsState()
            NewYorkerAudioTheme { ListeningHistoryScreen(entries, {}, { selected = it }) }
        }
        compose.onNodeWithTag("article:${article.id}").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(article.id, selected) }
        compose.onNodeWithText(article.description!!, useUnmergedTree = true).assertExists()
    }

    @Test fun historyActionIsAvailableEvenWhenIssueLoadingFailsOffline() {
        var opened = false
        compose.setContent {
            NewYorkerAudioTheme { MagazineGridScreen(LoadState.Error, {}, {}, onHistory = { opened = true }) }
        }
        compose.onNodeWithTag("toolbar-history").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
