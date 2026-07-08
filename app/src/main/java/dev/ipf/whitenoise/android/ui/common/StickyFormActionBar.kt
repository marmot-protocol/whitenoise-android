package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * Bottom action rail for form screens whose primary action must remain tappable
 * while a text field owns the IME. The scrollable content lives above this in the
 * Scaffold body; this bar follows navigation/IME insets like the composer and
 * reserves the Android transient-toast band below the primary action.
 */
@Composable
internal fun StickyFormActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val snackbarBottomInset = LocalSnackbarBottomInset.current
    val density = LocalDensity.current
    var actionBarHeight by remember { mutableStateOf(0.dp) }
    DisposableEffect(actionBarHeight, snackbarBottomInset) {
        if (actionBarHeight <= 0.dp) {
            onDispose { }
        } else {
            val previousInset = snackbarBottomInset.value
            if (actionBarHeight > previousInset) {
                snackbarBottomInset.value = actionBarHeight
            }
            onDispose {
                if (snackbarBottomInset.value == actionBarHeight) {
                    snackbarBottomInset.value = previousInset
                }
            }
        }
    }

    // Report the bar's height net of nav/IME insets: WhiteNoiseSnackbarHost
    // already pads for those, so including them here would lift the toast a
    // second keyboard-height above the bar instead of just clear of it (#796).
    val chromeInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    val chromeBottom = chromeInsets.getBottom(density)
                    actionBarHeight = with(density) { (size.height - chromeBottom).coerceAtLeast(0).toDp() }
                },
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(
                        start = Dimens.spaceLg,
                        top = Dimens.spaceMd,
                        end = Dimens.spaceLg,
                        bottom = Dimens.spaceMd,
                    ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
