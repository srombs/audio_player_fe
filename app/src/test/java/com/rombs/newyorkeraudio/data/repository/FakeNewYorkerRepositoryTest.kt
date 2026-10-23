package com.rombs.newyorkeraudio.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeNewYorkerRepositoryTest {
    private val repository = FakeNewYorkerRepository()

    @Test
    fun issueAndArticleIdsAreUniqueAcrossTheCatalog() = runBlocking {
        val issues = repository.getMagazines()
        assertEquals(4, issues.size)
        assertEquals(issues.size, issues.map { it.id }.distinct().size)
        val articles = issues.flatMap { repository.getArticles(it.id) }
        assertEquals(16, articles.size)
        assertEquals(articles.size, articles.map { it.id }.distinct().size)
    }

    @Test
    fun selectingAnIssueReturnsOnlyItsArticles() = runBlocking {
        val october12 = repository.getArticles("2026-10-12")
        val october5 = repository.getArticles("2026-10-05")
        assertTrue(october12.any { it.title == "The City After Midnight" })
        assertTrue(october5.any { it.title == "Following the River" })
        assertTrue(october12.map { it.id }.intersect(october5.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun anArticleCanBeReloadedUsingOnlyItsNavigationId() = runBlocking {
        for (issue in repository.getMagazines()) {
            assertEquals(issue, repository.getMagazine(issue.id))
            for (article in repository.getArticles(issue.id)) {
                assertEquals(article, repository.getArticle(article.id))
            }
        }
    }

    @Test
    fun sampleNarrationDoesNotInventAStreamingUrl() = runBlocking {
        val narrated = repository.getArticle("2026-10-12-city")
        assertNotNull(narrated.audio)
        assertEquals(27 * 60_000L, narrated.audio?.durationMs)
        assertNull(narrated.audio?.audioUrl)
        assertNull(repository.getArticle("2026-10-12-notes").audio)
    }

    @Test
    fun unknownIdsAreErrorsRatherThanEmptySuccessfulResults() {
        assertThrows(NoSuchElementException::class.java) {
            runBlocking { repository.getMagazine("missing") }
        }
        assertThrows(NoSuchElementException::class.java) {
            runBlocking { repository.getArticles("missing") }
        }
        assertThrows(NoSuchElementException::class.java) {
            runBlocking { repository.getArticle("missing") }
        }
    }
}
