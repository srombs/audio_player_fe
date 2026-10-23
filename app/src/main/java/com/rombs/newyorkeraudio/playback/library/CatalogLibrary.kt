package com.rombs.newyorkeraudio.playback.library

import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.canonicalArticleUrl
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.Magazine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

const val LIBRARY_ROOT_ID = "ny:root"
const val LIBRARY_ISSUES_ID = "ny:issues"
const val LIBRARY_CONTINUE_ID = "ny:continue"
private const val ISSUE_PREFIX = "ny:issue:"
private const val FRESHNESS_MS = 5 * 60_000L
private const val MAX_ISSUES = 200
private const val MAX_CACHED_ISSUES = 24
private const val MAX_ARTICLES_PER_ISSUE = 100

fun issueMediaId(date: String): String = ISSUE_PREFIX + validIssueDate(date)
private fun validIssueDate(date: String): String {
    require(LocalDate.parse(date).toString() == date)
    return date
}

data class LibraryNode(val id: String, val title: String, val author: String? = null,
    val artworkUrl: String? = null, val playable: Boolean = false, val browsable: Boolean = !playable,
    val positionMs: Long? = null, val durationMs: Long? = null, val description: String? = null,
    val completed: Boolean = false)

fun libraryRoot() = LibraryNode(LIBRARY_ROOT_ID, "New Yorker Audio")
fun issuesFolder() = LibraryNode(LIBRARY_ISSUES_ID, "Issues")
fun continueFolder() = LibraryNode(LIBRARY_CONTINUE_ID, "Continue Listening")
private fun Magazine.toNode() = LibraryNode(issueMediaId(id), title, artworkUrl = coverImageUrl)
private fun Article.toNode() = LibraryNode(id, title, author, imageUrl, audioAvailable != false, browsable = false,
    description = description)

/** Legacy browsers use MAX_VALUE for unpaged reads. Bound the reply and avoid integer overflow. */
internal fun <T> libraryPage(items: List<T>, page: Int, pageSize: Int): List<T> {
    require(page >= 0 && pageSize > 0)
    val start = page.toLong() * pageSize
    if (start >= items.size) return emptyList()
    return items.subList(start.toInt(), minOf(items.size.toLong(), start + minOf(pageSize, MAX_ISSUES)).toInt())
}

interface CatalogStorage {
    fun read(): String?
    fun write(value: String)
}

data class CatalogChange(val parentId: String, val count: Int)

