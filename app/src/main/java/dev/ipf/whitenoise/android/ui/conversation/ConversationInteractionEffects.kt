package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter

@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun DismissMessageActionMenuOnScroll(
    listState: LazyListState,
    onDismiss: () -> Unit,
) {
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collect { currentOnDismiss.value() }
    }
}

@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun DismissMessageActionMenuOnDispose(
    messageId: String,
    isOpen: Boolean,
    onDismiss: () -> Unit,
) {
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(messageId, isOpen) {
        onDispose {
            if (isOpen) currentOnDismiss.value()
        }
    }
}
