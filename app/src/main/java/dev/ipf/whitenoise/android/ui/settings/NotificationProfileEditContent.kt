package dev.ipf.whitenoise.android.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.MAX_NOTIFICATION_PROFILE_NAME_CODE_POINTS
import dev.ipf.whitenoise.android.notifications.NotificationProfileSchedule
import dev.ipf.whitenoise.android.ui.common.EmojiSheetFieldAnchor
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.currentDateTimeDisplay
import dev.ipf.whitenoise.android.ui.common.emojiSheetFieldAnchor
import dev.ipf.whitenoise.android.ui.common.shapedPressFeedback
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.time.DayOfWeek
import java.time.format.TextStyle

internal const val NOTIFICATION_PROFILE_EDIT_LIST_TAG = "notification-profile.edit"
private const val MINUTES_PER_HOUR = 60
private const val DISABLED_ALPHA = 0.38f
private val DayToggleSize = 40.dp
private val DayToggleTarget = 48.dp

/** Everything the editor renders; the name is shared text state so typing needs no round trip. */
internal data class NotificationProfileEditFormState(
    val isNew: Boolean,
    val name: TextFieldState,
    val allowedChatCount: Int,
    val allowMentions: Boolean,
    val accountName: String?,
    val schedule: NotificationProfileSchedule,
    val canSave: Boolean,
    val canDelete: Boolean,
    val error: String?,
    /** A save or delete is being written; the draft is read-only until it finishes. */
    val busy: Boolean = false,
    val emoji: String? = null,
    val allowedPeopleCount: Int = 0,
    val allowAllDirectMessages: Boolean = false,
    val allowRepeatedMessages: Boolean = false,
    val allowGroupChanges: Boolean = false,
)

/** The editor's callbacks, grouped so the form stays a stateless rendering of [NotificationProfileEditFormState]. */
internal class NotificationProfileEditActions(
    val onBack: () -> Unit,
    val onSave: () -> Unit,
    val onOpenChats: () -> Unit,
    val onMentionsChange: (Boolean) -> Unit,
    val onScheduleChange: (Boolean) -> Unit,
    val onEditStart: () -> Unit,
    val onEditEnd: () -> Unit,
    val onToggleDay: (DayOfWeek) -> Unit,
    val onDelete: () -> Unit,
    val onChooseEmoji: () -> Unit = {},
    val onRemoveEmoji: () -> Unit = {},
    val onOpenPeople: () -> Unit = {},
    val onDirectMessagesChange: (Boolean) -> Unit = {},
    val onRepeatedChange: (Boolean) -> Unit = {},
    val onGroupChangesChange: (Boolean) -> Unit = {},
)

/** Rejects edits that would make the name longer than the stored limit, counted in code points. */
private val ProfileNameLimit =
    InputTransformation {
        val text = asCharSequence()
        if (Character.codePointCount(text, 0, text.length) >
            MAX_NOTIFICATION_PROFILE_NAME_CODE_POINTS
        ) {
            revertAllChanges()
        }
    }

