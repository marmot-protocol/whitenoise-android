package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/**
 * Bottom inset the global snackbar host should reserve to clear any
 * persistent bottom chrome on the currently visible surface (e.g. the
 * conversation composer). Held as a `MutableState<Dp>` so screens
 * BELOW the host in the composition tree can push their chrome height
 * up to the parent-owned state — a plain CompositionLocal would only
 * flow values DOWN and couldn't reach the host. Default `0.dp` keeps
 * non-composer surfaces unaffected.
 *
 * See issue #122 (post-invite-accept toast overlapping message input).
 */
internal val LocalSnackbarBottomInset =
    staticCompositionLocalOf<MutableState<Dp>> {
        // Safe fallback for hosts rendered outside the app shell —
        // androidTest fixtures, Compose previews, or any future caller
        // that uses [WhiteNoiseSnackbarHost] without going through
        // [WhiteNoiseApp]'s provider. The host reads `.value`, so the
        // factory must return a real MutableState rather than throw;
        // 0.dp matches the no-composer surface behaviour.
        mutableStateOf(0.dp)
    }

@Composable
fun WhiteNoiseSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = { SwipeDismissibleSnackbar(it) },
) {
    val extraInset = LocalSnackbarBottomInset.current.value
    SnackbarHost(
        hostState = hostState,
        modifier =
            modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp + extraInset),
        snackbar = snackbar,
    )
}

/**
 * Visuals for toasts pushed through `WhiteNoiseAppState.present`. Carries the
 * emit site's explicit [copyable] flag so [SwipeDismissibleSnackbar] can gate
 * its Copy affordance on the toast's kind (error/diagnostic vs. success or
 * transient confirmation) instead of guessing from the message body (#796).
 */
internal data class ToastSnackbarVisuals(
    override val message: String,
    val copyable: Boolean = false,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Short
}

/**
 * Whether a snackbar should render the Copy affordance (#543, #796). Only
 * non-actionable toasts explicitly flagged copyable at their emit site — error
 * and diagnostic strings — qualify; plain `showSnackbar(message)` calls and
 * actionable snackbars never do.
 */
internal fun snackbarShowsCopyAffordance(visuals: SnackbarVisuals): Boolean =
    visuals.actionLabel == null && (visuals as? ToastSnackbarVisuals)?.copyable == true

/**
 * A [Snackbar] the user can swipe away horizontally (issue #352). Material 3's
 * [SnackbarHost] does not wire up swipe-to-dismiss itself, so a snackbar
 * otherwise sits until it times out or an action is tapped. Wrapping it in a
 * [SwipeToDismissBox] restores the standard Material gesture: a horizontal
 * swipe in either direction calls [SnackbarData.dismiss], which resolves the
 * host's suspending `showSnackbar` with [SnackbarResult.Dismissed] (never
 * [SnackbarResult.ActionPerformed]), so any actionable snackbar treats a
 * swipe-away as "ignored", not as tapping the action.
 *
 * The box is re-keyed per [SnackbarData] so its dismiss state resets for each
 * new snackbar; without that, a settled-away state would carry over and the
 * next snackbar would never render.
 */
@Composable
fun SwipeDismissibleSnackbar(data: SnackbarData) {
    val dismissState =
        key(data) {
            rememberSwipeToDismissBoxState(
                confirmValueChange = { target ->
                    if (target != SwipeToDismissBoxValue.Settled) {
                        data.dismiss()
                    }
                    // Let the box animate the snackbar off in the swipe
                    // direction; data.dismiss() pulls it from the host so it
                    // does not re-enter once the gesture settles.
                    true
                },
            )
        }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {},
    ) {
        if (snackbarShowsCopyAffordance(data.visuals)) {
            // Error/diagnostic toasts flagged copyable at their emit site get
            // a discoverable Copy affordance in the free action slot, plus a
            // SelectionContainer for long-press copy (issues #543, #796).
            val clipboard = LocalClipboardManager.current
            val message = data.visuals.message
            Snackbar(
                action = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(message)) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy),
                        )
                    }
                },
            ) {
                SelectionContainer {
                    Text(message)
                }
            }
        } else {
            // Everything else — actionable snackbars (e.g. the chat-list
            // "Undo", whose action slot and SnackbarResult semantics must stay
            // untouched) and non-copyable toasts like success confirmations —
            // renders plain, with the message text still selectable.
            SelectionContainer {
                Snackbar(snackbarData = data)
            }
        }
    }
}
