package com.rombs.newyorkeraudio.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("magazines")
data object MagazinesRoute

@Serializable
@SerialName("history")
data object HistoryRoute

@Serializable
@SerialName("articles")
data class ArticlesRoute(val magazineId: String)

@Serializable
@SerialName("player")
data class PlayerRoute(val articleId: String)
