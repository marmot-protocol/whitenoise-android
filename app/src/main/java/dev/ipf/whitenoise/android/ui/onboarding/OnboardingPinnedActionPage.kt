@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * The least height the scrolling column keeps beside pinned actions: one text field with its label and supporting
 * text. Below it, the actions scroll with the fields so the keyboard never leaves the field being edited no room.
 */
internal val MinPinnedContentHeight = 120.dp

/**
 * The shared onboarding page: a top bar, a scrolling column bounded to [OnboardingMaxContentWidth] with
 * 16 dp margins, and [actions] pinned above the navigation bar and keyboard at the same width. When the height left
 * after the keyboard cannot hold the actions and [MinPinnedContentHeight] of content, as in a landscape phone with
 * the keyboard open, the actions follow the content inside the scrolling column instead. Without actions nothing is
 * pinned, so no empty action band remains.
 */
@Composable
internal fun OnboardingPinnedActionPage(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    WhiteNoiseScaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = { WhiteNoiseTopBar(title, onBack = onBack) },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .navigationBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // The actions' own height, measured wherever they last rendered; zero until their first layout.
            var actionsHeightPx by remember { mutableIntStateOf(0) }
            val actionsHeight = with(LocalDensity.current) { actionsHeightPx.toDp() }
            val pinned = actions != null && pinsActions(maxHeight, actionsHeight)
            val measuredActions = Modifier.onSizeChanged { actionsHeightPx = it.height }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier
                        .weight(1f)
                        .widthIn(max = OnboardingMaxContentWidth)
                        .fillMaxWidth()
                        .whiteNoiseVerticalScroll(rememberScrollState())
                        .padding(
                            horizontal = WhiteNoiseSpacing.CompactScreenMargin,
                            vertical = WhiteNoiseSpacing.Section,
                        ),
                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                ) {
                    content()
                    if (actions != null && !pinned) OnboardingActionColumn(actions, measuredActions)
                }
                if (pinned && actions != null) OnboardingPinnedActions(actions, measuredActions)
            }
        }
    }
}

/**
 * Whether [available] height, already reduced by the top bar, navigation bar and keyboard, holds the pinned bar
 * (the actions plus its inset on both sides) and still leaves [MinPinnedContentHeight] for the content.
 */
internal fun pinsActions(
    available: Dp,
    actionsHeight: Dp,
): Boolean = available - actionsHeight - WhiteNoiseSpacing.PinnedActionInset * 2 >= MinPinnedContentHeight

/** Keeps the page's actions below the content, centred at the content width with the pinned inset. */
@Composable
private fun OnboardingPinnedActions(
    actions: @Composable ColumnScope.() -> Unit,
    measure: Modifier,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(WhiteNoiseSpacing.PinnedActionInset),
        contentAlignment = Alignment.Center,
    ) {
        OnboardingActionColumn(actions, Modifier.widthIn(max = OnboardingMaxContentWidth).then(measure))
    }
}

/** Stacks the page's actions full width with related spacing, pinned or inline. */
@Composable
private fun OnboardingActionColumn(
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        content = actions,
    )
}
