package com.rombs.newyorkeraudio.playback

import org.junit.Assert.*
import org.junit.Test

class StreamRetryPolicyTest {
    @Test fun `expired or removed source gets only one automatic refresh`() {
        for (status in listOf(401, 403, 404, 410)) {
            val policy = StreamRetryPolicy()
            assertTrue(policy.tryAutomatic(status, true))
            assertFalse(policy.tryAutomatic(status, true))
        }
    }

    @Test fun `paused playback and unrelated failures do not consume a retry`() {
        val policy = StreamRetryPolicy()
        for (status in listOf(null, 500, 503)) assertFalse(policy.tryAutomatic(status, true))
        assertFalse(policy.tryAutomatic(403, false))
        assertTrue(policy.tryAutomatic(403, true))
    }

    @Test fun `manual refreshed selection cannot enter another automatic refresh loop`() {
        val policy = StreamRetryPolicy()
        policy.begin(refresh = true)
        assertFalse(policy.tryAutomatic(403, true))
    }

    @Test fun `a new explicit selection gets its own bounded retry budget`() {
        val policy = StreamRetryPolicy()
        assertTrue(policy.tryAutomatic(403, true))
        policy.begin()
        assertTrue(policy.tryAutomatic(403, true))
        assertFalse(policy.tryAutomatic(403, true))
    }
}
