package com.rombs.newyorkeraudio.ui.articles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import com.rombs.newyorkeraudio.playback.ArticleListenFilter
import com.rombs.newyorkeraudio.playback.filterListenedArticles
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rombs.newyorkeraudio.R
import com.rombs.newyorkeraudio.ui.common.CatalogScaffold
import com.rombs.newyorkeraudio.ui.common.EmptyCatalogMessage
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.common.LoadedContent
import com.rombs.newyorkeraudio.ui.common.formatIssueDate

@Composable
fun ArticleListScreen(
    state: LoadState<ArticleListContent>,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onArticleClick: (String) -> Unit,
    listenedIds: Set<String> = emptySet(),
    filter: ArticleListenFilter = ArticleListenFilter.ALL,
    onFilterChange: (ArticleListenFilter) -> Unit = {},
) {
    CatalogScaffold(title = stringResource(R.string.articles_title), onBack = onBack,
        onRefresh = onRetry.takeIf { state != LoadState.Loading }) { padding ->
        LoadedContent(state, onRetry, Modifier.padding(padding).consumeWindowInsets(padding)) { content, modifier ->
            val articles = filterListenedArticles(content.articles, listenedIds, filter)
            LazyColumn(
                modifier = modifier.fillMaxSize().testTag("articles:${content.magazine.id}"),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(content.magazine.title, style = MaterialTheme.typography.headlineLarge)
                        val date = content.magazine.date?.let { formatIssueDate(it) }
                        if (date != null && date != content.magazine.title) {
                            Text(date, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            stringResource(R.string.article_list_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ArticleListenFilter.entries.forEach { option ->
                                FilterChip(selected = filter == option, onClick = { onFilterChange(option) },
                                    label = { Text(stringResource(when (option) {
                                        ArticleListenFilter.ALL -> R.string.all_articles
                                        ArticleListenFilter.UNLISTENED -> R.string.unlistened
                                        ArticleListenFilter.LISTENED -> R.string.listened
                                    })) }, modifier = Modifier.testTag("filter:${option.name}"))
                            }
                        }
                    }
                }
                if (articles.isEmpty()) {
                    item(key = "empty") {
                        EmptyCatalogMessage(stringResource(if (content.articles.isEmpty()) R.string.no_articles else R.string.no_filtered_articles), onRefresh = onRetry)
                    }
                }
                items(articles, key = { it.id }) { article ->
                    ArticleRow(article, onClick = { onArticleClick(article.id) }, listened = article.id in listenedIds)
                }
            }
        }
    }
}
