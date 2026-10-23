package com.rombs.newyorkeraudio.playback

/** One automatic fresh-source attempt per selection; a manual fresh-source request consumes it too. */
internal class StreamRetryPolicy {
    private var used = false
    fun begin(refresh: Boolean = false) { used = refresh }
    fun tryAutomatic(httpStatus: Int?, wantsPlayback: Boolean): Boolean {
        if (used || !wantsPlayback || httpStatus !in setOf(401, 403, 404, 410)) return false
        used = true
        return true
    }
}
