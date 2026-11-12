package com.rombs.newyorkeraudio.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IssueDto(
    @SerialName("issue_date") val issueDate: String,
    val label: String,
    val url: String,
    @SerialName("cover_image_url") val coverImageUrl: String? = null,
)

@Serializable
data class IssueListDto(
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("history_complete") val historyComplete: Boolean,
    val issues: List<IssueDto>,
)

@Serializable
data class ArticleSummaryDto(
    val title: String,
    val url: String,
    val section: String? = null,
    val rubric: String? = null,
    val byline: String? = null,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("audio_available") val audioAvailable: Boolean? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

@Serializable
data class IssueArticlesDto(
    val version: Int = 1,
    val issue: IssueDto,
    @SerialName("fetched_at") val fetchedAt: String,
    val articles: List<ArticleSummaryDto> = emptyList(),
)

@Serializable
data class AudioRequest(val url: String, val refresh: Boolean = false)

@Serializable
data class ResolvedArticleDto(
    val id: String,
    val title: String,
    val url: String,
    val description: String? = null,
    val authors: List<String> = emptyList(),
)

@Serializable
data class AudioSourceDto(
    @SerialName("audio_id") val audioId: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("audio_url") val audioUrl: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("verified_at") val verifiedAt: String,
)

@Serializable
data class ArticleAudioDto(val article: ResolvedArticleDto, val audio: AudioSourceDto? = null)

@Serializable
data class AuthStatusDto(
    val status: String,
    val authenticated: Boolean? = null,
    val subscriber: Boolean? = null,
    @SerialName("http_session_reusable") val httpSessionReusable: Boolean? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
    val action: String,
    val reason: String,
)

@Serializable
data class HealthDto(val status: String)

@Serializable
internal data class BackendErrorDto(val detail: BackendErrorDetailDto)

@Serializable
internal data class BackendErrorDetailDto(val code: String, val action: String)
