@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.composerDraftJumpShortcuts
import dev.ipf.whitenoise.android.ui.common.SlotIconButton
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress

internal const val COMPOSER_DRAFT_TOP_TAG = "composer-draft-top"
internal const val COMPOSER_DRAFT_BOTTOM_TAG = "composer-draft-bottom"

/** Shortcuts every configured composer offers; the draft jumps still wait for an overflowing draft. */
internal val composerAlwaysAvailableShortcuts =
    setOf(ComposerShortcut.Emoji, ComposerShortcut.Paste, ComposerShortcut.More) + composerDraftJumpShortcuts

/** Keyboard focus enters the toolbar on a control that is always present. */
internal val ComposerShortcut.takesDefaultFocus: Boolean
    get() = this != ComposerShortcut.VoiceNote && this !in composerDraftJumpShortcuts

/**
 * Short drafts keep the jump slots' width for text. Admission only follows settled geometry, and
 * admitting the slots can only shrink the editor, so an admitted draft never revokes itself.
 */
internal fun ComposerToolbarBindings.withDraftJumps(admitted: Boolean): ComposerToolbarBindings =
    if (admitted) this else copy(shortcuts = shortcuts - composerDraftJumpShortcuts)

/**
 * The draft scroll both jump slots follow. A jump that had keyboard focus leaves the row once the draft
 * reaches its edge, so it hands focus to the opposite jump, which that same scroll has just revealed.
 */
@Stable
internal class ComposerDraftJumps(
    val scrollState: ScrollState,
) {
    private val top = FocusRequester()
    private val bottom = FocusRequester()

    /** The jump that should take focus as soon as it is composed, if any. */
    var focusHandoff by mutableStateOf<ComposerShortcut?>(null)

    /** The requester attached to [shortcut]'s button. */
    fun focusRequester(shortcut: ComposerShortcut): FocusRequester =
        when (shortcut) {
            ComposerShortcut.JumpBottom -> bottom
            else -> top
        }
}

/**
 * An admitted jump keeps its slot so the row never shifts while reading, and shows its button only
 * while the draft is scrolled away from that edge. The slot modifier supplies hold-to-explain.
 */
@Composable
internal fun ComposerDraftEdgeAction(
    shortcut: ComposerShortcut,
    jumps: ComposerDraftJumps,
    enabled: Boolean,
    modifier: Modifier,
    onJump: (toEnd: Boolean) -> Unit,
) {
    val toEnd = shortcut == ComposerShortcut.JumpBottom
    val scrollState = jumps.scrollState
    val awayFromEdge by remember(scrollState, toEnd) {
        derivedStateOf { if (toEnd) scrollState.value < scrollState.maxValue else scrollState.value > 0 }
    }
    if (!awayFromEdge) return
    var focused by remember { mutableStateOf(false) }
    val handoff = jumps.focusHandoff
    LaunchedEffect(handoff) {
        if (handoff == shortcut) {
            jumps.focusHandoff = null
            jumps.focusRequester(shortcut).requestFocus()
        }
    }
    SlotIconButton(
        onClick = {
            if (focused) jumps.focusHandoff = if (toEnd) ComposerShortcut.JumpTop else ComposerShortcut.JumpBottom
            onJump(toEnd)
        },
        enabled = enabled,
        modifier =
            modifier
                .focusRequester(jumps.focusRequester(shortcut))
                .onFocusChanged { focused = it.hasFocus }
                .testTag(if (toEnd) COMPOSER_DRAFT_BOTTOM_TAG else COMPOSER_DRAFT_TOP_TAG),
    ) {
        Icon(
            painterResource(shortcut.iconRes),
            contentDescription = stringResource(shortcut.labelRes),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ComposerShortcutIconSize),
        )
    }
}

/** The fixed jump-to-top button of composers without a configurable toolbar. */
@Composable
internal fun ComposerLegacyDraftTop(
    width: Dp,
    onClick: () -> Unit,
) {
    ComposerToolbarTargets {
        SlotIconButton(
            onClick = onClick,
            modifier =
                Modifier
                    .width(width)
                    .height(48.dp)
                    .testTag(COMPOSER_DRAFT_TOP_TAG)
                    .explainOnLongPress(
                        composerExplanation(R.string.scroll_to_top, R.string.explanation_composer_draft_top),
                    ),
        ) {
            Icon(
                painterResource(R.drawable.ic_jump_to_edge),
                contentDescription = stringResource(R.string.scroll_to_top),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
