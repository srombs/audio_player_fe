package com.rombs.newyorkeraudio.playback

import android.content.Context

class PreferencesProgressStorage(context: Context) : ProgressStorage {
    private val preferences = context.applicationContext.getSharedPreferences("playback_progress", Context.MODE_PRIVATE)

    override fun read(): String? = try {
        preferences.getString("entries", null)
    } catch (_: ClassCastException) {
        null
    }

    override fun write(value: String) {
        preferences.edit().putString("entries", value).apply()
    }
}
