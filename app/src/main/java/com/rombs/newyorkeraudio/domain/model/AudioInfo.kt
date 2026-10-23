package com.rombs.newyorkeraudio.domain.model

import kotlinx.serialization.Serializable

/** A narration can be known before its streaming URL has been resolved. */
@Serializable
data class AudioInfo(
    val durationMs: Long?,
    val audioUrl: String?,
)
