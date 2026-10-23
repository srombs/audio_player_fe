package com.rombs.newyorkeraudio.playback.library

import com.rombs.newyorkeraudio.data.repository.TestNarration
import org.junit.Assert.*
import org.junit.Test

class SearchResultsCacheTest {
    private val snapshot = SearchSnapshot(listOf(TestNarration.article))
    @Test fun `caller isolation and latest generation prevent stale results replacing a repeated query`() {
        val cache = SearchResultsCache { 1000 }
        val stale = cache.begin("car", "title")
        val latest = cache.begin("car", "title")
        assertFalse(cache.finish("car", "title", stale, SearchSnapshot(emptyList())))
        assertTrue(cache.finish("car", "title", latest, snapshot))
        assertEquals(snapshot, cache.get("car", "title"))
        assertNull(cache.get("phone", "title"))
    }
    @Test fun `expiry clock rollback eviction and disconnect remove snapshots`() {
        var now = 1000L
        val cache = SearchResultsCache { now }
        cache.finish("car", "expired", cache.begin("car", "expired"), snapshot)
        now += 300_000
        assertNull(cache.get("car", "expired"))
        cache.finish("car", "rollback", cache.begin("car", "rollback"), snapshot)
        now--
        assertNull(cache.get("car", "rollback"))
        repeat(33) { cache.finish("car", "$it", cache.begin("car", "$it"), snapshot) }
        assertNull(cache.get("car", "0"))
        assertEquals(snapshot, cache.get("car", "32"))
        cache.finish("phone", "title", cache.begin("phone", "title"), snapshot)
        cache.removeCaller("car")
        assertNull(cache.get("car", "32"))
        assertEquals(snapshot, cache.get("phone", "title"))
    }
    @Test fun `failed searches retain their failure for result retrieval`() {
        val cache = SearchResultsCache()
        val failure = java.io.IOException("Offline")
        cache.finish("car", "title", cache.begin("car", "title"), SearchSnapshot(emptyList(), failure))
        assertSame(failure, cache.get("car", "title")!!.failure)
    }
}
