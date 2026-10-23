package com.rombs.newyorkeraudio.ui.articles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.R
import androidx.compose.ui.res.stringResource
import com.rombs.newyorkeraudio.ui.common.CatalogArtwork
import com.rombs.newyorkeraudio.ui.common.NarrationMetadata
import com.rombs.newyorkeraudio.ui.common.articlePlaceholder

@Composable
fun ArticleRow(article: Article, onClick: () -> Unit, modifier: Modifier = Modifier, listened: Boolean = false) {
    val cardModifier = modifier.fillMaxWidth().testTag("article:${article.id}")
    val shape = RoundedCornerShape(8.dp)
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (article.audioAvailable != false) {
        Card(onClick = onClick, modifier = cardModifier, shape = shape, colors = colors, border = border) {
            ArticleRowContent(article, listened)
        }
    } else {
        Card(modifier = cardModifier, shape = shape, colors = colors, border = border) {
            ArticleRowContent(article, listened)
        }
    }
}

@Composable
private fun ArticleRowContent(article: Article, listened: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CatalogArtwork(
            imageUrl = article.imageUrl,
            placeholder = articlePlaceholder(article.section),
            modifier = Modifier.size(width = 72.dp, height = 92.dp)
                .clip(RoundedCornerShape(4.dp)).testTag("thumbnail:${article.id}"),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            article.section?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(article.title, style = MaterialTheme.typography.titleMedium)
            if (listened) Text(stringResource(R.string.listened), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("listened:${article.id}"))
            article.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NarrationMetadata(article.audio, available = article.audioAvailable)
        }
    }
}
