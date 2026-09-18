package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.R

/** Replaces the last switch toast; a request or failed activation never calls this completion-only surface. */
internal class QuickAccountSwitchNotice(
    private val context: Context,
) {
    private var toast: Toast? = null

    /** Emits configuration-aware confirmation only for the native callback’s verified destination. */
    fun show(actualTitle: String) {
        clear()
        toast =
            Toast
                .makeText(
                    context,
                    context.getString(R.string.quick_account_switched, actualTitle),
                    Toast.LENGTH_SHORT,
                ).also(Toast::show)
    }

    /** Cancel any prior toast so repeated switches cannot queue obsolete identities. */
    fun clear() {
        toast?.cancel()
        toast = null
    }
}

/** Keeps configuration-aware native copy and cancels its short notice when the owning screen disappears. */
@Composable
internal fun rememberQuickAccountSwitchNotice(): QuickAccountSwitchNotice {
    val context = LocalContext.current
    val notice = remember(context) { QuickAccountSwitchNotice(context) }
    DisposableEffect(notice) { onDispose(notice::clear) }
    return notice
}
