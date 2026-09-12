@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.reserveSnackbarSpace
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll

/**
 * Pinned prototype SetupPage geometry: 520 dp column, 16 dp margins, 16 dp fields and 8 dp actions.
 * Recovery can offer more actions than the prototype. Its action region scrolls within 45% of the
 * available window so long labels, landscape and IME never consume the entire decision viewport.
 */
@Composable
internal fun SetupPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val actionLimit = maxHeight * 0.45f
        WhiteNoiseScaffold(
            topBar = { WhiteNoiseTopBar(title, onBack) },
            bottomBar = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .reserveSnackbarSpace()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 520.dp)
                            .fillMaxWidth()
                            .heightIn(max = actionLimit)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = actions,
                    )
                }
            },
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxSize()
                        .whiteNoiseVerticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
        }
    }
}
