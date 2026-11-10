package com.rombs.newyorkeraudio.playback.library

import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.canonicalArticleUrl
import com.rombs.newyorkeraudio.data.repository.mergeCachedNarration
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.playback.ArticleListenFilter
import com.rombs.newyorkeraudio.playback.filterListenedArticles
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

const val LIBRARY_ROOT_ID = "ny:root"
const val LIBRARY_ISSUES_ID = "ny:issues"
const val LIBRARY_CONTINUE_ID = "ny:continue"
private const val ISSUE_PREFIX = "ny:issue:"
private const val FILTER_PREFIX = "ny:filter:"
private const val FRESHNESS_MS = 5 * 60_000L
private const val MAX_ISSUES = 200
private const val MAX_CACHED_ISSUES = 24
private const val MAX_ARTICLES_PER_ISSUE = 100
private const val HYDRATION_BATCH_SIZE = 2
private const val MAX_PAGE_HYDRATION = 10

fun issueMediaId(date: String): String = ISSUE_PREFIX + validIssueDate(date)
fun issueFilterMediaId(date: String, filter: ArticleListenFilter): String =
    "$FILTER_PREFIX${validIssueDate(date)}:${filter.name}"

private data class IssueFilterFolder(val date: String, val filter: ArticleListenFilter) {
    val id: String get() = issueFilterMediaId(date, filter)
    val title: String get() = when (filter) {
        ArticleListenFilter.ALL -> "All"
        ArticleListenFilter.UNLISTENED -> "Unlistened"
        ArticleListenFilter.LISTENED -> "Listened"
    }
}

private fun parseFilterFolder(id: String): IssueFilterFolder {
    val parts = id.removePrefix(FILTER_PREFIX).split(':')
    require(parts.size == 2) { "Invalid filter folder" }
    return IssueFilterFolder(validIssueDate(parts[0]), ArticleListenFilter.valueOf(parts[1]))
}

private fun validIssueDate(date: String): String {
    require(LocalDate.parse(date).toString() == date)
    return date
}

data class IssueListeningSummary(val listened: Int, val total: Int) {
    val label: String get() = "$listened/$total listened"
}

data class LibraryNode(val id: String, val title: String, val author: String? = null,
    val artworkUrl: String? = null, val playable: Boolean = false, val browsable: Boolean = !playable,
    val positionMs: Long? = null, val durationMs: Long? = null, val description: String? = null,
    val completed: Boolean = false, val issueProgress: IssueListeningSummary? = null) {
    val displayTitle: String
        get() = issueProgress?.let { summary ->
            val suffix = " · ${summary.label}"
            title.take(256 - suffix.length) + suffix
        } ?: title
    val displayDescription: String?
        get() = listOfNotNull(
            issueProgress?.label,
            if (completed) "Listened" else null,
            durationMs?.takeIf { it > 0 && !browsable }?.let { "${(it / 60_000).coerceAtLeast(1)} min" },
            description?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }
}

fun libraryRoot() = LibraryNode(LIBRARY_ROOT_ID, "New Yorker Audio")
fun issuesFolder() = LibraryNode(LIBRARY_ISSUES_ID, "Issues")
fun continueFolder() = LibraryNode(LIBRARY_CONTINUE_ID, "Continue Listening")
private fun Magazine.toNode(summary: IssueListeningSummary?) = LibraryNode(issueMediaId(id), title,
    artworkUrl = coverImageUrl, issueProgress = summary,
    description = if (summary == null) "Progress unavailable" else null)
