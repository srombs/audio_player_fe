package com.rombs.newyorkeraudio.ui

import com.rombs.newyorkeraudio.playback.ArticleListenFilter
import com.rombs.newyorkeraudio.ui.articles.ArticleFilterPreferences
import com.rombs.newyorkeraudio.ui.articles.ArticleFilterStorage
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleFilterPreferencesTest {
    @Test fun `each filter choice survives app recreation including switching back to all`() {
        val storage = Storage()
        var preferences = ArticleFilterPreferences(storage)
        for (filter in listOf(ArticleListenFilter.UNLISTENED, ArticleListenFilter.LISTENED, ArticleListenFilter.ALL)) {
            preferences.select(filter)
            assertEquals(filter, preferences.filter.value)
            preferences = ArticleFilterPreferences(storage)
            assertEquals(filter, preferences.filter.value)
        }
    }

    @Test fun `missing or unrecognized preference defaults to all`() {
        assertEquals(ArticleListenFilter.ALL, ArticleFilterPreferences(Storage()).filter.value)
        assertEquals(ArticleListenFilter.ALL, ArticleFilterPreferences(Storage("obsolete")).filter.value)
    }

    private class Storage(var value: String? = null) : ArticleFilterStorage {
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }
}
