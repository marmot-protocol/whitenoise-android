package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The canonical horizontal separator. One color choice — `outlineVariant`,
 * Material 3's own divider token — so separators read subtle-but-visible in
 * Light, Dark, and AMOLED (where it is the same dim token the AMOLED surface
 * borders use) instead of each call site re-deriving contrast by eye.
 * Markdown-rendered rules inside message bubbles stay content-relative and are
 * deliberately not migrated: they sit on bubble container colors, not chrome.
 */
@Suppress("FunctionNaming")
@Composable
internal fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.outlineVariant)
}
