package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ActiveNotificationProfile
import dev.ipf.whitenoise.android.notifications.MAX_NOTIFICATION_PROFILES
import dev.ipf.whitenoise.android.notifications.NotificationProfile
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.notificationProfiles
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.chats.NotificationProfileQuickSheet
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

internal const val NOTIFICATION_PROFILES_LIST_TAG = "notification-profiles.list"

/**
 * Notification profiles: every profile with its status, a way to turn the active one off, and `+` to add one.
 * Creating or editing swaps in [NotificationProfileEditScreen] in place, as Chat folders does.
 */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfilesScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    if (appState.signOutInProgress || appState.wipeInProgress) return
    key(appState.runtimeGeneration) { NotificationProfilesSession(appState, onBack) }
}

/** Owns the editor, profile chooser and turn-off action for one runtime generation. */
@Suppress("FunctionNaming")
@Composable
private fun NotificationProfilesSession(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val store = appState.notificationProfiles
    val state by store.state.collectAsState()
    val active = rememberActiveNotificationProfile(store)
    var chooserOpen by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    if (editorOpen) {
        NotificationProfileEditScreen(appState = appState, profileId = editingId, onClose = { editorOpen = false })
        return
    }
    BackHandler(onBack = onBack)

    NotificationProfilesContent(
        profiles = state.profiles,
        active = active,
        onBack = onBack,
        onCreate = {
            editingId = null
            editorOpen = true
        },
        onEdit = { id ->
            editingId = id
            editorOpen = true
        },
        onTurnOff = {
            appState.launchMutation {
                val saved = withContext(Dispatchers.IO) { runCatching { store.turnOff() }.getOrDefault(false) }
                if (!saved) appState.present(R.string.notification_profile_change_failed)
            }
        },
        quickAccess = rememberNotificationProfileQuickAccess(appState, state),
        onSwitchProfile = { chooserOpen = true },
    )
    if (chooserOpen) {
        NotificationProfileQuickSheet(appState = appState, onDismiss = { chooserOpen = false })
    }
}

/** The list without state ownership, so tests can render each arrangement with a fixed clock. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun NotificationProfilesContent(
    profiles: List<NotificationProfile>,
    active: ActiveNotificationProfile?,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onTurnOff: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    quickAccess: NotificationProfileQuickAccess? = null,
    onSwitchProfile: (() -> Unit)? = null,
) {
    val createLabel = stringResource(R.string.notification_profile_create)
    SettingsScaffold(
        title = stringResource(R.string.notification_profiles_title),
        onBack = onBack,
        topBarActions = {
            if (profiles.isNotEmpty() && onSwitchProfile != null) {
                IconButton(onClick = onSwitchProfile, modifier = Modifier.testTag("notification-profiles.switch")) {
                    Icon(
                        painterResource(R.drawable.ic_notifications_paused),
                        contentDescription = stringResource(R.string.notification_profiles_title),
                    )
                }
            }
            if (profiles.isNotEmpty() && profiles.size < MAX_NOTIFICATION_PROFILES) {
                IconButton(
                    onClick = onCreate,
                    modifier =
                        Modifier
                            .testTag("notification-profiles.create")
                            .explainOnLongPress(
                                Explanation(createLabel, stringResource(R.string.explanation_privacy_notif_profiles)),
                            ),
                ) { Icon(painterResource(R.drawable.ic_add), createLabel) }
            }
        },
    ) {
        SettingsList(listTag = NOTIFICATION_PROFILES_LIST_TAG) {
            if (profiles.isEmpty()) {
                item(key = "empty") { NotificationProfilesEmpty(createLabel, onCreate) }
            } else {
                if (active != null) {
                    item(key = "active") { ActiveProfileGroup(active, zone, onTurnOff) }
                }
                item(key = "profiles") { ProfileListGroup(profiles, active, zone, onEdit) }
                item(key = "explainer") { SettingsExplainer(stringResource(R.string.notification_profiles_explainer)) }
                quickAccess?.let { item(key = "quick_access") { NotificationProfileQuickAccessSection(it) } }
            }
        }
    }
}

/** First-run state: what a profile is for and the one action that makes one. */
@Suppress("FunctionNaming")
@Composable
private fun NotificationProfilesEmpty(
    createLabel: String,
    onCreate: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WhiteNoiseEmptyState(
            title = stringResource(R.string.notification_profiles_empty_title),
            detail = stringResource(R.string.notification_profiles_empty_body),
        )
        WhiteNoiseButton(onClick = onCreate, modifier = Modifier.testTag("notification-profiles.create-first")) {
            Text(createLabel)
        }
        Text(
            stringResource(R.string.notification_profiles_explainer),
            modifier =
                Modifier.padding(
                    top = WhiteNoiseSpacing.Section,
                    start = WhiteNoiseSpacing.Related,
                    end = WhiteNoiseSpacing.Related,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The profile that is on now, with the action that turns it off. */
@Suppress("FunctionNaming")
@Composable
private fun ActiveProfileGroup(
    active: ActiveNotificationProfile,
    zone: ZoneId,
    onTurnOff: () -> Unit,
) {
    SettingsGroup {
        row("active") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.notification_profile_turn_off_named, active.profile.label),
                subtitle = notificationProfileActiveLabel(active, zone),
                onClick = onTurnOff,
                modifier = Modifier.testTag("notification-profiles.turn-off"),
                leading = {
                    SettingsLeadingIcon(R.drawable.ic_notifications_paused, tint = MaterialTheme.colorScheme.primary)
                },
            )
        }
    }
}

/** Every profile with its live status or schedule; tapping one opens the editor. */
@Suppress("FunctionNaming")
@Composable
private fun ProfileListGroup(
    profiles: List<NotificationProfile>,
    active: ActiveNotificationProfile?,
    zone: ZoneId,
    onEdit: (String) -> Unit,
) {
    SettingsGroup {
        profiles.forEach { profile ->
            row(profile.id) { context ->
                val isActive = active?.profile?.id == profile.id
                val scheduled = profile.schedule?.let { it.enabled && it.isValid } == true
                SettingsLink(
                    context = context,
                    title = profile.name,
                    value = notificationProfileStatus(profile, active, zone),
                    onClick = { onEdit(profile.id) },
                    modifier = Modifier.testTag("notification-profiles.row.${profile.id}"),
                    leading = {
                        val scheme = MaterialTheme.colorScheme
                        val fallback =
                            if (scheduled && !isActive) R.drawable.ic_schedule else R.drawable.ic_notifications_paused
                        NotificationProfileLeading(
                            emoji = profile.emoji,
                            fallback = fallback,
                            tint = if (isActive) scheme.primary else scheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

/** The profile's emoji in the row's icon slot, or [fallback] when it has none. */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfileLeading(
    emoji: String?,
    @DrawableRes fallback: Int,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (emoji == null) {
        SettingsLeadingIcon(fallback, tint = tint)
    } else {
        Box(Modifier.size(SettingsRowDefaults.LeadingIconSize), contentAlignment = Alignment.Center) {
            EmojiLabel(
                emoji,
                style = LocalTextStyle.current.merge(TextStyle(fontSize = ProfileEmojiSize)),
                maxLines = 1,
            )
        }
    }
}

private val ProfileEmojiSize = 20.sp
