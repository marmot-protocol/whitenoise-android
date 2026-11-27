@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

internal const val PRIVATE_KEY_REMINDER_TAG = "chats.private_key_reminder"
internal const val PRIVATE_KEY_REMINDER_SAVE_TAG = "chats.private_key_reminder.save"
internal const val PRIVATE_KEY_REMINDER_NOT_NOW_TAG = "chats.private_key_reminder.not_now"

/**
 * Reminds the active account to save its private key once it has a conversation (#4279). Only an account created by
 * Sign up on this device that still holds its own key is reminded, until its key is copied or exported or the user
 * chooses Not now. The chat rows count only while the list is bound to the active account, so a switch never shows
 * one account's reminder with another account's chats. [onSaveKey] opens Profile keys; without it nothing is shown.
 */
@Composable
internal fun PrivateKeyReminder(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    visible: Boolean,
    onSaveKey: (() -> Unit)?,
) {
    if (!visible || onSaveKey == null) return
    val account = appState.activeAccount
    val hasConversations =
        controller.boundAccountRef == appState.activeAccountRef &&
            (controller.items.isNotEmpty() || controller.archivedItems.isNotEmpty())
    if (account == null || !appState.privateKeyReminders.shows(account, hasConversations)) return
    val accountIdHex = account.accountIdHex
    PrivateKeyReminderBanner(
        onSaveKey = { if (appState.activeAccount?.accountIdHex == accountIdHex) onSaveKey() },
        onNotNow = { appState.privateKeyReminders.dismiss(accountIdHex) },
    )
}

/** The reminder's title and reason above its two actions, which wrap below the text at any width or font size. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrivateKeyReminderBanner(
    onSaveKey: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(PRIVATE_KEY_REMINDER_TAG),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = amoledOutlineBorder(),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.private_key_reminder_title),
                modifier = Modifier.padding(end = 12.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.private_key_reminder_body),
                modifier = Modifier.padding(top = 4.dp, end = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                TextButton(onClick = onNotNow, modifier = Modifier.testTag(PRIVATE_KEY_REMINDER_NOT_NOW_TAG)) {
                    Text(stringResource(R.string.private_key_reminder_not_now))
                }
                TextButton(onClick = onSaveKey, modifier = Modifier.testTag(PRIVATE_KEY_REMINDER_SAVE_TAG)) {
                    Text(stringResource(R.string.private_key_reminder_save))
                }
            }
        }
    }
}
