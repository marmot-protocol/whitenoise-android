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
 * The window y, in pixels, that an explanation's sheet keeps its bottom edge at, read when a held control is
 * explained. The composer provides its top edge, so the sheet opens above it without taking focus from the editor
 * and the keyboard stays open. `null` opens the modal sheet at the bottom of the window.
 */
internal val LocalExplanationBottomEdge = staticCompositionLocalOf<(() -> Int)?> { null }

/** The one explanation on screen, and the window y its sheet stays above, if any. */
@Stable
internal class ExplanationHostState {
    var shown by mutableStateOf<Explanation?>(null)
        private set

    /** The window y the shown explanation's sheet keeps its bottom edge at, or `null` for the modal sheet. */
    var bottomWindowY by mutableStateOf<Int?>(null)
        private set

    /** Shows [explanation], replacing any other, above [bottomWindowY] when given. */
    fun show(
        explanation: Explanation,
        bottomWindowY: Int? = null,
    ) {
        this.bottomWindowY = bottomWindowY
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
 */
@Composable
internal fun Modifier.explainOnLongPress(
    explanation: Explanation?,
    enabled: Boolean = true,
    holdToExplain: Boolean = true,
    beforeShow: () -> Unit = {},
    additionalActions: List<CustomAccessibilityAction> = emptyList(),
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
                explanations.show(current, latestBottomEdge?.invoke())
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
    return if (holdToExplain) described.explainOnHold(haptics, show) else described
}

/** Observes the hold at Initial so the explanation replaces the child's click or long press. */
private fun Modifier.explainOnHold(
    haptics: HapticFeedback,
    show: () -> Unit,
): Modifier =
    pointerInput(Unit) {
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
            if (released == null) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                show()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
