package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/** Collapses per-frame IME inset changes to a boolean so keyboard animation recomposes only on visibility edges. */
@Composable
internal fun rememberDictationImeVisible(): Boolean {
    val imeInsets = WindowInsets.ime
    val navigationBarsInsets = WindowInsets.navigationBars
    val density = LocalDensity.current
    val visible by remember(imeInsets, navigationBarsInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > navigationBarsInsets.getBottom(density) }
    }
    return visible
}
