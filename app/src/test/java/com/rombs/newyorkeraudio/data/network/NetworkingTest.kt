package com.rombs.newyorkeraudio.data.network

import com.rombs.newyorkeraudio.data.repository.NetworkNewYorkerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkingTest {
    @Test fun `client uses configured timeouts`() {
        val client = BackendClient.createHttpClient()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(15_000, client.writeTimeoutMillis)
        assertEquals(90_000, client.callTimeoutMillis)
    }

    @Test fun `all endpoints use relative paths and audio body contains only url`() = runBlocking {
        val transport = Transport { request -> when (request.url.encodedPath) {
            "/api/issues" -> ISSUES
            "/api/issues/2026-10-12/articles" -> ARTICLES
            "/api/articles/audio" -> AUDIO
            "/api/auth/status" -> """{"status":"missing","action":"reconnect","reason":"session_missing"}"""
            "/api/health" -> """{"status":"ok"}"""
            else -> error("Unexpected endpoint")
        } }
        try {
            val api = transport.api
            api.getIssues()
            api.getIssues(2026)
            api.getIssueArticles(DATE)
            api.resolveAudio(AudioRequest(URL))
            val auth = api.getAuthStatus()
            assertNull(auth.authenticated)
            assertEquals("reconnect", auth.action)
            assertEquals("ok", api.getHealth().status)
            assertNull(transport.requests[0].url.query)
            assertEquals("year=2026", transport.requests[1].url.query)
            val audioRequest = transport.requests[3]
            assertEquals("POST", audioRequest.method)
            val buffer = Buffer()
            audioRequest.body!!.writeTo(buffer)
            assertEquals(Json.parseToJsonElement("""{"url":"$URL"}"""), Json.parseToJsonElement(buffer.readUtf8()))
            assertEquals("application/json; charset=utf-8", audioRequest.body!!.contentType().toString())
            assertTrue(transport.requests.all { it.header("Authorization") == null && it.header("Cookie") == null })
        } finally { transport.close() }
    }

    @Test fun `json ignores added fields while preserving nullable audio`() {
        val result = BackendClient.json.decodeFromString<ArticleAudioDto>(
            """{"article":{"id":"be-id","title":"Title","url":"$URL","future":true},"audio":null,"future":{}}""",
        )
        assertNull(result.audio)
        assertTrue(result.article.authors.isEmpty())
    }

    @Test fun `repository maps issues and warms header cache from article response`() = runBlocking {
        val transport = Transport { if (it.url.encodedPath.endsWith("articles")) ARTICLES else ISSUES }
        try {
            val repository = NetworkNewYorkerRepository(transport.api)
            val magazines = repository.getMagazines()
            assertEquals(DATE, magazines.single().id)
            assertEquals("October 12, 2026", magazines.single().title)
            assertEquals("https://media.newyorker.com/cover.jpg", magazines.single().coverImageUrl)
            repository.getArticles(DATE)
            assertEquals(DATE, repository.getMagazine(DATE).date)
            assertEquals(2, transport.requests.size)
        } finally { transport.close() }
    }

    @Test fun `cold history article metadata is available without resolving or fetching the network`() = runBlocking {
        val saved = com.rombs.newyorkeraudio.data.repository.TestNarration.article.copy(id = URL, articleUrl = URL,
            description = "Historical description", audio = com.rombs.newyorkeraudio.domain.model.AudioInfo(123000, null))
        val transport = Transport { error("History metadata lookup must stay offline") }
        try {
            val repository = NetworkNewYorkerRepository(transport.api, historyMetadata = { id -> saved.takeIf { it.id == id } })
            assertEquals(saved, repository.getArticleMetadata(URL))
            assertTrue(transport.requests.isEmpty())
        } finally { transport.close() }
    }

    @Test fun `summary maps author artwork section and all narration hint states`() = runBlocking {
        val transport = Transport { ARTICLES }
        try {
            val articles = NetworkNewYorkerRepository(transport.api).getArticles(DATE)
            assertEquals("List author", articles[0].author)
            assertEquals("List description", articles[0].description)
            assertEquals("Books", articles[0].section)
            assertEquals("https://media.newyorker.com/story.jpg", articles[0].imageUrl)
            assertEquals(URL, articles[0].id)
            assertNotNull(articles[0].audio)
            assertEquals(true, articles[0].audioAvailable)
            assertNull(articles[0].audio!!.audioUrl)
            assertNull(articles[0].audio!!.durationMs)
            assertNull(articles[1].audio)
            assertEquals(false, articles[1].audioAvailable)
            assertNotNull(articles[2].audio)
            assertNull(articles[2].audioAvailable)
            assertEquals("Fiction", articles[2].section)
            assertEquals(1, transport.requests.size) // Listing never resolves audio.
        } finally { transport.close() }
    }

    @Test fun `metadata lookup uses catalog without resolving audio`() = runBlocking {
        val transport = Transport { ARTICLES }
        try {
            val repository = NetworkNewYorkerRepository(transport.api)
            repository.getArticles(DATE)
            val article = repository.getArticleMetadata(URL)
            assertEquals(URL, article.id)
            assertNull(article.audio?.audioUrl)
            assertEquals(1, transport.requests.size)
            assertTrue(transport.requests.all { it.method == "GET" })
        } finally { transport.close() }
    }

    @Test fun `explicit refresh bypasses backend cache and catalog never retains source`() = runBlocking {
        val transport = Transport { AUDIO }
        try {
            val repository = NetworkNewYorkerRepository(transport.api)
            repository.resolveAudio(URL, refresh = true)
            val body = Buffer()
            transport.requests.single().body!!.writeTo(body)
            assertEquals(AudioRequest(URL, refresh = true), BackendClient.json.decodeFromString<AudioRequest>(body.readUtf8()))
            assertNull(repository.getArticleMetadata(URL).audio?.audioUrl)
            assertEquals(123_000L, repository.getArticleMetadata(URL).audio?.durationMs)
            assertEquals(1, transport.requests.size)
        } finally { transport.close() }
    }

    @Test fun `resolver preserves url identity and summary metadata`() = runBlocking {
        val transport = Transport { if (it.method == "POST") AUDIO else ARTICLES }
        try {
            val repository = NetworkNewYorkerRepository(transport.api)
            repository.getArticles(DATE)
            val article = repository.getArticle(URL)
            assertEquals(URL, article.id)
            assertNotEquals("backend-article-id", article.id)
            assertEquals("Resolved title", article.title)
            assertEquals("Resolved description", article.description)
            assertEquals("Author One, Author Two", article.author)
            assertEquals("Books", article.section)
            assertEquals("https://media.newyorker.com/story.jpg", article.imageUrl)
            assertEquals(123_000L, article.audio!!.durationMs)
            assertEquals(MP3, article.audio!!.audioUrl)
        } finally { transport.close() }
    }

    @Test fun `restored url id resolves without browsing and repeated lookup refreshes stream`() = runBlocking {
        val transport = Transport { AUDIO }
        try {
            val repository = NetworkNewYorkerRepository(transport.api)
            val article = repository.getArticle(URL)
            assertEquals(URL, article.id)
            assertNotNull(article.audio?.audioUrl)
            assertNull(article.imageUrl)
            repository.getArticle(URL)
            assertTrue(transport.requests.all { it.method == "POST" })
            assertEquals(2, transport.requests.size)
        } finally { transport.close() }
    }

    @Test fun `unnarrated response clears summary hint and keeps author fallback`() = runBlocking {
        val transport = Transport { if (it.method == "POST")
            """{"article":{"id":"be-id","title":"Title","url":"$URL"},"audio":null}""" else ARTICLES }
        try {
            val repository = NetworkNewYorkerRepository(transport.api)
            repository.getArticles(DATE)
            val article = repository.getArticle(URL)
            assertNull(article.audio)
            assertEquals("List author", article.author)
            assertEquals("List description", article.description)
        } finally { transport.close() }
    }

    @Test fun `tracking urls normalize to stable post body`() = runBlocking {
        val transport = Transport { AUDIO }
        try {
            val article = NetworkNewYorkerRepository(transport.api).getArticle(
                URL.replace("www.newyorker.com", "newyorker.com") + "/?utm_source=test#top",
            )
            assertEquals(URL, article.id)
            val buffer = Buffer()
            transport.requests.single().body!!.writeTo(buffer)
            assertEquals(AudioRequest(URL), BackendClient.json.decodeFromString<AudioRequest>(buffer.readUtf8()))
        } finally { transport.close() }
    }

    @Test fun `backend session error retains status code and recovery action`() = runBlocking {
        val transport = Transport(503) {
            """{"detail":{"code":"newyorker_session_missing","action":"reconnect"}}"""
        }
        try {
            val failure = failure { NetworkNewYorkerRepository(transport.api).getMagazines() }
            assertEquals(BackendFailureKind.HTTP, failure.kind)
            assertEquals(503, failure.httpStatus)
            assertEquals("newyorker_session_missing", failure.code)
            assertEquals("reconnect", failure.action)
        } finally { transport.close() }
    }

    @Test fun `validation arrays and non-json server errors remain http errors`() = runBlocking {
        for (body in listOf("""{"detail":[{"msg":"invalid"}]}""", "<html>Gateway error</html>")) {
            val transport = Transport(422) { body }
            try {
                val error = failure { NetworkNewYorkerRepository(transport.api).getArticle(URL) }
                assertEquals(BackendFailureKind.HTTP, error.kind)
                assertEquals(422, error.httpStatus)
                assertNull(error.code)
            } finally { transport.close() }
        }
    }

    @Test fun `missing audio route is not classified as a removed article`() = runBlocking {
        val transport = Transport(404) { """{"detail":"Not Found"}""" }
        try {
            val error = failure { NetworkNewYorkerRepository(transport.api).getArticle(URL) }
            assertEquals(404, error.httpStatus)
            assertEquals("audio_endpoint_unavailable", error.code)
            assertEquals("contact_operator", error.action)
        } finally { transport.close() }
    }

    @Test fun `structured article not found error preserves the backend code`() = runBlocking {
        val transport = Transport(404) { """{"detail":{"code":"article_not_found","action":"refresh_issue_list"}}""" }
        try {
            val error = failure { NetworkNewYorkerRepository(transport.api).getArticle(URL) }
            assertEquals("article_not_found", error.code)
            assertEquals("refresh_issue_list", error.action)
        } finally { transport.close() }
    }

    @Test fun `missing issue still uses normal not found classification`() = runBlocking {
        val transport = Transport(404) { """{"detail":{"code":"issue_not_found","action":"refresh_issue_list"}}""" }
        try {
            val error = failure { NetworkNewYorkerRepository(transport.api).getArticles(DATE) }
            assertEquals(404, error.httpStatus)
            assertEquals("issue_not_found", error.code)
        } finally { transport.close() }
    }

    @Test fun `connection failures and timeouts are distinguished`() = runBlocking {
        for ((error, kind) in listOf(IOException("offline") to BackendFailureKind.CONNECTION,
            SocketTimeoutException("timed out") to BackendFailureKind.TIMEOUT)) {
            val transport = Transport { throw error }
            try {
                assertEquals(kind, failure { NetworkNewYorkerRepository(transport.api).getMagazines() }.kind)
            } finally { transport.close() }
        }
    }

    @Test fun `malformed successful response is rejected`() = runBlocking {
        val transport = Transport { """{"issues":[]}""" }
        try {
            assertEquals(BackendFailureKind.INVALID_RESPONSE,
                failure { NetworkNewYorkerRepository(transport.api).getMagazines() }.kind)
        } finally { transport.close() }
    }

    @Test fun `resolver response cannot switch article identity`() = runBlocking {
        val transport = Transport { AUDIO.replace(URL, "$URL-other") }
        try {
            assertEquals(BackendFailureKind.INVALID_RESPONSE,
                failure { NetworkNewYorkerRepository(transport.api).getArticle(URL) }.kind)
        } finally { transport.close() }
    }

    @Test fun `invalid audio duration and insecure stream are rejected`() = runBlocking {
        for (body in listOf(AUDIO.replace("123000", "0"), AUDIO.replace(MP3, MP3.replace("https:", "http:")))) {
            val transport = Transport { body }
            try {
                assertEquals(BackendFailureKind.INVALID_RESPONSE,
                    failure { NetworkNewYorkerRepository(transport.api).getArticle(URL) }.kind)
            } finally { transport.close() }
        }
    }

    @Test fun `invalid article identifier makes no request`() = runBlocking {
        val transport = Transport { AUDIO }
        try {
            for (url in listOf("old-opaque-id", "https://example.com/article/story", "http://www.newyorker.com/books/story")) {
                try {
                    NetworkNewYorkerRepository(transport.api).getArticle(url)
                    fail("Expected invalid identifier")
                } catch (_: IllegalArgumentException) { }
            }
            assertTrue(transport.requests.isEmpty())
        } finally { transport.close() }
    }

    @Test fun `cancelled coroutine cancels retrofit call`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = BackendClient.createHttpClient().newBuilder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            })
            .addInterceptor { chain ->
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                response(chain.request(), 200, ISSUES)
            }.build()
        try {
            val repository = NetworkNewYorkerRepository(BackendClient.create(BASE, client))
            val job = launch { repository.getMagazines() }
            withTimeout(5_000) { entered.await() }
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertEquals(0L, cancelled.count)
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun failure(block: suspend () -> Any?): BackendFailure {
        try { block() } catch (error: BackendFailure) { return error }
        throw AssertionError("Expected BackendFailure")
    }

    private class Transport(status: Int = 200, body: (Request) -> String) {
        val requests = mutableListOf<Request>()
        private val client = BackendClient.createHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            response(request, status, body(request))
        }.build()
        val api = BackendClient.create(BASE, client)
        fun close() {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    companion object {
        private const val BASE = "https://backend.example/api/"
        private const val DATE = "2026-10-12"
        private const val URL = "https://www.newyorker.com/magazine/2026/10/12/example-story"
        private const val MP3 = "https://audio-resources.cnevids.com/audio/narrated/example.mp3"
        private val ISSUE = """{"issue_date":"$DATE","label":"October 12, 2026","url":"https://www.newyorker.com/magazine/2026/10/12","cover_image_url":"https://media.newyorker.com/cover.jpg"}"""
        private val ISSUES = """{"updated_at":"2026-10-12T00:00:00Z","history_complete":false,"issues":[$ISSUE]}"""
        private val ARTICLES = """{"issue":$ISSUE,"fetched_at":"2026-10-12T00:00:00Z","articles":[{"title":"List title","url":"$URL","byline":"List author","description":"List description","section":"Books","image_url":"https://media.newyorker.com/story.jpg","audio_available":true},{"title":"No narration","url":"$URL-no-audio","audio_available":false},{"title":"Unknown narration","url":"$URL-unknown","rubric":"Fiction","audio_available":null}]}"""
        private val AUDIO = """{"article":{"id":"backend-article-id","title":"Resolved title","description":"Resolved description","url":"$URL","authors":["Author One","Author Two"]},"audio":{"audio_id":"narration-id","duration_ms":123000,"audio_url":"$MP3","content_type":"audio/mpeg","verified_at":"2026-10-12T00:00:00Z"}}"""
        private fun response(request: Request, status: Int, body: String) = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
}
