package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals

/** The viewer owns a separate Dialog window: group-image failures must be painted in its own snackbar. */
@Composable
@Suppress("FunctionNaming")
internal fun ViewerGroupPictureFailureNotice(
    notice: ToastMessage?,
    hostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val visuals =
        remember(notice, configuration) {
            notice?.let {
                ToastSnackbarVisuals(
                    message = listOfNotNull(it.title.resolve(context), it.detail?.resolve(context)).joinToString("\n"),
                    copyable = it.copyable,
                    tier = it.tier,
                    copyText = it.diagnosticReport,
                )
            }
        }
    LaunchedEffect(visuals) { if (visuals != null) hostState.showSnackbar(visuals) }
    DisposableEffect(visuals) {
        onDispose {
            if (hostState.currentSnackbarData?.visuals === visuals) hostState.currentSnackbarData?.dismiss()
        }
    }
}
