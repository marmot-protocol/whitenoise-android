package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.delay

internal val LocalSettingsSearchTarget = staticCompositionLocalOf<SettingsSearchEntry?> { null }
internal val LocalSettingsSearchRevealConsumed = staticCompositionLocalOf { false }
internal val LocalSettingsSearchOnReveal = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalSettingsSearchItemKey = staticCompositionLocalOf<Any?> { null }

/** A visual/accessibility reveal, never an invocation of the existing control's action. */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsSearchRowReveal(
    rowKey: String?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable () -> Unit,
) {
    val target = LocalSettingsSearchTarget.current
    val itemKey = LocalSettingsSearchItemKey.current
    val selected = target != null && target.rowKey == rowKey && target.itemKey == itemKey
    val consumed = LocalSettingsSearchRevealConsumed.current
    val onReveal = LocalSettingsSearchOnReveal.current
    val view = LocalView.current
    val revealedText = stringResource(R.string.settings_search_revealed, target?.let { stringResource(it.titleRes) }.orEmpty())
    val requester = remember { BringIntoViewRequester() }
    var highlighted by remember(target?.id) { mutableStateOf(selected && !consumed) }
    LaunchedEffect(selected, target?.id) {
        if (selected && !consumed) {
            androidx.compose.runtime.withFrameNanos { }
            requester.bringIntoView()
            view.announceForAccessibility(revealedText)
            onReveal()
            delay(SETTINGS_SEARCH_HIGHLIGHT_MILLIS)
            highlighted = false
        }
    }
    Box(
        modifier
            .bringIntoViewRequester(requester)
            .then(if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
            .semantics {
                if (highlighted) {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = revealedText
                }
            },
    ) { content() }
}

private const val SETTINGS_SEARCH_HIGHLIGHT_MILLIS = 2200L
