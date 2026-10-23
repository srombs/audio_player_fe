package com.rombs.newyorkeraudio.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Called on the UI thread; each refresh replaces the previous request. */
internal class ScreenLoader<T>(
    private val scope: CoroutineScope,
    private val load: suspend () -> T,
    private val isEmpty: (T) -> Boolean,
    private val onLoaded: (T) -> Unit = {},
) {
    private val mutableState = MutableStateFlow<LoadState<T>>(LoadState.Loading)
    val state = mutableState.asStateFlow()
    private var request: Job? = null

    fun refresh() {
        request?.cancel()
        mutableState.value = LoadState.Loading
        request = scope.launch {
            val result = loadContent(load, isEmpty)
            // A repository may finish after cancellation; it must not publish stale data.
            currentCoroutineContext().ensureActive()
            mutableState.value = result
            when (result) {
                is LoadState.Content -> onLoaded(result.value)
                is LoadState.Empty -> onLoaded(result.value)
                else -> Unit
            }
        }
    }
}
