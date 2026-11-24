package dev.ipf.whitenoise.android.ui.common

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TransientNoticeKind

/** Presents a short confirmation through the app's top notice region; a no-op without a host (previews, tests). */
@Composable
internal fun rememberTransientNotice(): (Int, TransientNoticeKind) -> Unit {
    val host = LocalTopNoticeHost.current
    return remember(host) { { titleRes, kind -> host?.present?.invoke(titleRes, kind) } }
}

/** The one place a transient notice is shown from a Compose-hosted action, so feedback looks the same everywhere. */
@Composable
internal fun rememberTransientNoticeOf(
    @StringRes titleRes: Int,
    kind: TransientNoticeKind = TransientNoticeKind.Confirmation,
): () -> Unit {
    val present = rememberTransientNotice()
    return remember(present, titleRes, kind) { { present(titleRes, kind) } }
}

/**
 * Call after a successful clipboard write. Android 13 and later confirm clipboard writes themselves, so older
 * supported releases get the app's localized Copied notice and newer ones stay quiet.
 */
@Composable
internal fun rememberCopyConfirmation(): () -> Unit {
    val present = rememberTransientNotice()
    return remember(present) {
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                present(R.string.copied, TransientNoticeKind.Confirmation)
            }
        }
    }
}
