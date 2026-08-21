package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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

/** Makes route disposal the final durability fallback for a pending notification read. */
@Composable
@Suppress("FunctionNaming")
internal fun NotificationReadThroughCommitOnDispose(
    committer: NotificationReadThroughCommitter,
    onCommit: (NotificationReadThroughTarget) -> Unit,
) {
    val currentOnCommit by rememberUpdatedState(onCommit)
    DisposableEffect(committer) {
        onDispose { committer.commit(currentOnCommit) }
    }
}
