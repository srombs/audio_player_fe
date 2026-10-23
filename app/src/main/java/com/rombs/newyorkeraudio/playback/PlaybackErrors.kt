package com.rombs.newyorkeraudio.playback

import androidx.media3.session.SessionError
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.rombs.newyorkeraudio.data.network.BackendFailure
import com.rombs.newyorkeraudio.ui.common.LoadFailure
import com.rombs.newyorkeraudio.ui.common.toLoadFailure

@OptIn(UnstableApi::class)
internal fun selectionErrorCode(error: Exception): Int = when {
    error is BackendFailure && error.toLoadFailure() == LoadFailure.SESSION_RECONNECT -> SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
    error is BackendFailure && error.toLoadFailure() == LoadFailure.SUBSCRIPTION -> SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED
    error is NarrationUnavailableException -> SessionError.ERROR_NOT_SUPPORTED
    error is IllegalArgumentException || error is NoLastPlayedException -> SessionError.ERROR_BAD_VALUE
    else -> SessionError.ERROR_IO
}
