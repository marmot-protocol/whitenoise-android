package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlClick

/** A modal launcher retains measured chrome only; source pixels and interaction stay in their host. */
@Composable
internal fun rememberMeasuredContourClick(onClick: (Rect?, SettingsContour?) -> Unit): Pair<Modifier, () -> Unit> {
    val morph = LocalSettingsCardMorph.current
    return rememberMeasuredControlClick { bounds -> onClick(bounds, bounds?.let { morph?.launchContour(it) }) }
}
