package com.rombs.newyorkeraudio.playback

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rombs.newyorkeraudio.data.network.BackendClient
import com.rombs.newyorkeraudio.data.repository.NetworkNewYorkerRepository
import com.rombs.newyorkeraudio.playback.artwork.ArtworkCache
import com.rombs.newyorkeraudio.playback.library.CatalogLibrary
import com.rombs.newyorkeraudio.playback.library.CatalogStorage
import com.rombs.newyorkeraudio.playback.library.issueFilterMediaId
import com.rombs.newyorkeraudio.playback.library.toLibraryMediaItem
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.player.PlayerScreen
import com.rombs.newyorkeraudio.ui.theme.NewYorkerAudioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SummaryDurationScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun articlePageShowsSummaryDurationBeforePlay() = runBlocking {
        val transport = SummaryTransport()
        try {
            val article = transport.repository.getArticles(DATE).single()
            compose.setContent {
                NewYorkerAudioTheme { PlayerScreen(LoadState.Content(article), {}, {}) }
            }
            val page = compose.onNodeWithTag("player:$URL")
            page.performScrollToNode(hasText("10 min · Narrated"))
            compose.onNodeWithText("10 min · Narrated").assertIsDisplayed()
            page.performScrollToNode(hasTestTag("duration"))
            compose.onNodeWithTag("duration").assertTextEquals("10:57")
            compose.onNodeWithTag("seek").assertIsNotEnabled()
            assertEquals(listOf("GET"), transport.methods)
            assertNull(article.audio?.audioUrl)
        } finally { transport.close() }
    }

    @Test fun androidAutoArticleRowShowsSummaryDurationBeforePlay() = runBlocking {
        val transport = SummaryTransport()
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val catalog = CatalogLibrary(transport.repository, scope, object : CatalogStorage {
                override fun read(): String? = null
                override fun write(value: String) = Unit
            })
            val row = catalog.children(issueFilterMediaId(DATE, ArticleListenFilter.ALL), 0, 100).single()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val item = row.toLibraryMediaItem(ArtworkCache(context))
            assertEquals("10 min · Article description", item.mediaMetadata.subtitle.toString())
            assertEquals("10 min · Article description", item.mediaMetadata.description.toString())
            assertEquals(URL, item.mediaId)
            assertNull(item.localConfiguration)
            assertEquals(listOf("GET"), transport.methods)
        } finally { scope.cancel(); transport.close() }
    }

    private class SummaryTransport {
        val methods = mutableListOf<String>()
        private val client = BackendClient.createHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET" && request.url.encodedPath == "/issues/$DATE/articles")
            methods.add(request.method)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(RESPONSE.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = NetworkNewYorkerRepository(BackendClient.create("https://backend.example/", client))
        fun close() {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    companion object {
        private const val DATE = "2026-11-16"
        private const val URL = "https://www.newyorker.com/magazine/2026/11/16/example-story"
        private val RESPONSE = """{"version":1,"issue":{"issue_date":"$DATE","label":"November 16, 2026","url":"https://www.newyorker.com/magazine/2026/11/16"},"fetched_at":"2026-11-12T19:39:42Z","articles":[{"title":"Article title","url":"$URL","description":"Article description","audio_available":true,"duration_ms":657000}]}"""
    }
}
