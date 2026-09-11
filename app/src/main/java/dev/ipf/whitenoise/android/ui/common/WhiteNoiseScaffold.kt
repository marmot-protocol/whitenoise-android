package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll

/** The pinned top-bar scroll behaviour a [WhiteNoiseScaffold] shares with its [WhiteNoiseTopBar]. */
@OptIn(ExperimentalMaterial3Api::class)
val LocalWhiteNoiseHeaderScroll = staticCompositionLocalOf<TopAppBarScrollBehavior?> { null }

/** Material scaffold whose top bar receives the content's scroll so it can tint when content moves under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun WhiteNoiseScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    CompositionLocalProvider(LocalWhiteNoiseHeaderScroll provides scrollBehavior) {
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = containerColor,
            contentColor = contentColor,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    }
}

/**
 * Vertical scroll that also drives the pinned top bar's scrolled colour, so a plain column behaves like the lazy
 * lists under the same header. The header offset resets when the content leaves composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Modifier.whiteNoiseVerticalScroll(state: ScrollState = rememberScrollState()): Modifier {
    val behavior = LocalWhiteNoiseHeaderScroll.current
    DisposableEffect(behavior) { onDispose { behavior?.state?.contentOffset = 0f } }
    LaunchedEffect(state, behavior) {
        snapshotFlow { state.value }.collect { behavior?.state?.contentOffset = -it.toFloat() }
    }
    return verticalScroll(state)
}
