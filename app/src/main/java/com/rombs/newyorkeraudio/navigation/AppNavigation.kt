package com.rombs.newyorkeraudio.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.ui.articles.ArticleListViewModel
import com.rombs.newyorkeraudio.ui.articles.ArticleListScreen
import com.rombs.newyorkeraudio.ui.magazines.MagazineGridViewModel
import com.rombs.newyorkeraudio.ui.magazines.MagazineGridScreen
import com.rombs.newyorkeraudio.ui.player.PlayerViewModel
import com.rombs.newyorkeraudio.ui.player.PlayerScreen
import com.rombs.newyorkeraudio.playback.PlaybackController
import com.rombs.newyorkeraudio.playback.PlaybackState
import com.rombs.newyorkeraudio.playback.ListeningHistoryCache
import com.rombs.newyorkeraudio.playback.ListeningHistoryEntry
import com.rombs.newyorkeraudio.ui.articles.ListeningHistoryScreen
import com.rombs.newyorkeraudio.playback.shouldFollowSessionArticle
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun AppNavigation(
    repository: NewYorkerRepository,
    playback: PlaybackController? = null,
    openPlayerRequest: Int = 0,
    onPlayerOpened: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
    history: ListeningHistoryCache? = null,
) {
    val emptyHistory = remember { MutableStateFlow(emptyList<ListeningHistoryEntry>()) }
    val historyEntries by (history?.entries ?: emptyHistory).collectAsStateWithLifecycle()
    val listenedIds = remember(historyEntries) { historyEntries.map { it.article.id }.toSet() }
    val fallback = remember { MutableStateFlow(PlaybackState()) }
    val session by (playback?.state ?: fallback).collectAsStateWithLifecycle()
    var previousMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    var connectedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(session.isConnected, session.mediaId) {
        if (!session.isConnected) return@LaunchedEffect
        val entry = navController.currentBackStackEntry
        val displayed = entry?.takeIf { it.destination.hasRoute<PlayerRoute>() }?.toRoute<PlayerRoute>()?.articleId
        if (shouldFollowSessionArticle(displayed, previousMediaId, session.mediaId, !connectedOnce)) {
            navController.navigate(PlayerRoute(requireNotNull(session.mediaId))) {
                popUpTo(requireNotNull(entry).destination.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        session.mediaId?.let { previousMediaId = it }
        connectedOnce = true
    }
    LaunchedEffect(openPlayerRequest, session.isConnected, session.mediaId) {
        val id = session.mediaId
        if (openPlayerRequest > 0 && session.isConnected && id != null) {
            navController.navigate(PlayerRoute(id)) { launchSingleTop = true }
            onPlayerOpened()
        }
    }
    NavHost(navController = navController, startDestination = MagazinesRoute) {
        composable<MagazinesRoute> { entry ->
            val model: MagazineGridViewModel = viewModel(
                viewModelStoreOwner = entry,
                factory = viewModelFactory { initializer { MagazineGridViewModel(repository) } },
            )
            val state by model.state.collectAsStateWithLifecycle()
            MagazineGridScreen(
                state = state,
                onRetry = model::refresh,
                onIssueClick = { navController.navigate(ArticlesRoute(it)) { launchSingleTop = true } },
                onHistory = if (history == null) null else { { navController.navigate(HistoryRoute) { launchSingleTop = true } } },
            )
        }
        composable<ArticlesRoute> { entry ->
            val route = entry.toRoute<ArticlesRoute>()
            val model: ArticleListViewModel = viewModel(
                viewModelStoreOwner = entry,
                factory = viewModelFactory {
                    initializer { ArticleListViewModel(repository, route.magazineId) }
                },
            )
            val state by model.state.collectAsStateWithLifecycle()
            ArticleListScreen(
                state = state,
                onRetry = model::refresh,
                onBack = { navController.popBackStack() },
                onArticleClick = { navController.navigate(PlayerRoute(it)) { launchSingleTop = true } },
                listenedIds = listenedIds,
            )
        }
        composable<HistoryRoute> {
            ListeningHistoryScreen(historyEntries, onBack = { navController.popBackStack() },
                onArticleClick = { navController.navigate(PlayerRoute(it)) { launchSingleTop = true } })
        }
        composable<PlayerRoute> { entry ->
            val route = entry.toRoute<PlayerRoute>()
            val model: PlayerViewModel = viewModel(
                viewModelStoreOwner = entry,
                factory = viewModelFactory {
                    initializer { PlayerViewModel(repository, route.articleId, controller = playback) }
                },
            )
            val state by model.state.collectAsStateWithLifecycle()
            val playerState by model.playbackState.collectAsStateWithLifecycle()
            DisposableEffect(model) {
                onDispose { model.cancelPendingPlayback() }
            }
            PlayerScreen(
                state = state,
                onRetry = model::refresh,
                onBack = { navController.popBackStack() },
                playback = playerState,
                onPlayPause = model::playPause,
                onSeek = model::seekTo,
                onSkipBack = model::skipBackward,
                onSkipForward = model::skipForward,
                onSpeed = model::setSpeed,
                onRetryAudio = model::retryAudio,
                savedPositionMs = model.savedPositionMs,
            )
        }
    }
}
