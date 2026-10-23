package com.rombs.newyorkeraudio.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.ui.articles.ArticleListContent
import com.rombs.newyorkeraudio.ui.articles.ArticleListScreen
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.magazines.MagazineGridScreen
import com.rombs.newyorkeraudio.ui.theme.NewYorkerAudioTheme

@Preview(name = "Loading", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun LoadingPreview() {
    NewYorkerAudioTheme { MagazineGridScreen(LoadState.Loading, {}, {}) }
}

@Preview(name = "Error · large text", showBackground = true, widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable
private fun ErrorPreview() {
    NewYorkerAudioTheme { MagazineGridScreen(LoadState.Error, {}, {}) }
}

@Preview(name = "Empty library", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun EmptyLibraryPreview() {
    NewYorkerAudioTheme { MagazineGridScreen(LoadState.Empty(emptyList()), {}, {}) }
}

@Preview(name = "Empty issue", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun EmptyIssuePreview() {
    NewYorkerAudioTheme {
        ArticleListScreen(
            LoadState.Empty(ArticleListContent(Magazine("2026-10-12", "October 12, 2026", "2026-10-12", null), emptyList())),
            {}, {}, {},
        )
    }
}
