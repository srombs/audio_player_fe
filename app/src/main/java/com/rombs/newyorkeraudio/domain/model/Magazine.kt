package com.rombs.newyorkeraudio.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Magazine(
    val id: String,
    val title: String,
    val date: String?,
    val coverImageUrl: String?,
)
