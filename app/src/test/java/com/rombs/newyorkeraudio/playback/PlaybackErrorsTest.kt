package com.rombs.newyorkeraudio.playback

import androidx.media3.session.SessionError
import com.rombs.newyorkeraudio.data.network.BackendFailure
import com.rombs.newyorkeraudio.data.network.BackendFailureKind
import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorsTest {
    @Test fun `backend account and subscription failures retain actionable media codes`() {
        assertEquals(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
            selectionErrorCode(BackendFailure(BackendFailureKind.HTTP, 503, "newyorker_session_reconnect_required")))
        assertEquals(SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED,
            selectionErrorCode(BackendFailure(BackendFailureKind.HTTP, 403, "newyorker_session_not_subscribed")))
    }
    @Test fun `no narration and invalid or missing selections are distinct`() {
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, selectionErrorCode(NarrationUnavailableException()))
        assertEquals(SessionError.ERROR_BAD_VALUE, selectionErrorCode(IllegalArgumentException()))
        assertEquals(SessionError.ERROR_BAD_VALUE, selectionErrorCode(NoLastPlayedException()))
    }
    @Test fun `network and resolver failures report io errors`() {
        assertEquals(SessionError.ERROR_IO, selectionErrorCode(BackendFailure(BackendFailureKind.CONNECTION)))
        assertEquals(SessionError.ERROR_IO, selectionErrorCode(BackendFailure(BackendFailureKind.TIMEOUT)))
        assertEquals(SessionError.ERROR_IO, selectionErrorCode(IllegalStateException()))
    }
}
