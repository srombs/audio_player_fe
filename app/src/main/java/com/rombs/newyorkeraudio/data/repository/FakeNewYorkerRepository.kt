package com.rombs.newyorkeraudio.data.repository

import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.AudioInfo
import com.rombs.newyorkeraudio.domain.model.Magazine

/** Entirely local, fictional metadata. No subscriber content or network requests. */
class FakeNewYorkerRepository(demoArticle: Article? = null) : NewYorkerRepository {
    private val magazines = listOf(
        Magazine("2026-10-12", "October 12, 2026", "2026-10-12", null),
        Magazine("2026-10-05", "October 5, 2026", "2026-10-05", null),
        Magazine("2026-09-28", "September 28, 2026", "2026-09-28", null),
        Magazine("2026-09-21", "September 21, 2026", "2026-09-21", null),
    )

    private val articlesByIssue = mapOf(
        "2026-10-12" to listOf(
            sample("2026-10-12-city", "The City After Midnight", "Alex Morgan", "Reporting", 27),
            sample("2026-10-12-books", "A Life Between the Pages", "Jamie Reed", "Books", 18),
            sample("2026-10-12-garden", "The Rooftop Garden", "Sam Ellis", "Fiction", 34),
            sample("2026-10-12-notes", "Notes from the Neighborhood", "Taylor Lane", "Talk of the Town", null),
        ).let { articles -> if (demoArticle != null) listOf(demoArticle) + articles else articles },
        "2026-10-05" to listOf(
            sample("2026-10-05-river", "Following the River", "Robin Park", "Reporting", 22),
            sample("2026-10-05-music", "The Sound of a Small Room", "Alex Morgan", "Music", 15),
            sample("2026-10-05-train", "The Last Train Home", "Sam Ellis", "Fiction", 31),
            sample("2026-10-05-table", "A Table for Two", "Taylor Lane", "Food", null),
        ),
        "2026-09-28" to listOf(
            sample("2026-09-28-coast", "On the Changing Coast", "Jamie Reed", "Reporting", 29),
            sample("2026-09-28-art", "Looking at the Ordinary", "Robin Park", "Art", 16),
            sample("2026-09-28-letter", "An Unsent Letter", "Sam Ellis", "Fiction", 25),
            sample("2026-09-28-walk", "A Walk Through September", "Taylor Lane", "Talk of the Town", null),
        ),
        "2026-09-21" to listOf(
            sample("2026-09-21-workshop", "Inside the Workshop", "Alex Morgan", "Profiles", 24),
            sample("2026-09-21-stage", "Before the Curtain Rises", "Robin Park", "Theatre", 19),
            sample("2026-09-21-summer", "The End of Summer", "Sam Ellis", "Fiction", 33),
            sample("2026-09-21-market", "At the Morning Market", "Jamie Reed", "Food", null),
        ),
    )

    override suspend fun getMagazines(): List<Magazine> = magazines.toList()

    override suspend fun getMagazine(magazineId: String): Magazine =
        magazines.firstOrNull { it.id == magazineId }
            ?: throw NoSuchElementException("Unknown issue: $magazineId")

    override suspend fun getArticles(magazineId: String): List<Article> =
        articlesByIssue[magazineId]?.toList()
            ?: throw NoSuchElementException("Unknown issue: $magazineId")

    override suspend fun getArticleMetadata(articleId: String): Article = getArticle(articleId)

    override suspend fun getArticle(articleId: String): Article =
        articlesByIssue.values.flatten().firstOrNull { it.id == articleId }
            ?: throw NoSuchElementException("Unknown article: $articleId")

    private fun sample(
        id: String,
        title: String,
        author: String,
        section: String,
        durationMinutes: Long?,
    ) = Article(
        id = id,
        title = title,
        author = author,
        articleUrl = null,
        imageUrl = null,
        section = section,
        audio = durationMinutes?.let { AudioInfo(durationMs = it * 60_000, audioUrl = null) },
    )
}
