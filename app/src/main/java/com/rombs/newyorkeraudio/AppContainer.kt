package com.rombs.newyorkeraudio

import android.content.Context
import com.rombs.newyorkeraudio.data.network.BackendClient
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.NetworkNewYorkerRepository
import com.rombs.newyorkeraudio.data.repository.PreferencesArticleMetadataStorage
import com.rombs.newyorkeraudio.playback.PlayerConnection
import com.rombs.newyorkeraudio.playback.PlaybackProgressCache
import com.rombs.newyorkeraudio.playback.PreferencesProgressStorage
import com.rombs.newyorkeraudio.playback.ListeningHistoryCache
import com.rombs.newyorkeraudio.playback.PreferencesListeningHistoryStorage
import com.rombs.newyorkeraudio.playback.artwork.ArtworkCache
import com.rombs.newyorkeraudio.ui.articles.ArticleFilterPreferences
import com.rombs.newyorkeraudio.ui.articles.PreferencesArticleFilterStorage

class AppContainer(context: Context) {
    val articleFilterPreferences = ArticleFilterPreferences(PreferencesArticleFilterStorage(context))
    val backendApi by lazy { BackendClient.create(BuildConfig.BASE_URL) }
    val historyCache = ListeningHistoryCache(PreferencesListeningHistoryStorage(context))
    val networkRepository: NewYorkerRepository by lazy { NetworkNewYorkerRepository(backendApi,
        PreferencesArticleMetadataStorage(context), historyCache::article) }
    val repository: NewYorkerRepository get() = networkRepository
    val progressCache = PlaybackProgressCache(PreferencesProgressStorage(context))
    val playerConnection = PlayerConnection(context, progressCache)
    val artworkCache by lazy { ArtworkCache(context) }
}
