package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

internal data class NotificationReadThroughTarget(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
)

/**
 * Commits a notification tap's durable read cursor exactly once, regardless of
 * whether the screen captures its unread boundary, backs out, or is replaced.
 */
internal class NotificationReadThroughCommitter(
    private val target: NotificationReadThroughTarget?,
) {
    private val committed = AtomicBoolean(false)

    fun commit(onCommit: (NotificationReadThroughTarget) -> Unit) {
        val resolved = target ?: return
        if (committed.compareAndSet(false, true)) onCommit(resolved)
    }
}

/** Makes app backgrounding and route disposal durability fallbacks for a pending notification read. */
@Composable
@Suppress("FunctionNaming")
internal fun NotificationReadThroughCommitOnDispose(
    committer: NotificationReadThroughCommitter,
    onCommit: (NotificationReadThroughTarget) -> Unit,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val currentOnCommit by rememberUpdatedState(onCommit)
    DisposableEffect(committer, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) committer.commit(currentOnCommit)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            committer.commit(currentOnCommit)
        }
    }
}
