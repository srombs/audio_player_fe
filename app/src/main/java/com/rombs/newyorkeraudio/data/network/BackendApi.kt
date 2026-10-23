package com.rombs.newyorkeraudio.data.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface BackendApi {
    @GET("issues")
    suspend fun getIssues(@Query("year") year: Int? = null): IssueListDto

    @GET("issues/{issue_date}/articles")
    suspend fun getIssueArticles(@Path("issue_date") issueDate: String): IssueArticlesDto

    @POST("articles/audio")
    suspend fun resolveAudio(@Body request: AudioRequest): ArticleAudioDto

    @GET("auth/status")
    suspend fun getAuthStatus(): AuthStatusDto

    @GET("health")
    suspend fun getHealth(): HealthDto
}
