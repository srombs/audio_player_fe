package com.rombs.newyorkeraudio

import android.app.Application
import android.os.Process
import android.util.Log

const val PLAYBACK_DIAGNOSTICS_VERSION = "dhu-voice-13"

class NewYorkerAudioApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        Log.i("PlaybackAccess", "application started diagnostics=$PLAYBACK_DIAGNOSTICS_VERSION " +
            "package=$packageName pid=${Process.myPid()} uid=${Process.myUid()} " +
            "version=${BuildConfig.VERSION_NAME} build=${BuildConfig.BUILD_TYPE}")
    }
}
