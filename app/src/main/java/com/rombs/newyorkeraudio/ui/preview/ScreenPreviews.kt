package com.rombs.newyorkeraudio.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.AudioInfo
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.ui.articles.ArticleListContent
import com.rombs.newyorkeraudio.ui.articles.ArticleListScreen
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.magazines.MagazineGridScreen
import com.rombs.newyorkeraudio.ui.player.PlayerScreen
import com.rombs.newyorkeraudio.ui.theme.NewYorkerAudioTheme
import com.rombs.newyorkeraudio.playback.PlaybackState

private val previewIssues = listOf(
    Magazine("2026-10-12", "October 12, 2026", "2026-10-12", null),
    Magazine("2026-10-05", "October 5, 2026", "2026-10-05", null),
    Magazine("2026-09-28", "September 28, 2026", "2026-09-28", null),
    Magazine("2026-09-21", "September 21, 2026", "2026-09-21", null),
)
private val previewArticle = Article(
    "2026-10-12-city", "The City After Midnight", "Alex Morgan", null, null,
    "Reporting", AudioInfo(27 * 60_000L, null),
)
private val previewArticles = listOf(
    previewArticle,
    previewArticle.copy(id = "books", title = "A Life Between the Pages", author = "Jamie Reed", section = "Books"),
    previewArticle.copy(id = "notes", title = "Notes from the Neighborhood", section = "Talk of the Town", audio = null),
)

@Preview(name = "Issues", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "Issues · large text", showBackground = true, widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Composable
private fun MagazineGridPreview() {
    NewYorkerAudioTheme {
        MagazineGridScreen(LoadState.Content(previewIssues), onRetry = {}, onIssueClick = {})
    }
}

@Preview(name = "Articles", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ArticleListPreview() {
    NewYorkerAudioTheme {
        ArticleListScreen(
            LoadState.Content(ArticleListContent(previewIssues.first(), previewArticles)),
            onRetry = {}, onBack = {}, onArticleClick = {},
        )
    }
}

@Preview(name = "Player", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PlayerPreview() {
    NewYorkerAudioTheme {
        PlayerScreen(
            LoadState.Content(previewArticle), onRetry = {}, onBack = {},
            playback = PlaybackState(
                mediaId = previewArticle.id, isConnected = true, isPlaying = true,
                playWhenReady = true, positionMs = 120_000, durationMs = 27 * 60_000L, canSeek = true,
            ),
        )
    }
}
