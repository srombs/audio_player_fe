package com.rombs.newyorkeraudio.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException

enum class BackendFailureKind { CONNECTION, TIMEOUT, HTTP, INVALID_RESPONSE }

/** Keeps recovery codes available for the next step's UI, without displaying raw server bodies. */
class BackendFailure(
    val kind: BackendFailureKind,
    val httpStatus: Int? = null,
    val code: String? = null,
    val action: String? = null,
    cause: Throwable? = null,
) : Exception("Backend request failed: $kind", cause)

internal suspend fun <T> backendRequest(request: suspend () -> T): T {
    try {
        return request().also { currentCoroutineContext().ensureActive() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: HttpException) {
        currentCoroutineContext().ensureActive()
        val detail = try {
            error.response()?.errorBody()?.use { body ->
                BackendClient.json.decodeFromString<BackendErrorDto>(body.string()).detail
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
        throw BackendFailure(BackendFailureKind.HTTP, error.code(), detail?.code, detail?.action, error)
    } catch (error: InterruptedIOException) {
        currentCoroutineContext().ensureActive()
        throw BackendFailure(BackendFailureKind.TIMEOUT, cause = error)
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw BackendFailure(BackendFailureKind.CONNECTION, cause = error)
    } catch (error: SerializationException) {
        throw BackendFailure(BackendFailureKind.INVALID_RESPONSE, cause = error)
    }
}
