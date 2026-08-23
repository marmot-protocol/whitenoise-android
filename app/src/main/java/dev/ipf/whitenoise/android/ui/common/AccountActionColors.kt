package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.resolveActionColorArgb

internal data class AccountActionColors(
    val container: Color,
    val content: Color,
)

@Composable
internal fun accountActionColors(
    appState: WhiteNoiseAppState?,
    accountRef: String? = appState?.activeAccountRef,
): AccountActionColors {
    val scheme = MaterialTheme.colorScheme
    val theme = appState?.let { BubbleTheme.resolve(it.themeMode, isSystemInDarkTheme()) }
    val resolved =
        resolveActionColorArgb(
            customArgb = theme?.let { appState.actionColorArgb(it, accountRef) },
            defaultContainerArgb = scheme.primary.toArgb().toLong() and 0xFFFFFFFFL,
            defaultContentArgb = scheme.onPrimary.toArgb().toLong() and 0xFFFFFFFFL,
            blueFree = theme == BubbleTheme.Amoled,
        )
    return AccountActionColors(
        container = Color(resolved.container),
        content = Color(resolved.content),
    )
}
