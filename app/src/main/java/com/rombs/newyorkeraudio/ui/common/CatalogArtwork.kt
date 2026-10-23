package com.rombs.newyorkeraudio.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import com.rombs.newyorkeraudio.R

/** Bundled images keep the fake library offline; Coil handles backend URLs later. */
@Composable
fun CatalogArtwork(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    @DrawableRes placeholder: Int = R.drawable.sample_artwork,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val fallback = painterResource(placeholder)
    if (imageUrl.isNullOrBlank()) {
        Image(fallback, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            placeholder = fallback,
            error = fallback,
            fallback = fallback,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

@DrawableRes
fun issuePlaceholder(issueId: String): Int = when (issueId) {
    "2026-10-12" -> R.drawable.sample_cover_city
    "2026-10-05" -> R.drawable.sample_cover_evening
    "2026-09-28" -> R.drawable.sample_cover_shapes
    else -> R.drawable.sample_artwork
}

@DrawableRes
fun articlePlaceholder(section: String?): Int = when (section) {
    "Reporting", "Profiles", "Talk of the Town" -> R.drawable.sample_cover_city
    "Music", "Theatre", "Fiction" -> R.drawable.sample_cover_evening
    "Books", "Art" -> R.drawable.sample_cover_shapes
    else -> R.drawable.sample_artwork
}
