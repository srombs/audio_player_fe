package com.rombs.newyorkeraudio.ui.magazines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rombs.newyorkeraudio.R
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.ui.common.CatalogScaffold
import com.rombs.newyorkeraudio.ui.common.EmptyCatalogMessage
import com.rombs.newyorkeraudio.ui.common.LoadState
import com.rombs.newyorkeraudio.ui.common.LoadedContent

@Composable
fun MagazineGridScreen(
    state: LoadState<List<Magazine>>,
    onRetry: () -> Unit,
    onIssueClick: (String) -> Unit,
    onHistory: (() -> Unit)? = null,
) {
    CatalogScaffold(title = stringResource(R.string.app_name), onRefresh = onRetry.takeIf { state != LoadState.Loading },
        onHistory = onHistory) { padding ->
        LoadedContent(state, onRetry, Modifier.padding(padding).consumeWindowInsets(padding)) { magazines, modifier ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = modifier.fillMaxSize().testTag("magazines"),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.browse_issues), style = MaterialTheme.typography.headlineLarge)
                        Text(
                            stringResource(R.string.library_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (magazines.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyCatalogMessage(stringResource(R.string.no_issues), onRefresh = onRetry)
                    }
                }
                items(magazines, key = { it.id }) { magazine ->
                    MagazineCard(magazine, onClick = { onIssueClick(magazine.id) })
                }
            }
        }
    }
}
