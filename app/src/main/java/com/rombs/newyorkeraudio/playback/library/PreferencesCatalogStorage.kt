package com.rombs.newyorkeraudio.playback.library

import android.content.Context

class PreferencesCatalogStorage(context: Context) : CatalogStorage {
    private val preferences = context.applicationContext.getSharedPreferences("car_catalog", Context.MODE_PRIVATE)
    override fun read(): String? = preferences.getString("snapshot", null)
    override fun write(value: String) { preferences.edit().putString("snapshot", value).apply() }
}
