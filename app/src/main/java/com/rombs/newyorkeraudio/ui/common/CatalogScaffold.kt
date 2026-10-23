package com.rombs.newyorkeraudio.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.rombs.newyorkeraudio.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (onHistory != null) {
                        TextButton(onClick = onHistory, modifier = Modifier.testTag("toolbar-history")) {
                            Text(stringResource(R.string.listening_history))
                        }
                    }
                    if (onRefresh != null) {
                        TextButton(onClick = onRefresh, modifier = Modifier.testTag("toolbar-refresh")) {
                            Text(stringResource(R.string.refresh))
                        }
                    }
                },
            )
        },
        content = content,
    )
}

@Composable
fun <T> LoadedContent(
    state: LoadState<T>,
    onRetry: () -> Unit,
    modifier: Modifier,
    loadingMessage: String = stringResource(R.string.loading),
    content: @Composable (T, Modifier) -> Unit,
) {
    when (state) {
        LoadState.Loading -> StateMessage(modifier, "loading") {
            CircularProgressIndicator()
            Text(loadingMessage)
        }
        LoadState.Error -> StateMessage(modifier, "error") {
            Text(stringResource(R.string.load_error), textAlign = TextAlign.Center)
            Button(onClick = onRetry, modifier = Modifier.testTag("retry")) {
                Text(stringResource(R.string.retry))
            }
        }
        is LoadState.Failure -> StateMessage(modifier, "error") {
            Text(stringResource(state.reason.messageResource()), textAlign = TextAlign.Center)
            Button(onClick = onRetry, modifier = Modifier.testTag("retry")) {
                Text(stringResource(R.string.retry))
            }
        }
        is LoadState.Empty -> content(state.value, modifier)
        is LoadState.Content -> content(state.value, modifier)
    }
}

internal fun LoadFailure.messageResource(): Int = when (this) {
    LoadFailure.CONNECTION -> R.string.connection_error
    LoadFailure.TIMEOUT -> R.string.timeout_error
    LoadFailure.SESSION_RECONNECT -> R.string.session_reconnect_error
    LoadFailure.SUBSCRIPTION -> R.string.subscription_error
    LoadFailure.SESSION_UNAVAILABLE -> R.string.session_unavailable_error
    LoadFailure.LIBRARY_UNAVAILABLE -> R.string.library_unavailable_error
    LoadFailure.AUDIO_UNAVAILABLE -> R.string.audio_resolution_error
    LoadFailure.AUDIO_SERVICE_UNAVAILABLE -> R.string.audio_service_unavailable_error
    LoadFailure.NOT_FOUND -> R.string.content_not_found_error
    LoadFailure.SERVER_UNAVAILABLE -> R.string.service_unavailable_error
    LoadFailure.INVALID_RESPONSE -> R.string.invalid_response_error
}

@Composable
private fun StateMessage(modifier: Modifier, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
fun EmptyCatalogMessage(message: String, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.padding(vertical = 24.dp).testTag("empty")
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onRefresh, modifier = Modifier.testTag("refresh")) {
            Text(stringResource(R.string.refresh))
        }
    }
}