/** A service-scoped metadata tree. Browsing never calls the audio resolver. */
class CatalogLibrary(
    private val repository: NewYorkerRepository,
    private val scope: CoroutineScope,
    private val storage: CatalogStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var snapshot = restore()
    private val flights = mutableMapOf<String, Deferred<Unit>>()
    private val mutableChanges = MutableSharedFlow<CatalogChange>(extraBufferCapacity = 64)
    val changes = mutableChanges.asSharedFlow()

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<LibraryNode> {
        require(page >= 0 && pageSize > 0)
        val nodes = when {
            parentId == LIBRARY_ROOT_ID -> listOf(issuesFolder(), continueFolder())
            parentId == LIBRARY_ISSUES_ID -> issues().map { it.toNode() }
            parentId.startsWith(ISSUE_PREFIX) -> articles(validIssueDate(parentId.removePrefix(ISSUE_PREFIX)))
                .filter { it.audioAvailable != false }.map { it.toNode() }
            else -> throw NoSuchElementException("Unknown library folder")
        }
        return libraryPage(nodes, page, pageSize)
    }

    suspend fun item(id: String): LibraryNode = when {
        id == LIBRARY_ROOT_ID -> libraryRoot()
        id == LIBRARY_ISSUES_ID -> issuesFolder()
        id == LIBRARY_CONTINUE_ID -> continueFolder()
        id.startsWith(ISSUE_PREFIX) -> {
            val date = validIssueDate(id.removePrefix(ISSUE_PREFIX))
            issues().firstOrNull { it.id == date }?.toNode() ?: throw NoSuchElementException("Unknown issue")
        }
        else -> {
            val canonical = canonicalArticleUrl(id)
            val article = cachedArticle(canonical) ?: repository.getArticleMetadata(canonical)
            article.toNode()
        }
    }

    suspend fun cachedArticle(id: String): Article? = mutex.withLock {
        snapshot.issueArticles.asReversed().firstNotNullOfOrNull { entry -> entry.articles.firstOrNull { it.id == id } }
    }

    /** Metadata for the five newest issues, independent of phone browsing. Cached data works offline. */
    suspend fun recentArticles(): RecentArticles {
        val latest = try { issues().take(5) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return RecentArticles(emptyList(), error) }
        val loads = coroutineScope {
            latest.map { issue -> async {
                try { Result.success(articles(issue.id)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure<List<Article>>(error) }
            } }.awaitAll()
        }
        return RecentArticles(loads.flatMap { it.getOrNull().orEmpty() }.distinctBy { it.id },
            loads.firstNotNullOfOrNull { it.exceptionOrNull() as? Exception })
    }

    private suspend fun issues(): List<Magazine> {
        val cached = mutex.withLock { snapshot.issues?.let { it to snapshot.issuesUpdatedAt } }
        if (cached != null) {
            if (stale(cached.second)) refreshLater(LIBRARY_ISSUES_ID)
            return cached.first
        }
        refresh(LIBRARY_ISSUES_ID)
        return mutex.withLock { requireNotNull(snapshot.issues) }
    }

    private suspend fun articles(date: String): List<Article> {
        val cached = mutex.withLock { snapshot.issueArticles.firstOrNull { it.date == date } }
        if (cached != null) {
            if (stale(cached.updatedAt)) refreshLater(issueMediaId(date))
            return cached.articles
        }
        refresh(issueMediaId(date))
        return mutex.withLock { snapshot.issueArticles.first { it.date == date }.articles }
    }

    private fun stale(updatedAt: Long): Boolean = clock().let { it < updatedAt || it - updatedAt >= FRESHNESS_MS }

    private fun refreshLater(key: String) {
        scope.launch {
            try { refresh(key) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Stale metadata stays browsable during an outage. */ }
        }
    }

    private suspend fun refresh(key: String) {
        val work = mutex.withLock {
            val updatedAt = if (key == LIBRARY_ISSUES_ID) snapshot.issues?.let { snapshot.issuesUpdatedAt }
                else snapshot.issueArticles.firstOrNull { issueMediaId(it.date) == key }?.updatedAt
            if (updatedAt != null && !stale(updatedAt)) return
            flights[key] ?: scope.async<Unit>(start = CoroutineStart.LAZY) {
                try {
                    if (key == LIBRARY_ISSUES_ID) {
                        val result = repository.getMagazines().distinctBy { it.id }.sortedByDescending { it.date ?: it.id }.take(MAX_ISSUES)
                        result.forEach { validIssueDate(it.id) }
                        mutex.withLock {
                            snapshot = snapshot.copy(issues = result, issuesUpdatedAt = clock())
                            persist()
                            mutableChanges.tryEmit(CatalogChange(key, result.size))
                        }
                    } else {
                        val date = validIssueDate(key.removePrefix(ISSUE_PREFIX))
                        val result = repository.getArticles(date).distinctBy { it.id }.take(MAX_ARTICLES_PER_ISSUE)
                            .map { it.copy(audio = it.audio?.copy(audioUrl = null)) }
                        mutex.withLock {
                            val previous = snapshot.issueArticles.firstOrNull { it.date == date }?.articles?.associateBy { it.id }.orEmpty()
                            // A summary without narration information must not erase a resolver's
                            // confirmed answer. An explicit positive/negative summary can replace it.
                            val merged = result.map { summary ->
                                val known = previous[summary.id]
                                if (summary.audioAvailable == null && known?.audioAvailable != null) {
                                    summary.copy(audioAvailable = known.audioAvailable,
                                        audio = if (known.audioAvailable == false) null else known.audio?.copy(audioUrl = null))
                                } else summary
                            }
                            val entries = snapshot.issueArticles.filterNot { it.date == date } + CachedIssueArticles(date, clock(), merged)
                            snapshot = snapshot.copy(issueArticles = entries.takeLast(MAX_CACHED_ISSUES))
                            persist()
                            mutableChanges.tryEmit(CatalogChange(key, merged.count { it.audioAvailable != false }))
                        }
                    }
                } finally {
                    withContext(NonCancellable) { mutex.withLock { flights.remove(key) } }
                }
            }.also { flights[key] = it }
        }
        work.start()
        work.await()
    }

    suspend fun updateArticle(article: Article) {
        mutex.withLock {
            val affected = snapshot.issueArticles.filter { entry -> entry.articles.any { it.id == article.id } }
            if (affected.isEmpty()) return
            val metadata = article.copy(audio = article.audio?.copy(audioUrl = null))
            if (affected.all { entry -> entry.articles.first { it.id == article.id } == metadata }) return
            snapshot = snapshot.copy(issueArticles = snapshot.issueArticles.map { entry ->
                entry.copy(articles = entry.articles.map {
                    if (it.id == article.id) metadata else it
                })
            })
            persist()
            affected.forEach { entry ->
                val items = snapshot.issueArticles.first { it.date == entry.date }.articles
                mutableChanges.tryEmit(CatalogChange(issueMediaId(entry.date), items.count { it.audioAvailable != false }))
            }
        }
    }

    suspend fun completionChanged(id: String) {
        mutex.withLock {
            snapshot.issueArticles.filter { entry -> entry.articles.any { it.id == id } }.forEach { entry ->
                mutableChanges.tryEmit(CatalogChange(issueMediaId(entry.date), entry.articles.count { it.audioAvailable != false }))
            }
        }
    }

    suspend fun artworkChanged(url: String) {
        mutex.withLock {
            if (snapshot.issues?.any { it.coverImageUrl == url } == true) {
                mutableChanges.tryEmit(CatalogChange(LIBRARY_ISSUES_ID, snapshot.issues!!.size))
            }
            snapshot.issueArticles.filter { entry -> entry.articles.any { it.imageUrl == url } }.forEach { entry ->
                mutableChanges.tryEmit(CatalogChange(issueMediaId(entry.date), entry.articles.count { it.audioAvailable != false }))
            }
        }
    }

    /** Changing files is off the player's thread; the mutex preserves write order. */
    private suspend fun persist() = withContext(Dispatchers.IO) { storage.write(json.encodeToString(snapshot)) }

    private fun restore(): CatalogSnapshot = try {
        val saved = storage.read()?.let { json.decodeFromString<CatalogSnapshot>(it) }
        if (saved?.version != 1) CatalogSnapshot() else {
            saved.issues?.forEach { validIssueDate(it.id); require(it.title.isNotBlank()) }
            saved.issueArticles.forEach { entry ->
                validIssueDate(entry.date)
                entry.articles.forEach { require(canonicalArticleUrl(it.id) == it.id && it.title.isNotBlank()) }
            }
            saved.copy(issues = saved.issues?.take(MAX_ISSUES), issueArticles = saved.issueArticles.takeLast(MAX_CACHED_ISSUES).map {
                it.copy(articles = it.articles.take(MAX_ARTICLES_PER_ISSUE).map { article -> article.copy(audio = article.audio?.copy(audioUrl = null)) })
            })
        }
    } catch (_: Exception) { CatalogSnapshot() }
}

data class RecentArticles(val articles: List<Article>, val failure: Exception? = null)

@Serializable
private data class CatalogSnapshot(val version: Int = 1, val issuesUpdatedAt: Long = 0,
    val issues: List<Magazine>? = null, val issueArticles: List<CachedIssueArticles> = emptyList())

@Serializable
private data class CachedIssueArticles(val date: String, val updatedAt: Long, val articles: List<Article>)
