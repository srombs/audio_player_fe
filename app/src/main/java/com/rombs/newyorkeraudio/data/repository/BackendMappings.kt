package com.rombs.newyorkeraudio.data.repository

import com.rombs.newyorkeraudio.data.network.ArticleAudioDto
import com.rombs.newyorkeraudio.data.network.ArticleSummaryDto
import com.rombs.newyorkeraudio.data.network.BackendFailure
import com.rombs.newyorkeraudio.data.network.BackendFailureKind
import com.rombs.newyorkeraudio.data.network.IssueDto
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.AudioInfo
import com.rombs.newyorkeraudio.domain.model.Magazine
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate

/** Matches the backend's canonical host and removes tracking queries and trailing slashes. */
internal fun canonicalArticleUrl(value: String): String {
    val url = value.toHttpUrl()
    require(url.isHttps && url.host in setOf("www.newyorker.com", "newyorker.com") &&
        url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        url.pathSegments.count { it.isNotEmpty() } >= 2) { "Expected an HTTPS New Yorker article URL." }
    return url.newBuilder().host("www.newyorker.com")
        .encodedPath(url.encodedPath.trimEnd('/')).query(null).fragment(null).build().toString()
}

internal fun IssueDto.toMagazine(): Magazine = validResponse {
    require(LocalDate.parse(issueDate).toString() == issueDate && label.isNotBlank())
    Magazine(issueDate, label, issueDate, coverImageUrl)
}

internal fun ArticleSummaryDto.toArticle(): Article = validResponse {
    require(title.isNotBlank())
    val canonical = canonicalArticleUrl(url)
    Article(
        id = canonical,
        title = title,
        author = byline?.takeIf { it.isNotBlank() },
        articleUrl = canonical,
        imageUrl = imageUrl,
        section = section ?: rubric,
        // Unknown narration remains selectable for resolution. This is not a playable source.
        audio = if (audioAvailable == false) null else AudioInfo(durationMs?.takeIf { it > 0 }, null),
        audioAvailable = audioAvailable,
        description = description?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun ArticleAudioDto.toArticle(url: String, summary: Article?): Article = validResponse {
    require(canonicalArticleUrl(article.url) == url && article.title.isNotBlank())
    val narration = audio?.let {
        require(it.durationMs > 0 && it.audioUrl.toHttpUrl().isHttps)
        AudioInfo(it.durationMs, it.audioUrl)
    }
    Article(
        id = url, // The resolver's separate backend ID never changes navigation/session identity.
        title = article.title,
        author = article.authors.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotEmpty() }
            ?: summary?.author,
        articleUrl = url,
        imageUrl = summary?.imageUrl,
        section = summary?.section,
        audio = narration,
        audioAvailable = narration != null,
        description = article.description?.trim()?.takeIf { it.isNotEmpty() } ?: summary?.description,
    )
}

private inline fun <T> validResponse(map: () -> T): T = try {
    map()
} catch (error: IllegalArgumentException) {
    throw BackendFailure(BackendFailureKind.INVALID_RESPONSE, cause = error)
} catch (error: java.time.DateTimeException) {
    throw BackendFailure(BackendFailureKind.INVALID_RESPONSE, cause = error)
}
