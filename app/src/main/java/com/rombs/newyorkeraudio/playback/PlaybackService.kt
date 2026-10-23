package com.rombs.newyorkeraudio.playback

import com.rombs.newyorkeraudio.domain.model.Article

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.Process
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.LibraryResult
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaConstants
import androidx.media3.datasource.HttpDataSource
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.rombs.newyorkeraudio.data.network.BackendFailure
import com.rombs.newyorkeraudio.ui.common.LoadFailure
import com.rombs.newyorkeraudio.ui.common.toLoadFailure
import com.rombs.newyorkeraudio.ui.common.messageResource
import com.rombs.newyorkeraudio.playback.library.CatalogLibrary
import com.rombs.newyorkeraudio.playback.library.ControllerAccessValidator
import com.rombs.newyorkeraudio.playback.library.PreferencesCatalogStorage
import com.rombs.newyorkeraudio.playback.library.libraryRoot
import com.rombs.newyorkeraudio.playback.library.toLibraryMediaItem
import com.rombs.newyorkeraudio.playback.library.LibraryNode
import com.rombs.newyorkeraudio.playback.library.libraryPage
import com.rombs.newyorkeraudio.playback.library.LIBRARY_CONTINUE_ID
import com.rombs.newyorkeraudio.playback.library.ListeningLibrary
import com.rombs.newyorkeraudio.playback.library.SearchResultsCache
import com.rombs.newyorkeraudio.playback.library.SearchSnapshot
import com.rombs.newyorkeraudio.playback.library.NoVoiceMatchException
import com.rombs.newyorkeraudio.playback.library.AmbiguousVoiceMatchException
import com.rombs.newyorkeraudio.playback.library.VoiceQueryTooLongException
import com.rombs.newyorkeraudio.playback.library.LIBRARY_ROOT_ID
import com.rombs.newyorkeraudio.playback.library.LIBRARY_ISSUES_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executor
import com.rombs.newyorkeraudio.MainActivity
import com.rombs.newyorkeraudio.NewYorkerAudioApplication
import com.rombs.newyorkeraudio.PLAYBACK_DIAGNOSTICS_VERSION
import com.rombs.newyorkeraudio.data.repository.canonicalArticleUrl

