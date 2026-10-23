package com.rombs.newyorkeraudio.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.rombs.newyorkeraudio.ui.common.LoadFailure
import com.google.common.util.concurrent.ListenableFuture
import com.rombs.newyorkeraudio.domain.model.Article
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

private val phoneRequestNumbers = AtomicLong()

/** All calls run on the main thread. Releasing this controller never stops the service's player. */
@OptIn(UnstableApi::class)
class PlayerConnection(context: Context, private val progressCache: PlaybackProgressCache) : PlaybackController {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var pendingArticle: Article? = null
    private var commandFailure: Article? = null
    private var failure: PlaybackFailure? = null
    private var resolutionFailure: LoadFailure? = null
    private var selection: Article? = null
    private var pendingRefresh = false
    private var requestNumber = 0
    private var activeRequest: Long? = null
    private var pendingVoice: Pair<String, (Boolean, String?) -> Unit>? = null
    private val lifecycle = PhoneControllerLifecycle()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playerError == null && player.currentMediaItem?.mediaId == commandFailure?.id) {
                commandFailure = null
                resolutionFailure = null
            }
            publish()
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (controller != null) {
                publish()
                handler.postDelayed(this, 500)
            }
        }
    }

    fun connect() {
        lifecycle.attach()
        if (controller != null) { handler.removeCallbacks(ticker); handler.post(ticker); return }
        if (future != null) return
        Log.i("PlaybackBrowse", "phone controller connection starting")
        mutableState.value = state.value.copy(isConnecting = true, error = null)
        val connection = try {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onError(controller: MediaController, sessionError: SessionError) {
                    if (this@PlayerConnection.controller !== controller || controller.playerError == null) return
                    commandFailure = controller.currentMediaItem?.toPlaybackArticle() ?: return
                    failure = sessionError.extras.getString(SELECTION_FAILURE)?.let { value -> PlaybackFailure.entries.firstOrNull { it.name == value } }
                        ?: PlaybackFailure.STREAM
                    resolutionFailure = sessionError.extras.getString(SELECTION_REASON)?.let { value -> LoadFailure.entries.firstOrNull { it.name == value } }
                    publish()
                }
                override fun onDisconnected(controller: MediaController) {
                    if (this@PlayerConnection.controller === controller) {
                        disconnect(force = true)
                        mutableState.value = state.value.copy(error = PlaybackFailure.CONNECTION)
                    }
                }
            }).buildAsync()
        } catch (error: Exception) {
            Log.w("PlaybackBrowse", "phone connection setup failed type=${error.javaClass.simpleName} reason=${error.message?.take(200)}")
            mutableState.value = state.value.copy(isConnecting = false, isConnected = false, error = PlaybackFailure.CONNECTION)
            failPendingConnection()
            return
        }
        future = connection
        connection.addListener({
            if (future !== connection) return@addListener
            try {
                controller = connection.get().also { it.addListener(listener) }
                Log.i("PlaybackBrowse", "phone controller connected")
                handler.post(ticker)
                publish()
                pendingArticle?.let { article -> pendingArticle = null; requestPlayback(article, pendingRefresh) }
                pendingVoice?.let { voice -> pendingVoice = null; playFromSearch(voice.first, voice.second) }
                detachIfSettled()
            } catch (error: Exception) {
                val cause = error.cause ?: error
                Log.w("PlaybackBrowse", "phone connection failed type=${cause.javaClass.simpleName} reason=${cause.message?.take(200)}")
                handler.removeCallbacks(ticker)
                controller?.removeListener(listener)
                future = null
                controller = null
                MediaController.releaseFuture(connection)
                mutableState.value = state.value.copy(isConnecting = false, isConnected = false, error = PlaybackFailure.CONNECTION)
                failPendingConnection()
            }
        }, executor)
    }

    fun disconnect(force: Boolean = false) {
        if (!force && !lifecycle.requestDetach(hasPendingWork())) { handler.removeCallbacks(ticker); return }
        controller?.let { rememberProgress(it, progressCache) }
        handler.removeCallbacks(ticker)
        controller?.removeListener(listener)
        val previous = future
        future = null
        controller = null
        pendingArticle = null
        pendingVoice = null
        activeRequest = null
        commandFailure = null
        selection = null
        requestNumber++
        previous?.let { MediaController.releaseFuture(it) }
        mutableState.value = state.value.copy(isConnected = false, isConnecting = false)
    }

    private fun hasPendingWork() = activeRequest != null || pendingArticle != null || pendingVoice != null
    private fun detachIfSettled() { if (lifecycle.shouldDetach(hasPendingWork())) disconnect(force = true) }
    private fun failPendingConnection() {
        val voice = pendingVoice
        pendingVoice = null
        pendingArticle = null
        activeRequest = null
        if (lifecycle.allowsUiCallbacks) voice?.second?.invoke(false, "Unable to connect to audio. Please try again.")
    }

    override fun play(article: Article) = requestPlayback(article, refresh = false)

    override fun retry(article: Article) = requestPlayback(article, refresh = true)

    private fun requestPlayback(article: Article, refresh: Boolean) {
        pendingVoice = null
        val player = controller
        if (player == null) {
            pendingArticle = article
            pendingRefresh = refresh
            connect()
            return
        }
        val request = ++requestNumber
        val nonce = phoneRequestNumbers.incrementAndGet()
        activeRequest = nonce
        commandFailure = null
        resolutionFailure = null
        selection = article
        publish()
        try {
            val args = Bundle().apply {
                putString(SELECTION_ID, article.id)
                putLong(SELECTION_REQUEST, nonce)
                putBoolean(SELECTION_REFRESH, refresh || (player.currentMediaItem?.mediaId == article.id && player.playerError != null))
            }
            val result = player.sendCustomCommand(SELECT_ARTICLE_COMMAND, args)
            result.addListener({
                if (request != requestNumber || controller !== player) return@addListener
                selection = null
                activeRequest = null
                try {
                    if (!result.isCancelled) {
                        val response = result.get()
                        // Media3 reports service-side cancellation as INFO_SKIPPED. A car
                        // selection or Pause can supersede this request; show the shared player.
                        if (response.resultCode != SessionResult.RESULT_SUCCESS && response.resultCode != SessionResult.RESULT_INFO_SKIPPED) {
                            commandFailure = article
                            failure = response.extras.getString(SELECTION_FAILURE)?.let { PlaybackFailure.valueOf(it) } ?: PlaybackFailure.RESOLUTION
                            resolutionFailure = response.extras.getString(SELECTION_REASON)?.let { LoadFailure.valueOf(it) }
                        }
                    }
                } catch (_: Exception) {
                    commandFailure = article
                    failure = PlaybackFailure.CONNECTION
                }
                publish()
                detachIfSettled()
            }, executor)
        } catch (_: Exception) {
            activeRequest = null
            selection = null
            commandFailure = article
            failure = PlaybackFailure.CONNECTION
            publish()
            detachIfSettled()
        }
    }

    /** Activity voice intents use the same service-owned workflow as car play-from-search. */
    fun playFromSearch(query: String, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        pendingArticle = null
        val player = controller
        if (player == null) { pendingVoice = query to onComplete; connect(); return }
        val request = ++requestNumber
        val nonce = phoneRequestNumbers.incrementAndGet()
        activeRequest = nonce
        selection = null
        commandFailure = null
        resolutionFailure = null
        val args = Bundle().apply { putString(VOICE_QUERY, query); putLong(SELECTION_REQUEST, nonce) }
        val result = try { player.sendCustomCommand(VOICE_PLAY_COMMAND, args) }
            catch (_: Exception) {
                activeRequest = null
                if (lifecycle.allowsUiCallbacks) onComplete(false, "Unable to start audio. Please try again.")
                detachIfSettled()
                return
            }
        result.addListener({
            if (request != requestNumber || controller !== player) return@addListener
            activeRequest = null
            try {
                val response = result.get()
                publish()
                if (lifecycle.allowsUiCallbacks) onComplete(response.resultCode == SessionResult.RESULT_SUCCESS,
                    if (response.resultCode == SessionResult.RESULT_INFO_SKIPPED) null else response.extras.getString(SELECTION_MESSAGE))
            } catch (_: Exception) { if (lifecycle.allowsUiCallbacks) onComplete(false, "Unable to start audio. Please try again.") }
            finally { detachIfSettled() }
        }, executor)
    }

    override fun cancelPendingPlayback() {
        if (!lifecycle.allowsUiCallbacks) return // Activity destruction/rotation is not a cancellation intent.
        activeRequest?.let { number ->
            controller?.sendCustomCommand(CANCEL_SELECTION_COMMAND, Bundle().apply { putLong(SELECTION_REQUEST, number) })
        }
        activeRequest = null
        requestNumber++
        pendingArticle = null
        pendingVoice = null
        selection = null
        publish()
    }

    override fun cancelPendingPlaybackFor(articleId: String) {
        if (selection?.id == articleId || pendingArticle?.id == articleId) cancelPendingPlayback()
    }

    override fun savedPosition(article: Article): Long =
        progressCache.resumePosition(article.id, article.audio?.durationMs ?: 0)

    override fun pause() { cancelPendingPlayback(); controller?.pause(); publish() }
    override fun seekTo(positionMs: Long) {
        controller?.takeIf { it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
            ?.seekTo(clampPosition(positionMs, state.value.durationMs))
        publish()
    }
    override fun skipBackward() = seekTo(skipPosition(state.value.positionMs, state.value.durationMs, false))
    override fun skipForward() = seekTo(skipPosition(state.value.positionMs, state.value.durationMs, true))
    override fun setSpeed(speed: Float) {
        if (speed in PLAYBACK_SPEEDS) controller?.setPlaybackSpeed(speed)
        publish()
    }

    private fun publish() {
        val player = controller ?: return
        selection?.let {
            val sameArticle = player.currentMediaItem?.mediaId == it.id
            mutableState.value = state.value.copy(mediaId = it.id, article = it, isConnected = true,
                isConnecting = false, isPlaying = false, playWhenReady = true, isBuffering = true,
                positionMs = if (sameArticle) player.currentPosition.coerceAtLeast(0) else savedPosition(it),
                durationMs = it.audio?.durationMs ?: 0, canSeek = false, error = null, resolutionFailure = null)
            return
        }
        commandFailure?.let {
            mutableState.value = state.value.copy(mediaId = it.id, article = it, isPlaying = false,
                playWhenReady = false, isBuffering = false, error = failure, resolutionFailure = resolutionFailure)
            return
        }
        val item = player.currentMediaItem
        val duration = player.duration.takeIf { it > 0 } ?: item?.mediaMetadata?.extras?.getLong(DURATION_METADATA_KEY)?.coerceAtLeast(0) ?: 0
        mutableState.value = PlaybackState(
            mediaId = item?.mediaId,
            isConnected = true,
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = clampPosition(player.currentPosition, duration),
            durationMs = duration,
            speed = player.playbackParameters.speed,
            canSeek = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            error = if (player.playerError != null) PlaybackFailure.STREAM else null,
            article = item?.toPlaybackArticle(),
        )
    }
}
