package com.rombs.newyorkeraudio.ui.articles

import android.content.Context
import com.rombs.newyorkeraudio.playback.ArticleListenFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ArticleFilterStorage {
    fun read(): String?
    fun write(value: String)
}

/** One filter for every issue, retained across navigation and app restarts. */
class ArticleFilterPreferences(private val storage: ArticleFilterStorage? = null) {
    private val mutableFilter = MutableStateFlow(
        storage?.read()?.let { saved -> ArticleListenFilter.entries.firstOrNull { it.name == saved } }
            ?: ArticleListenFilter.ALL,
    )
    val filter = mutableFilter.asStateFlow()

    fun select(filter: ArticleListenFilter) {
        if (filter == mutableFilter.value) return
        storage?.write(filter.name)
        mutableFilter.value = filter
    }
}

class PreferencesArticleFilterStorage(context: Context) : ArticleFilterStorage {
    private val preferences = context.applicationContext.getSharedPreferences("catalog_preferences", Context.MODE_PRIVATE)
    override fun read(): String? = try {
        preferences.getString("listened_filter", null)
    } catch (_: ClassCastException) { null }
    override fun write(value: String) { preferences.edit().putString("listened_filter", value).apply() }
}
