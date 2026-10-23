package com.rombs.newyorkeraudio.playback

import android.content.Context

internal class PreferencesLastPlayedStorage(context: Context) : ProgressStorage {
    private val preferences = context.applicationContext.getSharedPreferences("last_played", Context.MODE_PRIVATE)
    override fun read(): String? = try { preferences.getString("record", null) } catch (_: ClassCastException) { null }
    override fun write(value: String) { preferences.edit().putString("record", value).apply() }
}
