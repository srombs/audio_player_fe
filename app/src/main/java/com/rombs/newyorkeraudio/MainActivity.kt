package com.rombs.newyorkeraudio

import android.os.Bundle
import android.content.Intent
import android.util.Log
import android.app.SearchManager
import android.provider.MediaStore
import android.widget.Toast
import java.lang.ref.WeakReference
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rombs.newyorkeraudio.navigation.AppNavigation
import com.rombs.newyorkeraudio.ui.theme.NewYorkerAudioTheme
import com.rombs.newyorkeraudio.playback.ACTION_OPEN_PLAYER

class MainActivity : ComponentActivity() {
    private var openPlayerRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("PlaybackAccess", "phone activity created diagnostics=$PLAYBACK_DIAGNOSTICS_VERSION package=$packageName")
        enableEdgeToEdge()
        val container = (application as NewYorkerAudioApplication).container
        container.playerConnection.connect()
        if (intent.action == ACTION_OPEN_PLAYER) openPlayerRequest++
        if (savedInstanceState == null) handleVoiceIntent(intent)
        setContent {
            NewYorkerAudioTheme {
                AppNavigation(
                    container.repository,
                    playback = container.playerConnection,
                    history = container.historyCache,
                    articleFilterPreferences = container.articleFilterPreferences,
                    openPlayerRequest = openPlayerRequest,
                    onPlayerOpened = { openPlayerRequest = 0 },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as NewYorkerAudioApplication).container.playerConnection.connect()
    }

    override fun onStop() {
        (application as NewYorkerAudioApplication).container.playerConnection.disconnect()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_PLAYER) openPlayerRequest++
        handleVoiceIntent(intent)
    }

    private fun handleVoiceIntent(intent: Intent) {
        if (intent.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(SearchManager.QUERY).orEmpty()
        // Consume this explicit command so activity recreation cannot replay it.
        intent.action = Intent.ACTION_MAIN
        val activity = WeakReference(this)
        (application as NewYorkerAudioApplication).container.playerConnection.playFromSearch(query) { started, message ->
            val owner = activity.get()?.takeUnless { it.isDestroyed || it.isFinishing } ?: return@playFromSearch
            if (started) owner.openPlayerRequest++
            else if (message != null) Toast.makeText(owner, message, Toast.LENGTH_LONG).show()
        }
    }
}
