package com.rombs.newyorkeraudio.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Article(
    val id: String,
    val title: String,
    val author: String?,
    val articleUrl: String?,
    val imageUrl: String?,
    val section: String?,
    val audio: AudioInfo?,
    /** Null means the list's narration availability is unknown, until resolution. */
    val audioAvailable: Boolean? = audio != null,
    val description: String? = null,
)
