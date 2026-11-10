@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarLayout
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import kotlin.math.ceil
import kotlin.math.floor

private const val COMPOSER_SHORTCUT_TARGET_DP = 36f
private const val COMPOSER_TOOLBAR_GUTTER_DP = 8f
internal val ComposerShortcutTarget = 48.dp
internal val ComposerShortcutPitch = COMPOSER_SHORTCUT_TARGET_DP.dp
internal val ComposerShortcutIconSize = 24.dp
internal val ComposerToolbarGutter = COMPOSER_TOOLBAR_GUTTER_DP.dp
internal val ComposerToolbarMaximumHeight = 144.dp
internal val ComposerMinimumTextWidth = 96.dp

/** Fixed target footprints make the compact decision independent of the layout it selects. */
internal fun composerToolbarRequiresSeparateRow(
    width: Float,
    actionCount: Int,
    protectedWidth: Float,
    minimumTextWidth: Float,
    layout: ComposerToolbarLayout,
): Boolean =
    layout == ComposerToolbarLayout.AlwaysExpanded ||
        width <
        actionCount * COMPOSER_SHORTCUT_TARGET_DP + protectedWidth + minimumTextWidth + COMPOSER_TOOLBAR_GUTTER_DP

/** Short windows spend spare width on reachable actions without consuming the editor's minimum. */
internal fun composerToolbarCompactWidth(
    width: Float,
    actionCount: Int,
    protectedWidth: Float,
    minimumTextWidth: Float,
): Float {
    val room = width - protectedWidth - minimumTextWidth - COMPOSER_TOOLBAR_GUTTER_DP
    val visibleTargets =
        floor(room / COMPOSER_SHORTCUT_TARGET_DP)
            .coerceAtMost(actionCount.toFloat())
            .toInt()
            .coerceAtLeast(1)
    return minOf(actionCount.coerceAtLeast(0), visibleTargets) * COMPOSER_SHORTCUT_TARGET_DP
}

/** Counts complete target rows, retaining one row for an empty or extremely narrow toolbar. */
internal fun composerToolbarRowCount(
    actionCount: Int,
    width: Float,
): Int =
    ceil(
        actionCount.toFloat() / floor(width / COMPOSER_SHORTCUT_TARGET_DP).toInt().coerceAtLeast(1),
    ).toInt().coerceAtLeast(1)

/** Wrapped rows share the compact horizontal pitch; their 48dp targets overlap like adjacent columns. */
internal val ComposerToolbarRowOverlap = ComposerShortcutPitch - ComposerShortcutTarget

/** Height of [rows] wrapped target rows at the compact vertical pitch. */
internal fun composerToolbarStackHeight(rows: Int): Dp {
    val extraRows = (rows - 1).coerceAtLeast(0)
    return ComposerShortcutTarget + ComposerShortcutPitch * extraRows
}

/** Complete wrapped rows that fit within [limit], so a scrolled toolbar still ends on a whole row. */
internal fun composerToolbarFittingRows(limit: Dp): Int {
    val extraRows = ((limit - ComposerShortcutTarget) / ComposerShortcutPitch).coerceAtLeast(0f)
    return 1 + floor(extraRows).toInt()
}

/** Live adapters are ephemeral. Only [settings] is a persisted preference. */
internal data class ComposerToolbarBindings(
    val settings: ComposerToolbarSettings,
    val shortcuts: List<ComposerShortcut>,
    val attachmentActions: Map<ComposerShortcut, () -> Unit>,
    val enabled: Boolean = true,
    val scrollEnabled: Boolean = enabled,
    val transactionActive: Boolean = false,
    val voiceLauncherVisible: Boolean = true,
    val onCustomize: (() -> Unit)? = null,
    val canActivate: () -> Boolean = { true },
    val onVoiceBounds: (Rect?) -> Unit = {},
    val slotModifier: @Composable (ComposerShortcut) -> Modifier = { Modifier },
    val translate: ComposerTranslateToggle? = null,
)

/** Translate mode's toolbar adapter; [active] tints the pinned button while the preview line is shown. */
internal data class ComposerTranslateToggle(
    val active: Boolean,
    val onToggle: () -> Unit,
)

/**
 * A single root contains every configured slot. Compact centers retain overlapping nominal 48dp touch targets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongParameterList") // The renderer keeps transient callbacks separate from its geometry inputs.
internal fun ComposerConfiguredToolbar(
    bindings: ComposerToolbarBindings,
    width: Dp,
    heightLimit: Dp,
    modifier: Modifier = Modifier,
    singleRowOverflow: Boolean = false,
    defaultFocus: FocusRequester? = null,
    content: @Composable (ComposerShortcut, Modifier) -> Unit,
) {
    val scroll = rememberScrollState()
    val focusTarget = bindings.shortcuts.firstOrNull { it != ComposerShortcut.VoiceNote }
    val latestVoiceBounds by rememberUpdatedState(bindings.onVoiceBounds)
    DisposableEffect(Unit) { onDispose { latestVoiceBounds(null) } }
    val slots: @Composable () -> Unit = {
        bindings.shortcuts.forEach { shortcut ->
            key(shortcut) {
                Box(
                    modifier =
                        Modifier
                            .width(ComposerShortcutPitch)
                            .height(ComposerShortcutTarget)
                            .testTag("composer.shortcut.${shortcut.id}")
                            .then(bindings.slotModifier(shortcut))
                            .then(composerVoiceBoundsModifier(shortcut, bindings.onVoiceBounds)),
                    contentAlignment = Alignment.Center,
                ) {
                    val actionModifier =
                        Modifier
                            .width(ComposerShortcutPitch)
                            .height(ComposerShortcutTarget)
                            .then(
                                if (shortcut == focusTarget &&
                                    defaultFocus != null
                                ) {
                                    Modifier.focusRequester(defaultFocus)
                                } else {
                                    Modifier
                                },
                            ).composerCustomizationEntry(
                                enabled =
                                    bindings.enabled &&
                                        !bindings.transactionActive &&
                                        shortcut != ComposerShortcut.VoiceNote,
                                onCustomize = bindings.onCustomize,
                            )
                    content(shortcut, actionModifier)
                }
            }
        }
    }
    val scrollAllowed = bindings.scrollEnabled && !bindings.transactionActive
    val viewport = modifier.width(width.coerceAtLeast(ComposerShortcutPitch)).testTag("composer.shortcuts")
    if (singleRowOverflow) {
        Row(viewport.horizontalScroll(scroll, enabled = scrollAllowed)) { slots() }
    } else {
        FlowRow(
            modifier =
                viewport
                    .heightIn(max = heightLimit.coerceAtLeast(ComposerShortcutTarget))
                    .fadingVerticalScroll(scroll, enabled = scrollAllowed),
            verticalArrangement = Arrangement.spacedBy(ComposerToolbarRowOverlap),
            maxItemsInEachRow = floor(width.value / COMPOSER_SHORTCUT_TARGET_DP).toInt().coerceAtLeast(1),
        ) { slots() }
    }
}

/** Reports only an unclipped voice target so the recording owner never binds to a partial slot. */
private fun composerVoiceBoundsModifier(
    shortcut: ComposerShortcut,
    report: (Rect?) -> Unit,
): Modifier =
    if (shortcut == ComposerShortcut.VoiceNote) {
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            report(
                bounds.takeIf { it.width >= coordinates.size.width - 1f && it.height >= coordinates.size.height - 1f },
            )
        }
    } else {
        Modifier
    }