/** Name, allowed notifications, schedule and delete for one profile. */
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfileEditContent(
    state: NotificationProfileEditFormState,
    actions: NotificationProfileEditActions,
    profileEmojiAnchor: EmojiSheetFieldAnchor? = null,
) {
    SettingsScaffold(
        title =
            stringResource(
                if (state.isNew) R.string.notification_profile_new_title else R.string.notification_profile_edit_title,
            ),
        onBack = actions.onBack,
        modifier = Modifier.imePadding(),
        topBarActions = {
            TextButton(
                enabled = state.canSave,
                onClick = actions.onSave,
                modifier = Modifier.testTag("notification-profile.save"),
            ) { Text(stringResource(R.string.save)) }
        },
    ) {
        SettingsList(listTag = NOTIFICATION_PROFILE_EDIT_LIST_TAG) {
            item(key = "name") { ProfileNameField(state, actions, profileEmojiAnchor) }
            item(key = "allowed") { AllowedNotificationsSection(state, actions) }
            item(key = "schedule") { ScheduleSection(state.schedule, actions, enabled = !state.busy) }
            if (state.canDelete) {
                item(key = "delete") { DeleteProfileGroup(actions.onDelete, enabled = !state.busy) }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ProfileNameField(
    state: NotificationProfileEditFormState,
    actions: NotificationProfileEditActions,
    profileEmojiAnchor: EmojiSheetFieldAnchor?,
) {
    Column(
        Modifier.fillMaxWidth().padding(
            horizontal = WhiteNoiseSpacing.CompactScreenMargin,
            vertical = WhiteNoiseSpacing.Related,
        ),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            NotificationProfileEmojiButton(
                emoji = state.emoji,
                enabled = !state.busy,
                onChoose = actions.onChooseEmoji,
                onRemove = actions.onRemoveEmoji,
            )
            WhiteNoiseTextField(
                state = state.name,
                enabled = !state.busy,
                emojiAction = true,
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag("notification-profile.name")
                        .then(profileEmojiAnchor?.let { Modifier.emojiSheetFieldAnchor(it) } ?: Modifier),
                label = { Text(stringResource(R.string.notification_profile_name_label)) },
                placeholder = { Text(stringResource(R.string.notification_profile_name_placeholder)) },
                inputTransformation = ProfileNameLimit,
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        }
        state.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun ScheduleSection(
    schedule: NotificationProfileSchedule,
    actions: NotificationProfileEditActions,
    enabled: Boolean,
) {
    SettingsSection(stringResource(R.string.notification_profile_schedule_section))
    SettingsGroup {
        row("schedule") { context ->
            SettingsSwitch(
                context = context,
                enabled = enabled,
                title = stringResource(R.string.notification_profile_schedule_enable),
                subtitle =
                    if (schedule.enabled &&
                        schedule.isValid
                    ) {
                        notificationProfileScheduleSummary(schedule)
                    } else {
                        null
                    },
                checked = schedule.enabled,
                onCheckedChange = actions.onScheduleChange,
                modifier = Modifier.testTag("notification-profile.schedule"),
                leading = { SettingsLeadingIcon(R.drawable.ic_schedule) },
            )
        }
        if (schedule.enabled) {
            row("start") { context ->
                SettingsLink(
                    context = context,
                    enabled = enabled,
                    title = stringResource(R.string.notification_profile_schedule_start),
                    value = notificationProfileClock(schedule.startMinuteOfDay),
                    onClick = actions.onEditStart,
                    modifier = Modifier.testTag("notification-profile.start"),
                    leading = { SettingsLeadingIcon(R.drawable.ic_notifications_paused) },
                )
            }
            row("end") { context ->
                val end = notificationProfileClock(schedule.endMinuteOfDay)
                SettingsLink(
                    context = context,
                    enabled = enabled,
                    title = stringResource(R.string.notification_profile_schedule_end),
                    value =
                        if (schedule.endsNextDay && schedule.startMinuteOfDay != schedule.endMinuteOfDay) {
                            stringResource(R.string.notification_profile_schedule_end_next_day, end)
                        } else {
                            end
                        },
                    onClick = actions.onEditEnd,
                    modifier = Modifier.testTag("notification-profile.end"),
                    leading = { SettingsLeadingIcon(R.drawable.ic_notifications_active) },
                )
            }
            row("days") { context ->
                SettingsGroupPanel(context) { DayPicker(schedule.days, actions.onToggleDay, enabled) }
            }
        }
    }
    if (schedule.enabled) {
        val sameTimes = schedule.startMinuteOfDay == schedule.endMinuteOfDay
        val error =
            when {
                schedule.days.isEmpty() -> R.string.notification_profile_schedule_days_error
                sameTimes -> R.string.notification_profile_schedule_same_time_error
                else -> null
            }
        error?.let { ScheduleError(stringResource(it)) }
    }
}

/** Inline schedule validation at the helper-text line, announced when it appears. */
@Suppress("FunctionNaming")
@Composable
private fun ScheduleError(text: String) {
    Text(
        text,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.SettingsSectionInset)
                .testTag("notification-profile.schedule-error")
                .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Seven day toggles in the locale's week order, each a 48 dp target with the full day name for TalkBack. */
@Suppress("FunctionNaming")
@Composable
private fun DayPicker(
    days: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
    enabled: Boolean,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.Related, vertical = WhiteNoiseSpacing.Related),
    ) {
        Text(
            stringResource(R.string.notification_profile_schedule_days),
            modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.Related, vertical = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(Modifier.fillMaxWidth().testTag("notification-profile.days")) {
            notificationProfileWeek(locale).forEach { day ->
                DayToggle(
                    label = day.getDisplayName(TextStyle.NARROW, locale),
                    description = day.getDisplayName(TextStyle.FULL, locale),
                    selected = day in days,
                    onToggle = { onToggle(day) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("notification-profile.day.${day.name}"),
                )
            }
        }
    }
}

/** One weekday cell: the whole cell toggles, while its circle shows the selection and the press highlight. */
@Suppress("FunctionNaming")
@Composable
private fun DayToggle(
    label: String,
    description: String,
    selected: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = DayToggleTarget)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            // The whole cell toggles, and the press highlight is drawn on the day's circle below.
            .toggleable(
                value = selected,
                interactionSource = press,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = if (selected) scheme.primary else scheme.surfaceContainerLowest,
            contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
            border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
            modifier = Modifier.sizeIn(minWidth = DayToggleSize, minHeight = DayToggleSize).clearAndSetSemantics {},
        ) {
            Box(Modifier.shapedPressFeedback(press, CircleShape), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun DeleteProfileGroup(
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Section)) {
        row("delete") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.notification_profile_delete),
                onClick = onDelete,
                enabled = enabled,
                destructive = true,
                modifier = Modifier.testTag("notification-profile.delete"),
                leading = { SettingsLeadingIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error) },
            )
        }
    }
}

/** Picks one schedule time with the Material clock in the user's 12- or 24-hour choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun NotificationProfileTimeDialog(
    title: String,
    minuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val context = LocalContext.current
    val is24Hour = currentDateTimeDisplay().use24Hour ?: DateFormat.is24HourFormat(context)
    val timeState = rememberTimePickerState(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR, is24Hour)
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("notification-profile.time-dialog"),
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("notification-profile.time-confirm"),
                onClick = { close { onConfirm(timeState.hour * MINUTES_PER_HOUR + timeState.minute) } },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
