package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ChatListIdentifierSearch

/** Directory success cannot hide a failed HTTPS identity lookup in any recipient picker. */
internal fun recipientAddressLookupFailed(
    query: String,
    state: RecipientPreviewState,
): Boolean =
    state == RecipientPreviewState.Invalid &&
        ChatListIdentifierSearch.classify(query) is ChatListIdentifierSearch.Identifier.Nip05

/** Shared group-picker warning; retry is distinct from selection and respects an in-flight mutation. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun RecipientAddressLookupFeedback(busy: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(stringResource(R.string.user_search_address_unverified))
        TextButton(onClick = onRetry, enabled = !busy, modifier = Modifier.testTag("address.retry")) {
            Text(stringResource(R.string.retry))
        }
    }
}
