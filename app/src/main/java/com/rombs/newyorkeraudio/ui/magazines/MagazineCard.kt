package com.rombs.newyorkeraudio.ui.magazines

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rombs.newyorkeraudio.R
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.ui.common.CatalogArtwork
import com.rombs.newyorkeraudio.ui.common.formatIssueDate
import com.rombs.newyorkeraudio.ui.common.issuePlaceholder

@Composable
fun MagazineCard(magazine: Magazine, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val date = magazine.date?.let { formatIssueDate(it) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("issue:${magazine.id}"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        CatalogArtwork(
            imageUrl = magazine.coverImageUrl,
            placeholder = issuePlaceholder(magazine.id),
            modifier = Modifier.fillMaxWidth().aspectRatio(0.8f).testTag("cover:${magazine.id}"),
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(magazine.title, style = MaterialTheme.typography.titleMedium)
            Text(
                date?.takeUnless { it == magazine.title } ?: stringResource(R.string.magazine_issue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
