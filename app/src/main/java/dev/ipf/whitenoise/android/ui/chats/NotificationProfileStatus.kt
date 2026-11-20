package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ActiveNotificationProfile
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.notificationProfiles
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.settings.notificationProfileActiveDetail
import dev.ipf.whitenoise.android.ui.settings.notificationProfileActiveLabel
import dev.ipf.whitenoise.android.ui.settings.rememberActiveNotificationProfile
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import java.time.ZoneId

internal const val NOTIFICATION_PROFILE_BANNER_TAG = "chats.notificationProfileBanner"
internal const val NOTIFICATION_PROFILE_QUICK_ACTION_TAG = "chats.notificationProfileAction"

/**
 * Chat-list status for notification profiles: a top-bar button once a profile exists, which opens the quick
 * sheet. The icon is tinted while a profile is on.
 */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfileQuickAction(appState: WhiteNoiseAppState) {
    val store = appState.notificationProfiles
    val state by store.state.collectAsState()
    if (state.profiles.isEmpty()) return
    val active = rememberActiveNotificationProfile(store)
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val label =
        active?.let { stringResource(R.string.notification_profile_quick_open_active, it.profile.name) }
            ?: stringResource(R.string.notification_profiles_title)
    IconButton(
        onClick = { sheetOpen = true },
        modifier =
            Modifier
                .testTag(NOTIFICATION_PROFILE_QUICK_ACTION_TAG)
                .explainOnLongPress(
                    Explanation(
                        stringResource(R.string.notification_profiles_title),
                        stringResource(R.string.explanation_chats_notification_profiles),
                    ),
                ),
    ) {
        Icon(
            painterResource(R.drawable.ic_notifications_paused),
            contentDescription = label,
            tint =
                if (active !=
                    null
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
    if (sheetOpen) {
        NotificationProfileQuickSheet(appState = appState, onDismiss = { sheetOpen = false })
    }
}

/** Persistent chat-list notice while a profile is on; tapping it opens the quick sheet. */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfileChatListNotice(appState: WhiteNoiseAppState) {
    val store = appState.notificationProfiles
    val state by store.state.collectAsState()
    if (state.profiles.isEmpty()) return
    val active = rememberActiveNotificationProfile(store) ?: return
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    NotificationProfileBanner(active = active, onClick = { sheetOpen = true })
    if (sheetOpen) {
        NotificationProfileQuickSheet(appState = appState, onDismiss = { sheetOpen = false })
    }
}

/** One calm line naming the active profile and when it ends, on the same surface as other chat-list notices. */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfileBanner(
    active: ActiveNotificationProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val title = notificationProfileActiveLabel(active, zone)
    val detail = notificationProfileActiveDetail(active.profile)
    Surface(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(NOTIFICATION_PROFILE_BANNER_TAG)
                .semantics { role = Role.Button },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = amoledOutlineBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_notifications_paused),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
