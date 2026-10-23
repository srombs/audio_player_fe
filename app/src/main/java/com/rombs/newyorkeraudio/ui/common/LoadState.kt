package com.rombs.newyorkeraudio.ui.common

import kotlinx.coroutines.CancellationException
import com.rombs.newyorkeraudio.data.network.BackendFailure
import com.rombs.newyorkeraudio.data.network.BackendFailureKind

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Content<T>(val value: T) : LoadState<T>
    data class Empty<T>(val value: T) : LoadState<T>
    data object Error : LoadState<Nothing>
    data class Failure(val reason: LoadFailure) : LoadState<Nothing>
}

enum class LoadFailure {
    CONNECTION, TIMEOUT, SESSION_RECONNECT, SUBSCRIPTION, SESSION_UNAVAILABLE,
    LIBRARY_UNAVAILABLE, AUDIO_UNAVAILABLE, AUDIO_SERVICE_UNAVAILABLE, NOT_FOUND, SERVER_UNAVAILABLE, INVALID_RESPONSE,
}

internal fun BackendFailure.toLoadFailure(): LoadFailure = when {
    kind == BackendFailureKind.CONNECTION -> LoadFailure.CONNECTION
    kind == BackendFailureKind.TIMEOUT -> LoadFailure.TIMEOUT
    kind == BackendFailureKind.INVALID_RESPONSE -> LoadFailure.INVALID_RESPONSE
    code in setOf("newyorker_session_missing", "newyorker_session_reconnect_required") -> LoadFailure.SESSION_RECONNECT
    code == "newyorker_session_not_subscribed" -> LoadFailure.SUBSCRIPTION
    code?.startsWith("newyorker_session_") == true -> LoadFailure.SESSION_UNAVAILABLE
    code?.startsWith("issue_index_") == true -> LoadFailure.LIBRARY_UNAVAILABLE
    code == "audio_endpoint_unavailable" -> LoadFailure.AUDIO_SERVICE_UNAVAILABLE
    code?.startsWith("audio_") == true -> LoadFailure.AUDIO_UNAVAILABLE
    httpStatus == 404 -> LoadFailure.NOT_FOUND
    else -> LoadFailure.SERVER_UNAVAILABLE
}

internal suspend fun <T> loadContent(
    load: suspend () -> T,
    isEmpty: (T) -> Boolean,
): LoadState<T> = try {
    val value = load()
    if (isEmpty(value)) LoadState.Empty(value) else LoadState.Content(value)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: BackendFailure) {
    LoadState.Failure(error.toLoadFailure())
} catch (_: Exception) {
    LoadState.Error
}
