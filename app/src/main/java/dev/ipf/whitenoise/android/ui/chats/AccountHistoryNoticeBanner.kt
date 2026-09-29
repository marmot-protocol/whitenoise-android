package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AccountHistoryNotices
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val HISTORY_NOTICE_SUBSCRIPTION_RETRY_MS = 2_000L

/**
 * Binds the active account's account-wide history notices to this composition:
 * read on entry, re-read on each change event, released on account or runtime change.
 */
@Composable
internal fun rememberAccountHistoryNotices(appState: WhiteNoiseAppState): AccountHistoryNotices? {
    val accountRef = appState.activeAccountRef
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val runtimeGeneration = appState.runtimeGeneration
    val notices =
        remember(accountRef, runtimeGeneration) {
            accountRef?.let { account ->
                AccountHistoryNotices(
                    accountRef = account,
                    readNotices = { ref -> appState.marmotIo { historyNotices(ref) } },
                    dismissNotice = { ref, noticeId -> appState.marmotIo { dismissHistoryNotice(ref, noticeId) } },
                    reportDismissFailure = { failure ->
                        if (appState.activeAccountRef == account && appState.runtimeGeneration == runtimeGeneration) {
                            appState.presentFailure(
                                R.string.history_notice_dismiss_failed,
                                "HISTORY_NOTICE_DISMISS",
                                failure,
                            )
                        }
                    },
                )
            }
        }
    LaunchedEffect(notices, lifecycle) {
        val owner = notices ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val subscription = runCatchingCancellable { appState.marmotIo { subscribeEvents() } }.getOrNull()
                try {
                    if (subscription == null) {
                        owner.refresh()
                    } else {
                        owner.observe { withContext(Dispatchers.IO) { subscription.next() } }
                    }
                } finally {
                    subscription?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { it.destroy() } } }
                }
                // A closed stream or a transient subscription failure must not strand a visible account.
                delay(HISTORY_NOTICE_SUBSCRIPTION_RETRY_MS)
            }
        }
    }
    return notices
}

/**
 * Tells the user that automatic recovery could not prove this account's history
 * complete. Only the user may dismiss it; the chat list stays fully usable.
 */
@Composable
@Suppress("FunctionNaming")
internal fun AccountHistoryNoticeBanner(
    dismissing: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = amoledOutlineBorder(),
    ) {
        BoxWithConstraints {
            val stackAction =
                LocalDensity.current.fontScale >= 1.5f ||
                    MaterialTheme.typography.bodyMedium.fontSize >= 20.sp ||
                    maxWidth < 320.dp
            if (stackAction) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 4.dp)) {
                    Text(
                        stringResource(R.string.account_history_may_be_incomplete),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDismiss, enabled = !dismissing, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.account_history_may_be_incomplete),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDismiss, enabled = !dismissing) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            }
        }
    }
}
