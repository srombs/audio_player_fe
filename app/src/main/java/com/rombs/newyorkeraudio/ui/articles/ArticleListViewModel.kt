package com.rombs.newyorkeraudio.ui.articles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.ui.common.ScreenLoader
import kotlinx.coroutines.CoroutineScope

data class ArticleListContent(val magazine: Magazine, val articles: List<Article>)

class ArticleListViewModel(
    repository: NewYorkerRepository,
    magazineId: String,
    loadScope: CoroutineScope? = null,
) : ViewModel() {
    private val loader = ScreenLoader(
        scope = loadScope ?: viewModelScope,
        load = {
            val articles = repository.getArticles(magazineId)
            ArticleListContent(repository.getMagazine(magazineId), articles)
        },
        isEmpty = { it.articles.isEmpty() },
    )
    val state = loader.state

    init { refresh() }

    fun refresh() = loader.refresh()
}