private fun Article.toNode() = LibraryNode(id, title, author, imageUrl, audioAvailable != false, browsable = false,
    durationMs = audio?.durationMs?.takeIf { it > 0 }, description = description)

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
    initialListenedIds: Set<String> = emptySet(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var snapshot = restore()
    private var listenedIds = initialListenedIds.toSet()
    private val flights = mutableMapOf<String, Deferred<Unit>>()
    private var narrationRevision = 0L
    private val inFlightNarration = mutableMapOf<String, Pair<Long, Article>>()
    private val pendingHydration = linkedSetOf<String>()
    private var hydrating = false
    private val mutableChanges = MutableSharedFlow<CatalogChange>(extraBufferCapacity = 64)
    val changes = mutableChanges.asSharedFlow()

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<LibraryNode> {
        require(page >= 0 && pageSize > 0)
        val nodes = when {
            parentId == LIBRARY_ROOT_ID -> listOf(issuesFolder(), continueFolder())
            parentId == LIBRARY_ISSUES_ID -> {
                val issues = issues()
                hydrateLater((issues.take(5) + libraryPage(issues, page, pageSize).take(MAX_PAGE_HYDRATION)).map { it.id })
                mutex.withLock { issues.map { it.toNode(summary(it.id)) } }
            }
            parentId.startsWith(FILTER_PREFIX) -> {
                val folder = parseFilterFolder(parentId)
                val articles = articles(folder.date).filter { it.audioAvailable != false }
                mutex.withLock { filterListenedArticles(articles, listenedIds, folder.filter).map { it.toNode() } }
            }
            parentId.startsWith(ISSUE_PREFIX) -> {
                val date = validIssueDate(parentId.removePrefix(ISSUE_PREFIX))
                articles(date)
                mutex.withLock { ArticleListenFilter.entries.map { filterNode(IssueFilterFolder(date, it)) } }
            }
            else -> throw NoSuchElementException("Unknown library folder")
        }
        return libraryPage(nodes, page, pageSize)
    }

    suspend fun item(id: String): LibraryNode = when {
        id == LIBRARY_ROOT_ID -> libraryRoot()
        id == LIBRARY_ISSUES_ID -> issuesFolder()
        id == LIBRARY_CONTINUE_ID -> continueFolder()
        id.startsWith(FILTER_PREFIX) -> {
            val folder = parseFilterFolder(id)
            require(issues().any { it.id == folder.date }) { "Unknown issue" }
            mutex.withLock { filterNode(folder) }
        }
        id.startsWith(ISSUE_PREFIX) -> {
            val date = validIssueDate(id.removePrefix(ISSUE_PREFIX))
            val issue = issues().firstOrNull { it.id == date } ?: throw NoSuchElementException("Unknown issue")
            mutex.withLock { issue.toNode(summary(date)) }
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

    /** Called under the mutex so every filter title uses the same whole-issue summary. */
    private fun filterNode(folder: IssueFilterFolder): LibraryNode {
        val progress = summary(folder.date)
        return LibraryNode(folder.id, folder.title, issueProgress = progress,
            description = if (progress == null) "Progress unavailable" else null)
    }

    /** Called under the catalog mutex so a page uses one membership/history snapshot. */
    private fun summary(date: String): IssueListeningSummary? = snapshot.issueMembership
        .firstOrNull { it.date == date }?.let { membership ->
            val eligible = membership.articles.filterValues { it != false }.keys
            IssueListeningSummary(eligible.count { it in listenedIds }, eligible.size)
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
            if (stale(cached.updatedAt) || mutex.withLock { snapshot.issueMembership.none { it.date == date } }) {
                refreshLater(issueMediaId(date))
            }
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

    /** Prioritize recent issues, then a small requested page; never fan out over the whole catalog. */
    private fun hydrateLater(dates: List<String>) {
        scope.launch {
            mutex.withLock {
                dates.filter { date -> snapshot.issueMembership.firstOrNull { it.date == date }
                    ?.let { stale(it.updatedAt) } ?: true }.forEach { pendingHydration.add(it) }
                if (hydrating) return@launch
                hydrating = true
            }
            while (true) {
                val batch = mutex.withLock {
                    val next = pendingHydration.take(HYDRATION_BATCH_SIZE)
                    pendingHydration.removeAll(next.toSet())
                    if (next.isEmpty()) { hydrating = false; return@launch }
                    next
                }
                coroutineScope {
                    batch.map { date -> async {
                        try { refresh(issueMediaId(date)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* A cold failure leaves progress unavailable; cached totals stay usable. */ }
                    } }.awaitAll()
                }
            }
        }
    }

    private suspend fun refresh(key: String) {
        val work = mutex.withLock {
            val updatedAt = if (key == LIBRARY_ISSUES_ID) snapshot.issues?.let { snapshot.issuesUpdatedAt }
                else snapshot.issueArticles.firstOrNull { issueMediaId(it.date) == key }?.updatedAt
            val missingMembership = key != LIBRARY_ISSUES_ID && snapshot.issueMembership.none { issueMediaId(it.date) == key }
            if (updatedAt != null && !stale(updatedAt) && !missingMembership) return
            flights[key] ?: scope.async<Unit>(start = CoroutineStart.LAZY) {
                try {
                    val startedAtRevision = mutex.withLock { narrationRevision }
                    val changes = if (key == LIBRARY_ISSUES_ID) {
                        val result = repository.getMagazines().distinctBy { it.id }.sortedByDescending { it.date ?: it.id }.take(MAX_ISSUES)
                        result.forEach { validIssueDate(it.id) }
                        mutex.withLock {
                            snapshot = snapshot.copy(issues = result, issuesUpdatedAt = clock(),
                                issueMembership = retainMembership(snapshot.issueMembership, result))
                            persist()
                            listOf(CatalogChange(key, result.size))
                        }
                    } else {
                        val date = validIssueDate(key.removePrefix(ISSUE_PREFIX))
                        val result = repository.getArticles(date).distinctBy { it.id }
                            .map { it.copy(audio = it.audio?.copy(audioUrl = null)) }
                        mutex.withLock {
                            val previous = snapshot.issueArticles.flatMap { it.articles }.associateBy { it.id }
                            val previousMembers = snapshot.issueMembership.flatMap { it.articles.entries }
                                .filter { it.value != null }.associate { it.key to it.value }
                            val merged = result.map { incoming ->
                                val cached = previous[incoming.id] ?: incoming.copy(audio = null, audioAvailable = previousMembers[incoming.id])
                                val metadata = incoming.mergeCachedNarration(cached)
                                val newer = inFlightNarration[incoming.id]?.takeIf { it.first > startedAtRevision }?.second
                                if (newer == null) metadata else metadata.copy(audio = newer.audio, audioAvailable = newer.audioAvailable)
                            }
                            val affected = updateNarration(merged.associateBy { it.id }) - date
                            val entries = snapshot.issueArticles.filterNot { it.date == date } +
                                CachedIssueArticles(date, clock(), merged.take(MAX_ARTICLES_PER_ISSUE))
                            val membership = CachedIssueMembership(date, clock(), merged.associate { it.id to it.audioAvailable })
                            snapshot = snapshot.copy(issueArticles = entries.takeLast(MAX_CACHED_ISSUES),
                                issueMembership = retainMembership(snapshot.issueMembership.filterNot { it.date == date } + membership, snapshot.issues))
                            persist()
                            issueChanges(date) + affected.flatMap { issueChanges(it) } +
                                CatalogChange(LIBRARY_ISSUES_ID, snapshot.issues?.size ?: 0)
                        }
                    }
                    // Consumers may read the catalog while processing a notification.
                    changes.forEach { mutableChanges.emit(it) }
                } finally {
                    withContext(NonCancellable) { mutex.withLock {
                        flights.remove(key)
                        if (flights.isEmpty()) inFlightNarration.clear()
                    } }
                }
            }.also { flights[key] = it }
        }
        work.start()
        work.await()
    }

    suspend fun updateArticle(article: Article) {
        val changes = mutex.withLock {
            val metadata = article.copy(audio = article.audio?.copy(audioUrl = null))
            if (flights.isNotEmpty()) inFlightNarration[article.id] = (++narrationRevision) to metadata
            val affected = snapshot.issueMembership.filter { article.id in it.articles }.map { it.date }.toSet() +
                snapshot.issueArticles.filter { entry -> entry.articles.any { it.id == article.id } }.map { it.date }
            if (affected.isEmpty()) return
            snapshot = snapshot.copy(issueArticles = snapshot.issueArticles.map { entry ->
                entry.copy(articles = entry.articles.map {
                    if (it.id == article.id) metadata else it
                })
            }, issueMembership = snapshot.issueMembership.map { entry ->
                if (article.id in entry.articles) entry.copy(articles = entry.articles + (article.id to article.audioAvailable)) else entry
            })
            persist()
            affected.flatMap { issueChanges(it) } +
                CatalogChange(LIBRARY_ISSUES_ID, snapshot.issues?.size ?: 0)
        }
        changes.forEach { mutableChanges.emit(it) }
    }

    suspend fun listeningHistoryChanged(ids: Set<String>) {
        val changes = mutex.withLock {
            val changed = (listenedIds - ids) + (ids - listenedIds)
            listenedIds = ids.toSet()
            val affected = snapshot.issueMembership.filter { entry -> entry.articles.keys.any { it in changed } }.map { it.date }.toSet() +
                snapshot.issueArticles.filter { entry -> entry.articles.any { it.id in changed } }.map { it.date }
            if (affected.isEmpty()) emptyList() else affected.flatMap { issueChanges(it) } +
                CatalogChange(LIBRARY_ISSUES_ID, snapshot.issues?.size ?: 0)
        }
        changes.forEach { mutableChanges.emit(it) }
    }

    /** Match the bounded article detail list, while summaries still use complete membership. */
    private fun issueChanges(date: String): List<CatalogChange> {
        val members = snapshot.issueMembership.firstOrNull { it.date == date }?.articles?.entries
            ?.take(MAX_ARTICLES_PER_ISSUE)?.associate { it.key to it.value }
            ?: snapshot.issueArticles.firstOrNull { it.date == date }?.articles?.associate { it.id to it.audioAvailable }
            ?: emptyMap()
        val eligible = members.filterValues { it != false }.keys
        val listened = eligible.count { it in listenedIds }
        return listOf(
            CatalogChange(issueMediaId(date), ArticleListenFilter.entries.size),
            CatalogChange(issueFilterMediaId(date, ArticleListenFilter.ALL), eligible.size),
            CatalogChange(issueFilterMediaId(date, ArticleListenFilter.UNLISTENED), eligible.size - listened),
            CatalogChange(issueFilterMediaId(date, ArticleListenFilter.LISTENED), listened),
        )
    }

    /** Narration belongs to a canonical article, including when its issue details have been evicted. */
    private fun updateNarration(updates: Map<String, Article>): Set<String> {
        val affected = mutableSetOf<String>()
        snapshot = snapshot.copy(
            issueArticles = snapshot.issueArticles.map { entry ->
                entry.copy(articles = entry.articles.map { article ->
                    val narration = updates[article.id]
                    if (narration == null) article else {
                        val updated = article.copy(audio = narration.audio, audioAvailable = narration.audioAvailable)
                        if (updated != article) affected.add(entry.date)
                        updated
                    }
                })
            },
            issueMembership = snapshot.issueMembership.map { entry ->
                entry.copy(articles = entry.articles.mapValues { (id, availability) ->
                    val updated = updates[id]?.audioAvailable ?: availability
                    if (updated != availability) affected.add(entry.date)
                    updated
                })
            },
        )
        return affected
    }

    private fun retainMembership(entries: List<CachedIssueMembership>, issues: List<Magazine>?): List<CachedIssueMembership> {
        val headings = issues?.map { it.id }?.toSet()
        return entries.filter { headings == null || it.date in headings }.takeLast(MAX_ISSUES)
    }

    suspend fun artworkChanged(url: String) {
        val changes = mutex.withLock {
            val headings = if (snapshot.issues?.any { it.coverImageUrl == url } == true)
                listOf(CatalogChange(LIBRARY_ISSUES_ID, snapshot.issues!!.size)) else emptyList()
            headings + snapshot.issueArticles.filter { entry -> entry.articles.any { it.imageUrl == url } }
                .flatMap { issueChanges(it.date) }
        }
        changes.forEach { mutableChanges.emit(it) }
    }

    /** Changing files is off the player's thread; the mutex preserves write order. */
    private suspend fun persist() = withContext(Dispatchers.IO) { storage.write(json.encodeToString(snapshot)) }

    private fun restore(): CatalogSnapshot = try {
        val raw = storage.read()?.let { json.parseToJsonElement(it).jsonObject }
        val saved = raw?.let { json.decodeFromJsonElement<CatalogSnapshot>(JsonObject(it - "issueMembership")) }
        if (saved == null || saved.version !in 1..2) CatalogSnapshot() else {
            saved.issues?.forEach { validIssueDate(it.id); require(it.title.isNotBlank()) }
            saved.issueArticles.forEach { entry ->
                validIssueDate(entry.date)
                entry.articles.forEach { require(canonicalArticleUrl(it.id) == it.id && it.title.isNotBlank()) }
            }
            val membership = if (saved.version == 1) emptyList() else
                (raw["issueMembership"] as? JsonArray).orEmpty().mapNotNull { element ->
                    try {
                        json.decodeFromJsonElement<CachedIssueMembership>(element).also { entry ->
                            validIssueDate(entry.date)
                            require(entry.updatedAt >= 0)
                            entry.articles.keys.forEach { require(canonicalArticleUrl(it) == it) }
                        }
                    } catch (_: Exception) { null }
                }.distinctBy { it.date }.takeLast(MAX_ISSUES)
            saved.copy(version = 2, issueMembership = retainMembership(membership, saved.issues?.take(MAX_ISSUES)),
                issues = saved.issues?.take(MAX_ISSUES), issueArticles = saved.issueArticles.takeLast(MAX_CACHED_ISSUES).map {
                it.copy(articles = it.articles.take(MAX_ARTICLES_PER_ISSUE).map { article -> article.copy(audio = article.audio?.copy(audioUrl = null)) })
            })
        }
    } catch (_: Exception) { CatalogSnapshot() }
}

data class RecentArticles(val articles: List<Article>, val failure: Exception? = null)

@Serializable
private data class CatalogSnapshot(val version: Int = 2, val issuesUpdatedAt: Long = 0,
    val issues: List<Magazine>? = null, val issueArticles: List<CachedIssueArticles> = emptyList(),
    val issueMembership: List<CachedIssueMembership> = emptyList())

@Serializable
private data class CachedIssueMembership(val date: String, val updatedAt: Long, val articles: Map<String, Boolean?>)

@Serializable
private data class CachedIssueArticles(val date: String, val updatedAt: Long, val articles: List<Article>)
