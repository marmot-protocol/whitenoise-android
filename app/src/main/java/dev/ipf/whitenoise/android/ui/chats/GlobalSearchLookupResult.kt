package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.privacySafeErrorPresentation
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A transient local lookup outcome, never shared across request ownership. */
internal data class GlobalSearchLookupResult<T>(
    val request: Any,
    val value: T? = null,
    val error: ErrorPresentation? = null,
)

/** Complete loading on failure, but never publish work cancelled by a newer query or account. */
@Composable
internal fun <T> rememberGlobalSearchLookup(
    request: Any,
    enabled: Boolean,
    operationCode: String,
    debounceMillis: Long = CHAT_LIST_SEARCH_DEBOUNCE_MS,
    lookup: suspend () -> T,
): GlobalSearchLookupResult<T>? {
    var result by remember(request, enabled) { mutableStateOf<GlobalSearchLookupResult<T>?>(null) }
    LaunchedEffect(request, enabled) {
        if (!enabled) return@LaunchedEffect
        delay(debounceMillis)
        val completed =
            runCatchingCancellable { lookup() }.fold(
                onSuccess = { GlobalSearchLookupResult<T>(request, value = it) },
                onFailure = {
                    GlobalSearchLookupResult<T>(request = request, error = privacySafeErrorPresentation(operationCode, it))
                },
            )
        currentCoroutineContext().ensureActive()
        result = completed
    }
    return result?.takeIf { enabled && it.request === request }
}
