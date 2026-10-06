@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/** Emits the picker's dismiss action through a close control with a localized accessibility label. */
@Composable
internal fun ShareChatPickerCloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
    }
}

/**
 * Summarizes the pending share without changing its payload. [compact] limits
 * the preview to one line so the destination list remains usable above the IME;
 * the regular layout allows three lines and both ellipsize overflow.
 */
@Composable
internal fun ShareChatPickerPreview(
    previewText: String,
    attachmentCount: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = modifier,
    ) {
        Text(
            text = sharePickerPreviewText(previewText, attachmentCount),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (compact) 1 else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Chooses a localized text-and-attachment summary, a pluralized attachment-only
 * summary, or the supplied text. An empty share yields no preview text.
 */
@Composable
private fun sharePickerPreviewText(
    previewText: String,
    attachmentCount: Int,
): String =
    when {
        previewText.isNotEmpty() && attachmentCount > 0 ->
            stringResource(R.string.share_preview_text_and_attachments, previewText, attachmentCount)
        previewText.isNotEmpty() -> previewText
        attachmentCount > 0 ->
            pluralStringResource(
                R.plurals.share_preview_attachments_count,
                attachmentCount,
                attachmentCount,
            )
        else -> ""
    }

/**
 * Sending-account row shared by the inbound-share and in-app forward pickers:
 * one visible owner above the chat list, opening the account sheet when more
 * than one signed-in signing account exists.
 */
@Composable
internal fun ChatPickerSendingAccountRow(
    appState: WhiteNoiseAppState,
    account: AccountSummaryFfi,
    multipleAccounts: Boolean,
    onOpenSelector: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    testTag: String = SHARE_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG,
) {
    val accountTitle = appState.networkDisplayName(account.accountIdHex)
    Surface(
        onClick = { if (multipleAccounts) onOpenSelector() },
        enabled = multipleAccounts,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = modifier.testTag(testTag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(
                title = accountTitle,
                seed = account.accountIdHex,
                size = if (compact) 32.dp else 40.dp,
                pictureUrl = appState.contactAvatarSource(account.accountIdHex),
            )
            ShareChatPickerAccountIdentity(
                appState = appState,
                account = account,
                accountTitle = accountTitle,
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            if (multipleAccounts) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.share_choose_sending_account),
                )
            }
        }
    }
}

/** Name and short identity block for one sending account. */
@Composable
internal fun ShareChatPickerAccountIdentity(
    appState: WhiteNoiseAppState,
    account: AccountSummaryFfi,
    accountTitle: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (compact) {
            val sendingAs = stringResource(R.string.share_sending_as_value, accountTitle)
            EmojiLabel(
                text = accountTitle,
                modifier = Modifier.semantics { contentDescription = sendingAs },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = stringResource(R.string.share_sending_as),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                accountTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                appState.shortNpub(account.accountIdHex),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
