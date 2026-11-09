package com.rombs.newyorkeraudio.navigation

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rombs.newyorkeraudio.data.repository.TestNarration
import com.rombs.newyorkeraudio.data.repository.FakeNewYorkerRepository
import com.rombs.newyorkeraudio.ui.theme.NewYorkerAudioTheme
import com.rombs.newyorkeraudio.ui.articles.ArticleFilterPreferences
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val compose = createComposeRule()
    private lateinit var restoration: StateRestorationTester
    private val url = requireNotNull(TestNarration.article.articleUrl)

    @Before fun setup() {
        restoration = StateRestorationTester(compose)
        val repository = FakeNewYorkerRepository(TestNarration.article.copy(id = url))
        val filters = ArticleFilterPreferences()
        restoration.setContent {
            NewYorkerAudioTheme { AppNavigation(repository, articleFilterPreferences = filters) }
        }
    }

    @Test
    fun issueCoversShareATwoColumnRow() {
        compose.onNodeWithTag("cover:2026-10-12", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("cover:2026-10-05", useUnmergedTree = true).assertIsDisplayed()
        val left = compose.onNodeWithTag("issue:2026-10-12").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("issue:2026-10-05").fetchSemanticsNode().boundsInRoot
        assertEquals(left.top, right.top, 1f)
        assertTrue(left.right < right.left)
    }

    @Test
    fun selectingIdsAndGoingBackKeepsTheCorrectIssueAndArticle() {
        compose.onNodeWithTag("issue:2026-10-12").performClick()
        compose.onNodeWithText("October 12, 2026").assertIsDisplayed()
        compose.onNodeWithTag("articles:2026-10-12").performScrollToNode(hasTestTag("article:2026-10-12-city"))
        compose.onNodeWithTag("article:2026-10-12-city").performClick()
        compose.onNodeWithTag("player:2026-10-12-city").assertIsDisplayed()
        compose.onNodeWithTag("artwork:2026-10-12-city").assertIsDisplayed()
        compose.onNodeWithTag("player:2026-10-12-city")
            .performScrollToNode(hasText("The City After Midnight"))
        compose.onNodeWithText("The City After Midnight").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("articles:2026-10-12").performScrollToNode(hasText("October 12, 2026"))
        compose.onNodeWithText("October 12, 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("issue:2026-10-05").performClick()
        compose.onNodeWithTag("article:2026-10-05-river").assertIsDisplayed()
    }

    @Test
    fun articleRowsShowArtworkAuthorAndNarrationDuration() {
        compose.onNodeWithTag("issue:2026-10-12").performClick()
        compose.onNodeWithTag("articles:2026-10-12").performScrollToNode(hasTestTag("article:2026-10-12-city"))
        compose.onNodeWithTag("thumbnail:2026-10-12-city", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Alex Morgan").assertIsDisplayed()
        compose.onNodeWithText("27 min · Narrated").assertIsDisplayed()
    }

    @Test
    fun articlesWithoutNarrationDoNotNavigateToThePlayer() {
        compose.onNodeWithTag("issue:2026-10-12").performClick()
        compose.onNodeWithTag("articles:2026-10-12")
            .performScrollToNode(hasTestTag("article:2026-10-12-notes"))
        compose.onNodeWithTag("article:2026-10-12-notes").assertHasNoClickAction()
    }

    @Test
    fun savedNavigationRestoresTheSelectedArticleByUrl() {
        compose.onNodeWithTag("issue:2026-10-12").performClick()
        compose.onNodeWithTag("article:$url").performClick()
        compose.onNodeWithTag("player:$url").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("player:$url").assertIsDisplayed()
        compose.onNodeWithTag("player:$url").performScrollToNode(hasText(TestNarration.article.title))
        compose.onNodeWithText(TestNarration.article.title).assertIsDisplayed()
    }

    @Test
    fun listenedFiltersAreSharedAcrossIssuesAndSurviveNavigationRestoration() {
        compose.onNodeWithTag("issue:2026-10-12").performClick()
        for (filter in listOf("UNLISTENED", "LISTENED", "ALL")) {
            compose.onNodeWithTag("filter:$filter").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("issue:2026-10-05").performClick()
            compose.onNodeWithTag("filter:$filter").assertIsSelected()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("filter:$filter").assertIsSelected()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("issue:2026-10-12").performClick()
            compose.onNodeWithTag("filter:$filter").assertIsSelected()
        }
    }
}
