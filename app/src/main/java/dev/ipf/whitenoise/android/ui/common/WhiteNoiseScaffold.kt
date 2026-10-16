package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.layout.Layout
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll

/** The pinned top-bar scroll behaviour a [WhiteNoiseScaffold] shares with its [WhiteNoiseTopBar]. */
@OptIn(ExperimentalMaterial3Api::class)
val LocalWhiteNoiseHeaderScroll = staticCompositionLocalOf<TopAppBarScrollBehavior?> { null }

/**
 * Material scaffold whose top bar receives the content's scroll so it can tint when content moves under it.
 *
 * With [topNoticeSlot] the scaffold also hosts the screen's temporary-banner slot directly below [topBar], so app
 * confirmations and snackbars land below this screen's bars and its content absorbs their height (#3312). A
 * screen that already places its own slot below its bars, as the conversation does, turns this one off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun WhiteNoiseScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    topNoticeSlot: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    CompositionLocalProvider(LocalWhiteNoiseHeaderScroll provides scrollBehavior) {
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                ScaffoldTopBarWithNoticeSlot(
                    contentWindowInsets = contentWindowInsets,
                    topNoticeSlot = topNoticeSlot,
                    topBar = topBar,
                )
            },
            bottomBar = bottomBar,
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
 * A scaffold's top-bar slot that hosts the screen's temporary-banner slot directly below [topBar] (#3312).
 *
 * A material scaffold hands its content the top window inset only when the top-bar slot measures to nothing, and
 * this slot always measures, so a screen with no top bar would otherwise lose its status-bar and cutout padding.
 * When [topBar] composes nothing, this layout pads [contentWindowInsets]' top itself, above the banner slot, so
 * the content keeps the inset a plain scaffold would give it and a banner still lands below the status bar. A bar
 * that is present owns that inset, as every app bar does, and nothing is added. Screens that use a plain material
 * scaffold with a bar that is sometimes absent can place this in their own top-bar slot.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
fun ScaffoldTopBarWithNoticeSlot(
    contentWindowInsets: WindowInsets,
    topNoticeSlot: Boolean = true,
    topBar: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { topBar() }
            Box { if (topNoticeSlot) TopNoticeSlot() }
        },
    ) { measurables, constraints ->
        val bar = measurables[0].measure(constraints)
        val slot = measurables[1].measure(constraints)
        val inset = if (bar.height == 0) contentWindowInsets.getTop(this) else 0
        layout(maxOf(bar.width, slot.width), inset + bar.height + slot.height) {
            bar.placeRelative(0, inset)
            slot.placeRelative(0, inset + bar.height)
        }
    }
}

/**
 * Vertical scroll that also drives the pinned top bar's scrolled colour, so a plain column behaves like the lazy
 * lists under the same header. The header offset resets when the content leaves composition.
 * Forms can disable masking while retaining that header contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Modifier.whiteNoiseVerticalScroll(
    state: ScrollState = rememberScrollState(),
    fadeEnabled: Boolean = true,
): Modifier {
    val behavior = LocalWhiteNoiseHeaderScroll.current
    DisposableEffect(behavior) { onDispose { behavior?.state?.contentOffset = 0f } }
    LaunchedEffect(state, behavior) {
        snapshotFlow { state.value }.collect { behavior?.state?.contentOffset = -it.toFloat() }
    }
    return if (fadeEnabled) fadingVerticalScroll(state) else verticalScroll(state)
}
