package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/**
 * Inline chat-list search result for a pasted Nostr identifier (#344). Renders
 * the resolve lifecycle: a spinner while a NIP-05 lookup is in flight; once
 * resolved, either the existing 1:1 chat row (when the active account already
 * has a DM with the resolved key — tapping opens that conversation directly,
 * issue #344 step 2) or an "open profile" row (no existing chat — tapping
 * routes into the shared profile sheet, which surfaces the Start-chat
 * affordance, step 3); or an inline error on failure. Plain-text queries never
 * reach here — they keep filtering the list as before.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ChatListIdentifierResult(
    resolution: IdentifierResolution,
    appState: WhiteNoiseAppState,
    accountRef: String?,
    existingDirectChat: (String) -> ChatListItem?,
    onOpenChat: (ChatListItem) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    when (resolution) {
        IdentifierResolution.None -> Unit
        IdentifierResolution.Resolving ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.chat_list_search_resolving),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        is IdentifierResolution.Resolved -> {
            // Surface the existing 1:1 chat as the top result so a single tap
            // opens the conversation (issue #344 step 2). existingDirectChat
            // reads snapshot-backed projected items, so this recomposes if the
            // chat list lands the DM after the npub already resolved. Only when
            // there is no existing DM do we fall back to the profile preview /
            // Start-chat path (step 3).
            val existing = existingDirectChat(resolution.npub)
            if (existing != null) {
                ChatRow(
                    item = existing,
                    appState = appState,
                    accountRef = accountRef,
                    onClick = { onOpenChat(existing) },
                    onOpenProfile = onOpenProfile,
                )
            } else {
                dev.ipf.whitenoise.android.ui.search.GlobalPersonRow(
                    npub = resolution.npub,
                    onOpen = onOpenProfile,
                )
            }
        }
        is IdentifierResolution.Failed ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    resolution.arg
                        ?.let { stringResource(resolution.messageRes, it) }
                        ?: stringResource(resolution.messageRes),
                    color = MaterialTheme.colorScheme.error,
                )
            }
    }
}
