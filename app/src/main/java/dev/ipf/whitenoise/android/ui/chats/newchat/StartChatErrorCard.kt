package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** Resolves plain or resource text for display. */
@Composable
private fun AppText.resolveForCompose(): String =
    when (this) {
        is AppText.Plain -> value
        is AppText.Resource ->
            if (args.isEmpty()) {
                stringResource(resId)
            } else {
                stringResource(resId, *args.toTypedArray())
            }
    }

/** Keeps native failure details and canonical retry identity actionable at large text sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun StartChatErrorCard(
    error: StartChatErrorUiState,
    onRetry: () -> Unit,
    onInvite: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val title =
        if (error.retryGroupIdHex != null) {
            stringResource(R.string.new_message_chat_created)
        } else {
            error.title.resolveForCompose()
        }
    val detail = error.detail.resolveForCompose()
    val retryLabel = stringResource(if (error.retryGroupIdHex != null) R.string.new_message_open_chat else R.string.retry)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            when {
                error.missingInbox -> {
                    val instructions = stringResource(R.string.new_message_inbox_instructions)
                    Button(onClick = { onCopy(instructions) }) {
                        Text(stringResource(R.string.new_message_copy_inbox_instructions))
                    }
                }
                error.invitation -> {
                    Button(onClick = onInvite) {
                        val shareIcon = painterResource(R.drawable.ic_share)
                        Icon(shareIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.share))
                    }
                }
                else -> Button(onClick = onRetry) { Text(retryLabel) }
            }
            if (error.missingInbox || error.invitation) {
                TextButton(onClick = onRetry) { Text(retryLabel) }
            }
            if (error.copyable) {
                TextButton(onClick = { onCopy(requireNotNull(error.diagnosticReport)) }) {
                    Text(stringResource(R.string.copy))
                }
            }
        }
    }
}
