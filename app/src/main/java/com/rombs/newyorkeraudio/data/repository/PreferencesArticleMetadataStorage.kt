package com.rombs.newyorkeraudio.data.repository

import android.content.Context

class PreferencesArticleMetadataStorage(context: Context) : ArticleMetadataStorage {
    private val preferences = context.applicationContext.getSharedPreferences("article_metadata", Context.MODE_PRIVATE)
    override fun read(): String? = preferences.getString("entries", null)
    override fun write(value: String) { preferences.edit().putString("entries", value).apply() }
}
