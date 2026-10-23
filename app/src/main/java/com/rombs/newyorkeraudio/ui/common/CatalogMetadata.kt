package com.rombs.newyorkeraudio.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rombs.newyorkeraudio.R
import com.rombs.newyorkeraudio.domain.model.AudioInfo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

fun formatIssueDate(date: String, locale: Locale = Locale.getDefault()): String = try {
    LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
} catch (_: DateTimeParseException) {
    date
}

@Composable
fun NarrationMetadata(audio: AudioInfo?, modifier: Modifier = Modifier, available: Boolean? = audio != null) {
    val duration = audio?.durationMs?.takeIf { it > 0 }
    val label = when {
        available == null -> stringResource(R.string.narration_unknown)
        audio == null -> stringResource(R.string.not_narrated)
        duration != null -> stringResource(R.string.narrated_duration, (duration / 60_000).coerceAtLeast(1))
        else -> stringResource(R.string.narrated)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (audio != null && available == true) {
            Icon(
                painterResource(R.drawable.ic_headphones),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = if (audio != null && available == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