const val ACTION_OPEN_PLAYER = "com.rombs.newyorkeraudio.OPEN_PLAYER"
internal const val RECENT_ROOT_ID = "ny:recent"

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val selections = PlaybackSelectionCoordinator(serviceScope)
    private var browseSequence = 0L
    private val repository get() = (application as NewYorkerAudioApplication).container.repository
    private val artwork get() = (application as NewYorkerAudioApplication).container.artworkCache
    private val catalog by lazy { CatalogLibrary(repository, serviceScope, PreferencesCatalogStorage(this)) }
    private val access by lazy { ControllerAccessValidator(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val progressCache get() = (application as NewYorkerAudioApplication).container.progressCache
    private val historyCache get() = (application as NewYorkerAudioApplication).container.historyCache
    private val completionRecorder by lazy { CompletionRecorder(historyCache) }
    private val lastPlayed by lazy { LastPlayedCache(PreferencesLastPlayedStorage(this)) }
    private val retries = StreamRetryPolicy()
    private val listening by lazy { ListeningLibrary(catalog, repository, progressCache) { lastPlayed.current?.article } }
    private val searches = SearchResultsCache()
    private val browseResults = mutableSetOf<SettableFuture<*>>()
    private val phoneOwner = PhoneSelectionOwner()
    private var committingPlayback = false
    private val progressListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            recordPlayback(player)
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // These pauses originate inside ExoPlayer and bypass SelectionPlayer.
            if (!playWhenReady && reason in setOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) cancelSelections()
        }
        override fun onPlayerError(error: PlaybackException) {
            val player = session?.player ?: return
            recordPlayback(player)
            val status = generateSequence(error as Throwable?) { it.cause }.take(16)
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            if (!selections.hasPending && retries.tryAutomatic(status, player.playWhenReady)) {
                Log.i("PlaybackAccess", "stream recovery refreshing source status=$status")
                recoverCurrentStream()
            } else {
                session?.sendError(SessionError(SessionError.ERROR_IO, "Audio playback failed. Press Play to retry.", Bundle.EMPTY))
            }
        }
    }
    private val saveProgress = object : Runnable {
        override fun run() {
            session?.player?.let { recordPlayback(it) }
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("PlaybackAccess", "service created diagnostics=$PLAYBACK_DIAGNOSTICS_VERSION")
        val engine = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SKIP_INTERVAL_MS)
            .setSeekForwardIncrementMs(SKIP_INTERVAL_MS)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val player = SelectionPlayer(engine,
            cancelSelection = { recordPlayback(engine); cancelSelections() },
            beforeItemChange = { recordPlayback(engine) },
            afterItemChange = { recordPlayback(engine, explicitSeek = true) },
            afterSeek = { recordPlayback(engine, explicitSeek = true) },
            handlePlayRequest = {
                if (committingPlayback) false
                else if (engine.currentMediaItem == null) true // Suppress Media3's fallback Play after failed/cancelled cold resumption.
                else if (engine.playerError != null) { retries.begin(refresh = true); recoverCurrentStream(); true }
                else false
            },
        )
        val openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val controls = listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15).setDisplayName("Back 15 seconds")
                .setPlayerCommand(Player.COMMAND_SEEK_BACK).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15).setDisplayName("Forward 15 seconds")
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setSlots(CommandButton.SLOT_FORWARD).build(),
        )
        session = MediaLibrarySession.Builder(this, player, libraryCallback).setSessionActivity(openPlayer)
            .setMediaButtonPreferences(controls).build()
        serviceScope.launch {
            catalog.changes.collect { change ->
                session?.notifyChildrenChanged(change.parentId, change.count, null)
                notifyContinueChanged()
            }
        }
        serviceScope.launch {
            progressCache.changes.collect { notifyContinueChanged() }
        }
        serviceScope.launch {
            historyCache.entries.collect { entries ->
                entries.firstOrNull()?.let { catalog.completionChanged(it.article.id) }
                session?.notifyChildrenChanged(RECENT_ROOT_ID, recentNodes().size, null)
                notifyContinueChanged()
            }
        }
        serviceScope.launch {
            // Shared catalog flights deduplicate warm-up with an immediate voice/browse request.
            val recent = catalog.recentArticles()
            Log.i("PlaybackBrowse", "voice metadata warm count=${recent.articles.size} partial=${recent.failure != null}")
            listening.unfinishedArticles()
        }
        serviceScope.launch {
            artwork.updates.collect { url ->
                catalog.artworkChanged(url)
                val current = player.currentMediaItem
                if (current?.toPlaybackArticle()?.imageUrl == url) {
                    player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon()
                        .setMediaMetadata(current.mediaMetadata.buildUpon().setArtworkUri(artwork.uri(url)).build()).build())
                }
            }
        }
        player.addListener(progressListener)
        handler.postDelayed(saveProgress, 5_000)
        Log.i("PlaybackAccess", "service session ready diagnostics=$PLAYBACK_DIAGNOSTICS_VERSION")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i("PlaybackAccess", "service bind action=${intent?.action} diagnostics=$PLAYBACK_DIAGNOSTICS_VERSION")
        return super.onBind(intent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        // Media3 1.8.0 uses an anonymous legacy placeholder (UID -1) here while
        // creating the platform browser binder. Authorizing it rejects Android Auto
        // before its real caller identity reaches onConnect. Select the session
        // here; onConnect remains the authorization gate for every real caller.
        Log.i("PlaybackAccess", "session selected package=${controllerInfo.packageName} uid=${controllerInfo.uid} " +
            "sessionReady=${session != null} accessCheck=onConnect diagnostics=$PLAYBACK_DIAGNOSTICS_VERSION")
        return session
    }

    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val allowed = access.accepts(controller)
            Log.i("PlaybackAccess", "session connect package=${controller.packageName} uid=${controller.uid} " +
                "allowed=$allowed trusted=${controller.isTrusted} diagnostics=$PLAYBACK_DIAGNOSTICS_VERSION")
            if (!allowed) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            if (controller.uid == Process.myUid()) {
                commands.add(SELECT_ARTICLE_COMMAND).add(CANCEL_SELECTION_COMMAND).add(VOICE_PLAY_COMMAND)
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()).build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            searches.removeCaller(searchCaller(controller))
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            val root = if (params?.isRecent == true) LibraryNode(RECENT_ROOT_ID, "Recently played") else libraryRoot()
            Log.i("PlaybackBrowse", "root package=${browser.packageName} recent=${params?.isRecent} returned=${root.id}")
            return Futures.immediateFuture(LibraryResult.ofItem(root.toLibraryMediaItem(artwork), params))
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            browseFuture(params, "children parent=$parentId page=$page size=$pageSize package=${browser.packageName}") {
                val nodes = if (parentId == RECENT_ROOT_ID) libraryPage(recentNodes(), page, pageSize)
                    else if (parentId == LIBRARY_CONTINUE_ID) libraryPage(listening.continueNodes(), page, pageSize)
                    else catalog.children(parentId, page, pageSize)
                val items = if (parentId == RECENT_ROOT_ID) libraryPage(listOfNotNull(recentMediaItem()), page, pageSize)
                    else nodes.map { it.withCompletion().toLibraryMediaItem(artwork) }
                nodes.forEach { artwork.prefetch(it.artworkUrl) }
                LibraryResult.ofItemList(items, params)
            }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = browseFuture(null, "item id=$mediaId package=${browser.packageName}") {
            val node = if (mediaId == RECENT_ROOT_ID) LibraryNode(RECENT_ROOT_ID, "Recently played")
                else if (mediaId in setOf(LIBRARY_ROOT_ID, LIBRARY_ISSUES_ID, LIBRARY_CONTINUE_ID)) catalog.item(mediaId)
                else recentNodes().firstOrNull { it.id == mediaId }
                    ?: listening.continueNodes().firstOrNull { it.id == mediaId } ?: catalog.item(mediaId)
            val item = if (lastPlayed.current?.article?.id == mediaId) recentMediaItem() ?: node.withCompletion().toLibraryMediaItem(artwork)
                else node.withCompletion().toLibraryMediaItem(artwork)
            artwork.prefetch(node.artworkUrl)
            LibraryResult.ofItem(item, null)
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            if (query.length > 256) {
                session.notifySearchResultChanged(browser, query, 0, params)
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params))
            }
            val caller = searchCaller(browser)
            val generation = searches.begin(caller, query)
            return browseFuture(params, "search chars=${query.length} package=${browser.packageName}") {
                var failure: Exception? = null
                val articles = try { withTimeout(14_000) { listening.search(query) } }
                    catch (timeout: TimeoutCancellationException) { failure = java.io.IOException("Search timed out.", timeout); emptyList() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failure = error; emptyList() }
                currentCoroutineContext().ensureActive()
                searches.finish(caller, query, generation, SearchSnapshot(articles, failure))
                // Notify even when a snapshot was evicted: otherwise an older legacy request can hang.
                session.notifySearchResultChanged(browser, query, searches.get(caller, query)?.articles?.size ?: articles.size, params)
                if (failure != null) LibraryResult.ofError(selectionErrorCode(failure!!), params)
                else LibraryResult.ofVoid(params)
            }
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            browseFuture(params, "search results chars=${query.length} page=$page size=$pageSize package=${browser.packageName}") {
                require(query.length <= 256 && page >= 0 && pageSize > 0)
                val snapshot = searches.get(searchCaller(browser), query)
                snapshot?.failure?.let { throw it }
                val articles = snapshot?.articles ?: listening.search(query)
                val items = libraryPage(articles, page, pageSize).map { article ->
                    artwork.prefetch(article.imageUrl)
                    LibraryNode(article.id, article.title, article.author, article.imageUrl, playable = true, browsable = false,
                        description = article.description)
                        .withCompletion().toLibraryMediaItem(artwork)
                }
                LibraryResult.ofItemList(items, params)
            }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.isEmpty()) {
                cancelSelections()
                rememberProgress(mediaSession.player, progressCache)
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET))
            }
            // Single article playback in v1. Never accept controller-supplied streaming URIs.
            if (mediaItems.size != 1 || startIndex !in listOf(0, C.INDEX_UNSET)) {
                reportSelectionError(controller, IllegalArgumentException())
                return Futures.immediateFailedFuture(IllegalArgumentException("Expected one article."))
            }
            val refresh = mediaSession.player.currentMediaItem?.mediaId == mediaItems.single().mediaId && mediaSession.player.playerError != null
            retries.begin(refresh)
            phoneOwner.clear()
            return selections.select { request ->
                try {
                    val incoming = mediaItems.single()
                    val query = incoming.requestMetadata.searchQuery
                    val id = if (query != null || (incoming.mediaId.isBlank() && incoming.requestMetadata.mediaUri == null)) {
                        val match = voiceArticle(query.orEmpty())
                        request.ensureCurrent()
                        match.id
                    } else incoming.mediaId
                    val selection = PlaybackSelectionRequest(
                        articleId = id,
                        startPositionMs = startPositionMs.takeUnless { it == C.TIME_UNSET },
                        refresh = refresh,
                    )
                    val selected = prepareSelection(mediaSession.player, selection, request)
                    request.ensureCurrent()
                    rememberProgress(mediaSession.player, progressCache)
                    mediaSession.player.setPlaybackSpeed(selected.speed)
                    var item = selected.article.toMediaItem(this@PlaybackService, artwork.uri(selected.article.imageUrl))
                    if (selected.reuseActive) {
                        val seekPosition = selected.positionMs.takeIf { selection.startPositionMs != null || mediaSession.player.playbackState == Player.STATE_ENDED }
                        item = item.buildUpon().setTag(ActiveSelectionReuse(seekPosition)).build()
                    }
                    MediaSession.MediaItemsWithStartPosition(listOf(item), 0, selected.positionMs)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { reportSelectionError(controller, error); throw error }
            }
        }

        override fun onPlaybackResumption(mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val previous = lastPlayed.current
            Log.i("PlaybackAccess", "resumption request forPlayback=${mediaSession.controllerForCurrentRequest != null} hasLast=${previous != null}")
            // In Media3 1.8.0 the System UI's boot-time metadata query also invokes
            // this callback, outside a player command. It must not resolve a URL,
            // configure the player, or cancel a pending selection.
            if (mediaSession.controllerForCurrentRequest == null) {
                val item = recentMediaItem()
                    ?: return Futures.immediateFailedFuture(NoLastPlayedException())
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(item), 0,
                    if (previous?.completed == true) 0 else progressCache.resumePosition(item.mediaId, previous?.article?.audio?.durationMs ?: 0)))
            }
            retries.begin()
            phoneOwner.clear()
            return selections.select { request ->
                try {
                    val selected = prepareSelection(mediaSession.player, resumptionRequest(previous), request)
                    request.ensureCurrent()
                    mediaSession.player.setPlaybackSpeed(selected.speed)
                    MediaSession.MediaItemsWithStartPosition(listOf(selected.article.toMediaItem(this@PlaybackService,
                        artwork.uri(selected.article.imageUrl))), 0, selected.positionMs)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { reportSelectionError(controller, error); throw error }
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            Futures.immediateFailedFuture(UnsupportedOperationException("Select a single article instead of adding queue items."))

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.uid != Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            if (customCommand.customAction == CANCEL_SELECTION_ACTION) {
                if (args.containsKey(SELECTION_REQUEST) && phoneOwner.matches(args.getLong(SELECTION_REQUEST))) cancelSelections()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction !in setOf(SELECT_ARTICLE_ACTION, VOICE_PLAY_ACTION)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            retries.begin(args.getBoolean(SELECTION_REFRESH))
            val number = args.getLong(SELECTION_REQUEST)
            phoneOwner.begin(number)
            val result = selections.select { request ->
                try {
                    val player = session.player
                    val id = if (customCommand.customAction == VOICE_PLAY_ACTION) {
                        val match = voiceArticle(args.getString(VOICE_QUERY).orEmpty())
                        request.ensureCurrent()
                        match.id
                    } else requireNotNull(args.getString(SELECTION_ID))
                    val selected = prepareSelection(player, PlaybackSelectionRequest(
                        id, refresh = args.getBoolean(SELECTION_REFRESH),
                    ), request)
                    request.ensureCurrent()
                    commitSelection(player, selected)
                    SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putString(SELECTION_ID, selected.article.id) })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val issue = reportSelectionError(controller, error)
                    val extras = Bundle().apply {
                        putString(SELECTION_MESSAGE, issue.message)
                        putString(SELECTION_FAILURE, if (error is NarrationUnavailableException) PlaybackFailure.NO_NARRATION.name else PlaybackFailure.RESOLUTION.name)
                        if (error !is NarrationUnavailableException) {
                            putString(SELECTION_REASON, (if (error is BackendFailure) error.toLoadFailure() else LoadFailure.AUDIO_UNAVAILABLE).name)
                        }
                    }
                    SessionResult(issue.code, extras)
                }
            }
            result.addListener({ phoneOwner.finish(number) }, Executor { it.run() })
            return result
        }

    }

    private suspend fun voiceArticle(query: String): Article = try {
        withTimeout(14_000) { listening.voiceArticle(query) }
    } catch (timeout: TimeoutCancellationException) {
        // A request deadline is an actionable failure; a user cancellation stays cancellation.
        currentCoroutineContext().ensureActive()
        throw java.io.IOException("Voice search timed out.", timeout)
    }

    private suspend fun prepareSelection(player: Player, selection: PlaybackSelectionRequest,
        request: PlaybackSelectionCoordinator.Request): PreparedPlaybackSelection {
        val id = canonicalArticleUrl(selection.articleId)
        val normalized = selection.copy(articleId = id,
            resumeSpeed = selection.resumeSpeed ?: lastPlayed.current?.takeIf { it.article.id == id }?.speed)
        request.ensureCurrent()
        val prepared = try {
            preparePlaybackSelection(normalized, repository, progressCache,
                player.currentMediaItem?.toPlaybackArticle(), player.currentPosition,
                player.playbackParameters.speed, player.playerError == null)
        } catch (error: NarrationUnavailableException) {
            request.ensureCurrent()
            val metadata = repository.getArticleMetadata(normalized.articleId)
            request.ensureCurrent()
            catalog.updateArticle(metadata)
            request.ensureCurrent()
            lastPlayed.updateAvailability(metadata)
            progressCache.attachMetadata(metadata)
            throw error
        }
        request.ensureCurrent()
        val cached = catalog.cachedArticle(prepared.article.id)
            ?: progressCache.articleMetadata(prepared.article.id)
            ?: lastPlayed.current?.article?.takeIf { it.id == prepared.article.id }
        val selected = prepared.copy(article = prepared.article.copy(
            imageUrl = prepared.article.imageUrl ?: cached?.imageUrl,
            author = prepared.article.author ?: cached?.author,
            section = prepared.article.section ?: cached?.section,
        ))
        request.ensureCurrent()
        artwork.prefetch(selected.article.imageUrl)
        catalog.updateArticle(selected.article)
        request.ensureCurrent()
        // Playback may have advanced while the source was resolved.
        return if (normalized.startPositionMs == null && player.currentMediaItem?.mediaId == selected.article.id) {
            selected.copy(positionMs = if (player.playbackState == Player.STATE_ENDED) 0 else resumePlaybackPosition(player.currentPosition, selected.article.audio?.durationMs ?: 0),
                speed = player.playbackParameters.speed)
        } else selected
    }

    private fun cancelSelections() { phoneOwner.clear(); selections.cancel() }

    private suspend fun notifyContinueChanged() {
        val count = listening.continueNodes().size
        session?.notifyChildrenChanged(LIBRARY_CONTINUE_ID, count, null)
    }

    private fun searchCaller(controller: MediaSession.ControllerInfo) = "${controller.uid}:${controller.packageName}"

    private fun recordPlayback(player: Player, explicitSeek: Boolean = false) {
        rememberProgress(player, progressCache, explicitSeek)
        if (player.playerError != null) return
        val article = player.currentMediaItem?.toPlaybackArticle() ?: return
        // Preparing a replacement can emit temporary zero positions; that is not a new replay.
        if (player.playbackState != Player.STATE_IDLE) {
            val duration = player.duration.takeIf { it > 0 } ?: article.audio?.durationMs ?: 0
            completionRecorder.record(article, player.playbackState == Player.STATE_ENDED, player.currentPosition, duration)
        }
        val previous = lastPlayed.current
        lastPlayed.remember(article, player.playbackParameters.speed, player.playbackState == Player.STATE_ENDED)
        if (lastPlayed.current != previous) session?.notifyChildrenChanged(RECENT_ROOT_ID, recentNodes().size, null)
    }

    private fun LibraryNode.withCompletion() = copy(completed = !browsable && historyCache.isListened(id))

    private fun com.rombs.newyorkeraudio.domain.model.Article.toRecentNode() =
        LibraryNode(id, title, author, imageUrl, playable = audioAvailable != false, browsable = false, description = description)

    private fun recentNodes(): List<LibraryNode> =
        lastPlayed.current?.article?.takeIf { it.audioAvailable != false }?.let { listOf(it.toRecentNode()) } ?: emptyList()

    private fun recentMediaItem(): MediaItem? {
        val entry = lastPlayed.current?.takeIf { it.article.audioAvailable != false } ?: return null
        val duration = entry.article.audio?.durationMs ?: 0
        val position = progressCache.resumePosition(entry.article.id, duration)
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, when {
                entry.completed || historyCache.isListened(entry.article.id) -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                position > 0 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
            })
            putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                if (entry.completed || historyCache.isListened(entry.article.id)) 1.0 else if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0)
        }
        val item = entry.article.toRecentNode().copy(completed = entry.completed || historyCache.isListened(entry.article.id))
            .toLibraryMediaItem(artwork)
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    private fun commitSelection(player: Player, selected: PreparedPlaybackSelection) {
        committingPlayback = true
        try {
            if (!selected.reuseActive) {
                recordPlayback(player)
                player.setMediaItem(selected.article.toMediaItem(this, artwork.uri(selected.article.imageUrl)), selected.positionMs)
                player.setPlaybackSpeed(selected.speed)
                player.prepare()
            } else if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            } else if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        } finally { committingPlayback = false }
    }

    private fun recoverCurrentStream() {
        val player = session?.player ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        val controller = session?.controllerForCurrentRequest
        phoneOwner.clear()
        selections.select { request ->
            try {
                val selected = prepareSelection(player, PlaybackSelectionRequest(id, refresh = true), request)
                request.ensureCurrent()
                commitSelection(player, selected)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportSelectionError(controller, error) }
        }
    }

    private fun reportSelectionError(controller: MediaSession.ControllerInfo?, error: Exception): SessionError {
        val failure = (error as? BackendFailure)?.toLoadFailure()
        val code = selectionErrorCode(error)
        val message = when {
            failure != null -> getString(failure.messageResource())
            error is NarrationUnavailableException -> "This article has no narration. Choose another article."
            error is NoLastPlayedException -> "There is no recently played article. Choose an article to start listening."
            error is NoVoiceMatchException -> "No matching article in recent issues or Continue Listening."
            error is AmbiguousVoiceMatchException -> "Several articles match. Try a more specific title."
            error is VoiceQueryTooLongException -> "Try a shorter title or author."
            error is IllegalArgumentException -> "This article is unavailable. Choose another article."
            else -> "Audio is unavailable. Please try again."
        }
        val extras = Bundle().apply {
            putString(SELECTION_FAILURE, if (error is NarrationUnavailableException) PlaybackFailure.NO_NARRATION.name else PlaybackFailure.RESOLUTION.name)
            failure?.let { putString(SELECTION_REASON, it.name) }
        }
        val issue = SessionError(code, message, extras)
        session?.let { if (controller != null) it.sendError(controller, issue) else it.sendError(issue) }
        Log.w("PlaybackAccess", "playback request failed type=${error.javaClass.simpleName} code=$code")
        return issue
    }

    private fun <T : Any> browseFuture(params: LibraryParams?, description: String,
        block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        browseResults.add(future)
        val request = ++browseSequence
        val started = android.os.SystemClock.elapsedRealtime()
        Log.i("PlaybackBrowse", "request=$request start $description")
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withTimeout(15_000) { block() }
                val count = (result.value as? List<*>)?.size
                Log.i("PlaybackBrowse", "request=$request complete code=${result.resultCode} count=$count elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                currentCoroutineContext().ensureActive()
                future.set(result)
            }
            catch (_: TimeoutCancellationException) {
                Log.w("PlaybackBrowse", "request=$request timed out $description")
                val error = SessionError(SessionError.ERROR_IO, "The library request timed out. Please try again.", Bundle.EMPTY)
                future.set(if (params == null) LibraryResult.ofError<T>(error) else LibraryResult.ofError<T>(error, params))
            }
            catch (_: CancellationException) {
                Log.i("PlaybackBrowse", "request=$request cancelled")
                future.cancel(false)
            }
            catch (error: Exception) {
                Log.w("PlaybackBrowse", "request=$request failed type=${error.javaClass.simpleName} $description")
                val code = when {
                    error is IllegalArgumentException || error is java.time.DateTimeException -> SessionError.ERROR_BAD_VALUE
                    error is NoSuchElementException -> SessionError.ERROR_BAD_VALUE
                    error is BackendFailure && error.toLoadFailure() == LoadFailure.SESSION_RECONNECT -> SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
                    error is BackendFailure && error.toLoadFailure() == LoadFailure.SUBSCRIPTION -> SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED
                    else -> SessionError.ERROR_IO
                }
                val message = if (error is BackendFailure) getString(error.toLoadFailure().messageResource())
                    else if (code == SessionError.ERROR_BAD_VALUE) "This library item is unavailable."
                    else "The library is unavailable. Please try again."
                val sessionError = SessionError(code, message, Bundle.EMPTY)
                future.set(if (params == null) LibraryResult.ofError<T>(sessionError) else LibraryResult.ofError<T>(sessionError, params))
            }
        }
        job.invokeOnCompletion { if (it is CancellationException) future.cancel(false) }
        future.addListener({ browseResults.remove(future); if (future.isCancelled) job.cancel() }, Executor { it.run() })
        job.start()
        return future
    }

    override fun onDestroy() {
        cancelSelections()
        browseResults.toList().forEach { it.cancel(false) }
        serviceScope.cancel()
        handler.removeCallbacks(saveProgress)
        session?.run {
            recordPlayback(player)
            player.removeListener(progressListener)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
