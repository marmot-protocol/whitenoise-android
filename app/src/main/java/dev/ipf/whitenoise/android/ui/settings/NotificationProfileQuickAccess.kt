package dev.ipf.whitenoise.android.ui.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationProfileTileAddOutcome
import dev.ipf.whitenoise.android.notifications.NotificationProfilesState
import dev.ipf.whitenoise.android.notifications.requestAddNotificationProfileTile
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.notificationProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opt-in shortcuts for switching profiles. [onAddTile] is null when the tile is already in the panel or Android
 * cannot offer it; [tileHint] then explains where to add it by hand.
 */
internal data class NotificationProfileQuickAccess(
    val showInChatList: Boolean,
    val onShowInChatListChange: (Boolean) -> Unit,
    val onAddTile: (() -> Unit)?,
    val tileHint: Boolean,
)

/** Builds the quick-access controls for the stored [state]; every write runs off the main thread. */
@Composable
internal fun rememberNotificationProfileQuickAccess(
    appState: WhiteNoiseAppState,
    state: NotificationProfilesState,
): NotificationProfileQuickAccess {
    val context = LocalContext.current
    val store = appState.notificationProfiles

    fun write(change: () -> Boolean) {
        appState.launchMutation {
            val saved = withContext(Dispatchers.IO) { runCatching(change).getOrDefault(false) }
            if (!saved) appState.present(R.string.notification_profile_change_failed)
        }
    }
    val canOffer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val onAddTile: (() -> Unit)? =
        if (canOffer && !state.tileAdded) {
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestAddNotificationProfileTile(context) { outcome ->
                        when (outcome) {
                            NotificationProfileTileAddOutcome.ADDED -> write { store.setTileAdded(true) }
                            NotificationProfileTileAddOutcome.DECLINED -> Unit
                            NotificationProfileTileAddOutcome.FAILED ->
                                appState.present(R.string.notification_profile_add_tile_failed)
                        }
                    }
                }
            }
        } else {
            null
        }
    return NotificationProfileQuickAccess(
        showInChatList = state.showInChatList,
        onShowInChatListChange = { show -> write { store.setShowInChatList(show) } },
        onAddTile = onAddTile,
        tileHint = !canOffer,
    )
}

/** "Show in chat list" and, where Android offers it, "Add to Quick Settings". */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfileQuickAccessSection(quickAccess: NotificationProfileQuickAccess) {
    SettingsSection(stringResource(R.string.notification_profiles_quick_access))
    SettingsGroup {
        row("chat_list") { context ->
            SettingsSwitch(
                context = context,
                title = stringResource(R.string.notification_profile_show_in_chat_list),
                subtitle = stringResource(R.string.notification_profile_show_in_chat_list_detail),
                checked = quickAccess.showInChatList,
                onCheckedChange = quickAccess.onShowInChatListChange,
                modifier = Modifier.testTag("notification-profiles.show-in-chat-list"),
                leading = { SettingsLeadingIcon(R.drawable.ic_chat) },
            )
        }
        quickAccess.onAddTile?.let { onAddTile ->
            row("tile") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.notification_profile_add_tile),
                    subtitle = stringResource(R.string.notification_profile_add_tile_detail),
                    onClick = onAddTile,
                    modifier = Modifier.testTag("notification-profiles.add-tile"),
                    leading = { SettingsLeadingIcon(R.drawable.ic_add) },
                )
            }
        }
    }
    if (quickAccess.tileHint) SettingsExplainer(stringResource(R.string.notification_profile_tile_hint))
}
