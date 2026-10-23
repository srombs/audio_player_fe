package com.rombs.newyorkeraudio.ui.articles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rombs.newyorkeraudio.R
import com.rombs.newyorkeraudio.playback.ListeningHistoryEntry
import com.rombs.newyorkeraudio.ui.common.CatalogScaffold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ListeningHistoryScreen(entries: List<ListeningHistoryEntry>, onBack: () -> Unit, onArticleClick: (String) -> Unit) {
    CatalogScaffold(title = stringResource(R.string.listening_history_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("listening-history"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (entries.isEmpty()) item { Text(stringResource(R.string.no_listening_history)) }
            items(entries, key = { it.article.id }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.listened_on, DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.completedAt))),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ArticleRow(entry.article, onClick = { onArticleClick(entry.article.id) }, listened = true)
                }
            }
        }
    }
}
