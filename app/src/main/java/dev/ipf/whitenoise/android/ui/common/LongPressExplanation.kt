package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingBottomSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** What a held control does, in the words of its visible [title]. */
@Immutable
internal data class Explanation(
    val title: String,
    val body: String,
)

/** Shows one explanation at a time; `null` outside an [ExplanationHost], which disables holding to explain. */
internal val LocalExplanationHost = staticCompositionLocalOf<ExplanationHostState?> { null }

/**
 * The window y, in pixels, that an explanation's sheet keeps its bottom edge at, read for as long as the explanation
 * shows. The composer provides its top edge, so the sheet opens above it without taking focus from the editor, the
 * keyboard stays open, and the sheet follows the composer when the keyboard or the window changes. `null` opens the
 * modal sheet at the bottom of the window.
 */
internal val LocalExplanationBottomEdge = staticCompositionLocalOf<(() -> Int)?> { null }

/** The one explanation on screen, and the window y its sheet stays above, if any. */
@Stable
internal class ExplanationHostState {
    var shown by mutableStateOf<Explanation?>(null)
        private set

    /** Reads the window y the shown explanation's sheet keeps its bottom edge at, or `null` for the modal sheet. */
    var bottomEdge by mutableStateOf<(() -> Int)?>(null)
        private set

    /** The shown explanation's sheet's bottom edge now, observed through [bottomEdge] by whoever reads it. */
    val bottomWindowY: Int?
        get() = bottomEdge?.invoke()

    /** Shows [explanation], replacing any other, above the window y [bottomEdge] reads when given. */
    fun show(
        explanation: Explanation,
        bottomEdge: (() -> Int)? = null,
    ) {
        this.bottomEdge = bottomEdge
        shown = explanation
    }

    fun dismiss() {
        shown = null
    }
}

@Composable
internal fun rememberExplanationHostState(): ExplanationHostState = remember { ExplanationHostState() }

/** Draws [state]'s sheet; a hidden host, such as one under the app lock, drops it instead. */
@Suppress("FunctionNaming")
@Composable
internal fun ExplanationSheetHost(
    state: ExplanationHostState,
    visible: Boolean = true,
) {
    if (!visible) {
        LaunchedEffect(state) { state.dismiss() }
        return
    }
    state.shown?.let { explanation -> ExplanationSheet(explanation, state.bottomWindowY, onDismiss = state::dismiss) }
}

/** Provides holding to explain for [content] with its own sheet, for surfaces outside the app root. */
@Suppress("FunctionNaming")
@Composable
internal fun ExplanationHost(content: @Composable () -> Unit) {
    val state = rememberExplanationHostState()
    CompositionLocalProvider(LocalExplanationHost provides state, content = content)
    ExplanationSheetHost(state)
}

/**
 * The modal sheet at the window's bottom, or, with [bottomWindowY], a keyboard-preserving sheet whose bottom stays
 * at that window y. The modal sheet's focusable window would take focus from a focused editor and close the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun ExplanationSheet(
    explanation: Explanation,
    bottomWindowY: Int?,
    onDismiss: () -> Unit,
) {
    val sheet = Modifier.testTag("explanation.sheet")
    if (bottomWindowY == null) {
        WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss, modifier = sheet) {
            ExplanationContent(explanation, onDismiss)
        }
    } else {
        KeyboardPreservingBottomSheet(
            paneTitle = explanation.title,
            onDismissRequest = onDismiss,
            modifier = sheet,
            bottomWindowY = bottomWindowY,
        ) {
            ExplanationContent(explanation, onDismiss)
        }
    }
}

/** The explanation's title with a close button over its body, which scrolls, edge-faded, when its height is bounded. */
@Suppress("FunctionNaming")
@Composable
private fun ColumnScope.ExplanationContent(
    explanation: Explanation,
    onDismiss: () -> Unit,
) {
    WhiteNoiseSheetHeader(explanation.title, onClose = onDismiss)
    Text(
        explanation.body,
        modifier =
            Modifier
                .weight(1f, fill = false)
                .fadingVerticalScroll(rememberScrollState())
                .padding(horizontal = WhiteNoiseSpacing.Section)
                .padding(bottom = WhiteNoiseSpacing.Section)
                .testTag("explanation.body"),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Holding this element shows [explanation] instead of running its click; TalkBack gets an Explain action.
 *
 * The press is observed at Initial so the hold cancels the child's normal click, including the
 * trusted-system Paste menu. [beforeShow] lets a transient surface, such as a menu, close first.
 * [additionalActions] stay available to accessibility services whether or not holding is enabled.
 * [holdToExplain] = false keeps the Explain action but leaves the hold gesture to the element.
 * [holdClaim] leaves a hold to the child that claimed it with [claimsHold], such as a tooltip anchor.
 */
@Composable
internal fun Modifier.explainOnLongPress(
    explanation: Explanation?,
    enabled: Boolean = true,
    holdToExplain: Boolean = true,
    beforeShow: () -> Unit = {},
    additionalActions: List<CustomAccessibilityAction> = emptyList(),
    holdClaim: HoldClaim? = null,
): Modifier {
    val host = LocalExplanationHost.current
    val bottomEdge = LocalExplanationBottomEdge.current
    val haptics = LocalHapticFeedback.current
    val explainLabel = stringResource(R.string.explanation_action_explain)
    val latestExplanation by rememberUpdatedState(explanation)
    val latestBeforeShow by rememberUpdatedState(beforeShow)
    val latestHost by rememberUpdatedState(host)
    val latestBottomEdge by rememberUpdatedState(bottomEdge)
    val show: () -> Unit = {
        latestExplanation?.let { current ->
            latestHost?.let { explanations ->
                latestBeforeShow()
                explanations.show(current, latestBottomEdge)
            }
        }
    }
    if (!enabled || host == null || explanation == null) {
        return if (additionalActions.isEmpty()) this else semantics { customActions = additionalActions }
    }
    val described =
        semantics {
            customActions = listOf(CustomAccessibilityAction(explainLabel) { show().let { true } }) + additionalActions
        }
    return if (holdToExplain) described.explainOnHold(haptics, show, holdClaim) else described
}

/**
 * Observes the hold at Initial so the explanation replaces the child's click or long press, unless a child
 * claimed this press through [holdClaim].
 */
private fun Modifier.explainOnHold(
    haptics: HapticFeedback,
    show: () -> Unit,
    holdClaim: HoldClaim?,
): Modifier =
    pointerInput(holdClaim) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val released =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        finished = change == null ||
                            !change.pressed ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    }
                    true
                }
            // Children see the press after this Initial-pass observer, so the claim is read once the hold is long.
            if (released == null && holdClaim?.isClaimed(down.id) != true) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                show()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }

/**
 * Records which press a child's own long press owns, so a hold-to-explain parent can leave it alone. Compose's hit
 * test decides which presses reach the child, widened touch targets included, so the parent never guesses geometry.
 */
@Stable
internal class HoldClaim {
    private var claimed: PointerId? = null

    /** Marks [id] as the child's press. */
    fun claim(id: PointerId) {
        claimed = id
    }

    /** Whether the child received the press [id]; pointer ids never repeat, so an older claim never matches. */
    fun isClaimed(id: PointerId): Boolean = claimed == id
}

/** Claims, through [claim], every press Compose delivers to this element, without consuming it. */
internal fun Modifier.claimsHold(claim: HoldClaim): Modifier =
    pointerInput(claim) {
        awaitEachGesture {
            claim.claim(awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).id)
        }
    }
