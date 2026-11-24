@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * The shared onboarding page: a top bar, a scrolling column bounded to [OnboardingMaxContentWidth] with
 * 16 dp margins, and [actions] pinned above the navigation bar and keyboard at the same width.
 */
@Composable
internal fun OnboardingPinnedActionPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    WhiteNoiseScaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = { WhiteNoiseTopBar(title, onBack = onBack) },
        bottomBar = { OnboardingPinnedActions(actions) },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = OnboardingMaxContentWidth)
                    .fillMaxSize()
                    .whiteNoiseVerticalScroll(rememberScrollState())
                    .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                content = content,
            )
        }
    }
}

/** Keeps the page's actions above the navigation bar and keyboard, centred at the content width. */
@Composable
private fun OnboardingPinnedActions(actions: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(WhiteNoiseSpacing.PinnedActionInset),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = OnboardingMaxContentWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            content = actions,
        )
    }
}
