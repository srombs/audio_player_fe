package com.rombs.newyorkeraudio.playback.library

import com.rombs.newyorkeraudio.domain.model.Article

internal data class SearchSnapshot(val articles: List<Article>, val failure: Exception? = null)

/** Caller-separated snapshots keep pagination stable; only the latest identical query can publish. */
internal class SearchResultsCache(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Key(val caller: String, val query: String)
    private data class Entry(val generation: Long, val createdAt: Long, val snapshot: SearchSnapshot? = null)
    private val entries = linkedMapOf<Key, Entry>()
    private var sequence = 0L

    fun begin(caller: String, query: String): Long {
        require(query.length <= 256)
        val key = Key(caller, query)
        entries.remove(key)
        val generation = ++sequence
        entries[key] = Entry(generation, clock())
        while (entries.size > 32) entries.remove(entries.keys.first())
        return generation
    }

    fun finish(caller: String, query: String, generation: Long, snapshot: SearchSnapshot): Boolean {
        val key = Key(caller, query)
        if (entries[key]?.generation != generation) return false
        entries[key] = Entry(generation, clock(), snapshot)
        return true
    }

    fun get(caller: String, query: String): SearchSnapshot? {
        val key = Key(caller, query)
        val entry = entries[key] ?: return null
        val elapsed = clock() - entry.createdAt
        if (elapsed < 0 || elapsed >= 5 * 60_000L) { entries.remove(key); return null }
        return entry.snapshot
    }

    fun removeCaller(caller: String) { entries.keys.removeAll { it.caller == caller } }
}
