package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.NoticeTier
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Close
import dev.ipf.whitenoise.android.ui.icons.filled.ContentCopy
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder

/**
 * The app's snackbar presentation (#3312). It is placed in a top region below the owning screen's bars, so it
 * needs no navigation-bar, keyboard or composer clearance: the screen's content absorbs its height and bottom
 * chrome keeps its own size and position. Margins belong to each shown snackbar, so an empty host takes no space.
 */
@Composable
fun WhiteNoiseSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = { SwipeDismissibleSnackbar(it) },
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.fillMaxWidth(),
        snackbar = { data ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = SNACKBAR_REGION_VERTICAL_MARGIN)) { snackbar(data) }
        },
    )
}

/** The gap a top snackbar keeps from the bars above it and the content below it. */
private val SNACKBAR_REGION_VERTICAL_MARGIN = 4.dp

/**
 * Visuals for toasts pushed through `WhiteNoiseAppState.present`. Carries the
 * emit site's explicit [copyable] flag and privacy-safe [copyText] payload so
 * [SwipeDismissibleSnackbar] never falls back to copying user-facing text.
 */
internal data class ToastSnackbarVisuals(
    override val message: String,
    val copyable: Boolean = false,
    val tier: NoticeTier = if (copyable) NoticeTier.ActionableError else NoticeTier.Confirmation,
    val copyText: String? = null,
    val details: String? = null,
    override val actionLabel: String? = null,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = tier == NoticeTier.ActionableError
    override val duration: SnackbarDuration =
        if (tier == NoticeTier.ActionableError) {
            SnackbarDuration.Indefinite
        } else {
            SnackbarDuration.Short
        }
}

/**
 * Whether a snackbar should render the Copy affordance (#543, #796). Only
 * diagnostic toasts explicitly carrying a privacy-safe report qualify; plain
 * `showSnackbar(message)` calls and legacy copyable flags without a report do
 * not.
 */
internal fun snackbarShowsCopyAffordance(visuals: SnackbarVisuals): Boolean =
    visuals.actionLabel == null &&
        (visuals as? ToastSnackbarVisuals)?.let {
            it.details == null && it.copyable && !it.copyText.isNullOrBlank()
        } == true

/**
 * The one snackbar surface treatment: AMOLED resolves `inverseSurface` to pure
 * black, so a subtle 1dp `outlineVariant` stroke on the standard snackbar shape
 * keeps the transient surface readable against a black canvas. Light and
 * standard dark render the Material 3 defaults unchanged.
 */
@Composable
internal fun Modifier.snackbarSurfaceBoundary(): Modifier = amoledSurfaceBorder(SnackbarDefaults.shape)

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
        val deletionVisuals = (data.visuals as? ToastSnackbarVisuals)?.takeIf { it.details != null }
        if (deletionVisuals != null) {
            LocalDeleteSnackbar(data, deletionVisuals)
        } else if (snackbarShowsCopyAffordance(data.visuals)) {
            // Error/diagnostic toasts flagged copyable at their emit site get
            // a discoverable Copy affordance and a separate Dismiss control.
            // SelectionContainer still permits long-press copy (issues #543, #796).
            val clipboard = LocalClipboardManager.current
            val copyText = requireNotNull((data.visuals as ToastSnackbarVisuals).copyText)
            Snackbar(
                modifier = Modifier.snackbarSurfaceBoundary(),
                action = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy),
                        )
                    }
                },
                dismissAction = {
                    IconButton(onClick = data::dismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss))
                    }
                },
            ) {
                SelectionContainer {
                    Text(data.visuals.message)
                }
            }
        } else {
            // Everything else — actionable snackbars (e.g. the chat-list
            // "Undo", whose action slot and SnackbarResult semantics must stay
            // untouched) and non-copyable toasts like success confirmations —
            // renders plain, with the message text still selectable.
            SelectionContainer {
                Snackbar(snackbarData = data, modifier = Modifier.snackbarSurfaceBoundary())
            }
        }
    }
}
