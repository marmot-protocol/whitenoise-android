package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AccountHistoryNotices
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Binds the active account's account-wide history notices to this composition:
 * read on entry, re-read on each change event, released on account or runtime change.
 */
@Composable
internal fun rememberAccountHistoryNotices(appState: WhiteNoiseAppState): AccountHistoryNotices? {
    val accountRef = appState.activeAccountRef
    val notices =
        remember(accountRef, appState.runtimeGeneration) {
            accountRef?.let { account ->
                AccountHistoryNotices(
                    accountRef = account,
                    readNotices = { ref -> appState.marmotIo { historyNotices(ref) } },
                    dismissNotice = { ref, noticeId -> appState.marmotIo { dismissHistoryNotice(ref, noticeId) } },
                )
            }
        }
    LaunchedEffect(notices) {
        val owner = notices ?: return@LaunchedEffect
        val subscription = runCatchingCancellable { appState.marmotIo { subscribeEvents() } }.getOrNull()
        try {
            owner.observe { subscription?.next() }
        } finally {
            subscription?.let { withContext(NonCancellable + Dispatchers.IO) { it.destroy() } }
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
