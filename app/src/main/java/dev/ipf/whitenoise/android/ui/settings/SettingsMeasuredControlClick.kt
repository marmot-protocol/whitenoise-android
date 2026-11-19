package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

/** Retain just a control rectangle, never pixels or a long-lived navigation registry. */
@Composable
internal fun rememberMeasuredControlClick(onClick: (Rect?) -> Unit): Pair<Modifier, () -> Unit> {
    val view = LocalView.current
    val bounds = remember { arrayOf(Rect.Zero) }
    return Modifier.onGloballyPositioned { bounds[0] = it.settingsMorphBounds(view) } to {
        onClick(bounds[0].takeUnless { it.isEmpty })
    }
}

/** A modal launcher retains measured chrome only; source pixels and interaction stay in their host. */
@Composable
internal fun rememberMeasuredContourClick(onClick: (Rect?, SettingsContour?) -> Unit): Pair<Modifier, () -> Unit> {
    val morph = LocalSettingsCardMorph.current
    return rememberMeasuredControlClick { bounds -> onClick(bounds, bounds?.let { morph?.launchContour(it) }) }
}
