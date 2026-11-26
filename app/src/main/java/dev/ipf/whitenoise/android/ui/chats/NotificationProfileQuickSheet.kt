package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ActiveNotificationProfile
import dev.ipf.whitenoise.android.notifications.NotificationProfile
import dev.ipf.whitenoise.android.notifications.NotificationProfileDuration
import dev.ipf.whitenoise.android.notifications.notificationProfileManualUntilMs
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.notificationProfiles
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseChoiceRow
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.rememberSheetTerminalMotion
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.settings.notificationProfileActiveDetail
import dev.ipf.whitenoise.android.ui.settings.notificationProfileActiveLabel
import dev.ipf.whitenoise.android.ui.settings.notificationProfileClock
import dev.ipf.whitenoise.android.ui.settings.notificationProfileStatus
import dev.ipf.whitenoise.android.ui.settings.rememberActiveNotificationProfile
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

internal const val NOTIFICATION_PROFILE_QUICK_SHEET_TAG = "chats.notificationProfileSheet"

/** The clock and time zone read when the user taps a duration, not when the sheet was drawn. */
internal class NotificationProfileTapClock(
    val nowMs: () -> Long = System::currentTimeMillis,
    val zone: () -> ZoneId = ZoneId::systemDefault,
)

/** What the quick sheet asks the store to do; each choice applies at once and closes the sheet. */
internal class NotificationProfileQuickActions(
    val onSelect: (String) -> Unit,
    val onTurnOn: (profileId: String, untilMs: Long?) -> Unit,
    val onTurnOff: () -> Unit,
)

/** Turn a profile on for a while, until its scheduled end or until turned off; or turn the active one off. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfileQuickSheet(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
) {
    val store = appState.notificationProfiles
    val state by store.state.collectAsState()
    val active = rememberActiveNotificationProfile(store)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val terminal = rememberSheetTerminalMotion(sheet)
    val close = { terminal.close(onDismiss) }

    /** Writes off the main thread and reports a failed write; the sheet closes either way. */
    fun apply(change: () -> Boolean) {
        appState.launchMutation {
            val saved = withContext(Dispatchers.IO) { runCatching(change).getOrDefault(false) }
            if (!saved) appState.present(R.string.notification_profile_change_failed)
        }
        close()
    }
    WhiteNoiseModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheet,
        interactionEnabled = terminal.acceptsInput,
    ) {
        NotificationProfileQuickSheetContent(
            profiles = state.profiles,
            active = active,
            selectedId =
                selected?.takeIf { id -> state.profiles.any { it.id == id } } ?: state.profiles.firstOrNull()?.id,
            actions =
                NotificationProfileQuickActions(
                    onSelect = { selected = it },
                    onTurnOn = { id, until -> apply { store.turnOn(id, until) } },
                    onTurnOff = { apply { store.turnOff() } },
                ),
        )
    }
}

/** The sheet body without state ownership, so tests can render each state with a fixed clock. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun ColumnScope.NotificationProfileQuickSheetContent(
    profiles: List<NotificationProfile>,
    active: ActiveNotificationProfile?,
    selectedId: String?,
    actions: NotificationProfileQuickActions,
    zone: ZoneId = ZoneId.systemDefault(),
    tapClock: NotificationProfileTapClock = NotificationProfileTapClock(),
) {
    WhiteNoiseSheetHeader(stringResource(R.string.notification_profiles_title))
    Column(
        Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .fadingVerticalScroll(rememberScrollState())
            .padding(bottom = WhiteNoiseSpacing.Section)
            .testTag(NOTIFICATION_PROFILE_QUICK_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        if (active != null) {
            ActiveProfileSummary(active, zone, actions.onTurnOff)
        } else {
            ProfileChoices(profiles, selectedId, zone, actions.onSelect)
            profiles.firstOrNull { it.id == selectedId }?.let { profile ->
                SettingsSection(stringResource(R.string.notification_profile_turn_on))
                DurationChoices(profile, tapClock, actions.onTurnOn)
            }
        }
    }
}

/** The active profile, when it ends, and one clear way to turn it off. */
@Suppress("FunctionNaming")
@Composable
private fun ActiveProfileSummary(
    active: ActiveNotificationProfile,
    zone: ZoneId,
    onTurnOff: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = WhiteNoiseSpacing.Section,
            ).padding(top = WhiteNoiseSpacing.Related),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Text(notificationProfileActiveLabel(active, zone), style = MaterialTheme.typography.titleMedium)
        Text(
            notificationProfileActiveDetail(active.profile),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WhiteNoiseFilledTonalButton(
            onClick = onTurnOff,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        top = WhiteNoiseSpacing.Related,
                    ).testTag("notificationProfile.turnOff"),
        ) { Text(stringResource(R.string.notification_profile_turn_off_named, active.profile.label)) }
    }
}

/** One radio row per profile with its schedule, so the user sees which profile they are turning on. */
@Suppress("FunctionNaming")
@Composable
private fun ProfileChoices(
    profiles: List<NotificationProfile>,
    selectedId: String?,
    zone: ZoneId,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = WhiteNoiseSpacing.Related)
            .selectableGroup(),
    ) {
        profiles.forEach { profile ->
            WhiteNoiseChoiceRow(
                title = profile.label,
                subtitle = notificationProfileStatus(profile, active = null, zone = zone),
                selected = profile.id == selectedId,
                onClick = { onSelect(profile.id) },
                modifier = Modifier.testTag("notificationProfile.choice.${profile.id}"),
            )
        }
    }
}

/** For one hour, until the profile's scheduled end, or until turned off; each applies at once. */
@Suppress("FunctionNaming")
@Composable
private fun DurationChoices(
    profile: NotificationProfile,
    tapClock: NotificationProfileTapClock,
    onTurnOn: (profileId: String, untilMs: Long?) -> Unit,
) {
    val scheduleEnd = profile.schedule?.takeIf { it.enabled && it.isValid }?.endMinuteOfDay
    val scheduleEndLabel = scheduleEnd?.let { notificationProfileClock(it) }

    /** Turns [profile] on, timing [duration] from the moment of the tap. */
    fun turnOn(duration: NotificationProfileDuration) {
        onTurnOn(profile.id, notificationProfileManualUntilMs(profile, duration, tapClock.nowMs(), tapClock.zone()))
    }
    SettingsGroup {
        row("hour") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.notification_profile_for_one_hour),
                onClick = { turnOn(NotificationProfileDuration.ONE_HOUR) },
                modifier = Modifier.testTag("notificationProfile.forHour"),
                leading = { SettingsLeadingIcon(R.drawable.ic_timer) },
            )
        }
        if (scheduleEnd != null && scheduleEndLabel != null) {
            row("schedule_end") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.notification_profile_until_time, scheduleEndLabel),
                    onClick = { turnOn(NotificationProfileDuration.UNTIL_SCHEDULE_END) },
                    modifier = Modifier.testTag("notificationProfile.untilEnd"),
                    leading = { SettingsLeadingIcon(R.drawable.ic_schedule) },
                )
            }
        }
        row("until_off") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.notification_profile_until_off),
                onClick = { turnOn(NotificationProfileDuration.UNTIL_TURNED_OFF) },
                modifier = Modifier.testTag("notificationProfile.untilOff"),
                leading = { SettingsLeadingIcon(R.drawable.ic_notifications_paused) },
            )
        }
    }
}
