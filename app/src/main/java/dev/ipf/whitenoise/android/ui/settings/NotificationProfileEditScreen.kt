package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.notifications.NotificationProfile
import dev.ipf.whitenoise.android.notifications.NotificationProfileChat
import dev.ipf.whitenoise.android.notifications.NotificationProfilePerson
import dev.ipf.whitenoise.android.notifications.NotificationProfileSchedule
import dev.ipf.whitenoise.android.notifications.normalizedNotificationProfileEmoji
import dev.ipf.whitenoise.android.notifications.normalizedNotificationProfileName
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.allLoadedFolderChats
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import dev.ipf.whitenoise.android.state.notificationProfiles
import dev.ipf.whitenoise.android.ui.chats.newchat.deriveRecipientCandidates
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEntityPickerSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.common.rememberEmojiSheetFieldAnchor
import dev.ipf.whitenoise.android.ui.common.rememberEmojiSheetOpener
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.util.Locale
import java.util.UUID

/** Which schedule time the clock dialog is editing. */
private enum class ProfileTimeField { START, END }

private const val CHAT_KEY_SEPARATOR = '\u0000'

private val ProfileChatsSaver =
    Saver<Set<NotificationProfileChat>, ArrayList<String>>(
        save = { chats -> ArrayList(chats.map { "${it.accountRef}$CHAT_KEY_SEPARATOR${it.groupIdHex}" }) },
        restore = { saved ->
            saved
                .mapNotNull { key ->
                    key.split(CHAT_KEY_SEPARATOR).takeIf { it.size == 2 }?.let { NotificationProfileChat(it[0], it[1]) }
                }.toSet()
        },
    )

private val ProfilePeopleSaver =
    Saver<Set<NotificationProfilePerson>, ArrayList<String>>(
        save = { people -> ArrayList(people.map { "${it.accountRef}$CHAT_KEY_SEPARATOR${it.accountIdHex}" }) },
        restore = { saved ->
            saved
                .mapNotNull { key ->
                    val parts = key.split(CHAT_KEY_SEPARATOR)
                    if (parts.size == 2) NotificationProfilePerson(parts[0], parts[1]) else null
                }.toSet()
        },
    )

private val ProfileScheduleSaver =
    Saver<NotificationProfileSchedule, IntArray>(
        save = { s ->
            intArrayOf(if (s.enabled) 1 else 0, s.startMinuteOfDay, s.endMinuteOfDay) +
                s.days.map { it.value }.toIntArray()
        },
        restore = { saved ->
            NotificationProfileSchedule(
                enabled = saved[0] == 1,
                startMinuteOfDay = saved[1],
                endMinuteOfDay = saved[2],
                days = saved.drop(3).map(DayOfWeek::of).toSet(),
            )
        },
    )

/**
 * Creates or edits one profile in place. Edits stay in this draft until Save; Back with changes asks before
 * discarding. Allowed chats are chosen for the active account and other accounts' choices are preserved.
 */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun NotificationProfileEditScreen(
    appState: WhiteNoiseAppState,
    profileId: String?,
    onClose: () -> Unit,
) {
    val store = appState.notificationProfiles
    val storeState by store.state.collectAsState()
    val existing =
        remember(profileId) {
            profileId?.let { id ->
                store.state.value.profiles
                    .firstOrNull { it.id == id }
            }
        }
    val missing = profileId != null && storeState.profiles.none { it.id == profileId }
    val runtimeGeneration = remember { appState.runtimeGeneration }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }

    fun canMutate() =
        active &&
            appState.runtimeGeneration == runtimeGeneration &&
            !appState.signOutInProgress &&
            !appState.wipeInProgress

    val name = rememberTextFieldState(existing?.name.orEmpty())
    val initialChats = remember { existing?.allowedChats.orEmpty() }
    var allowedChats by rememberSaveable(stateSaver = ProfileChatsSaver) { mutableStateOf(initialChats) }
    val initialPeople = remember { existing?.allowedPeople.orEmpty() }
    var allowedPeople by rememberSaveable(stateSaver = ProfilePeopleSaver) { mutableStateOf(initialPeople) }
    val initialMentions = remember { existing?.allowMentions ?: true }
    var allowMentions by rememberSaveable { mutableStateOf(initialMentions) }
    val initialDms = remember { existing?.allowAllDirectMessages ?: false }
    var allowAllDms by rememberSaveable { mutableStateOf(initialDms) }
    val initialRepeated = remember { existing?.allowRepeatedMessages ?: false }
    var allowRepeated by rememberSaveable { mutableStateOf(initialRepeated) }
    val initialGroupChanges = remember { existing?.allowGroupChanges ?: false }
    var allowGroupChanges by rememberSaveable { mutableStateOf(initialGroupChanges) }
    val initialEmoji = remember { existing?.emoji }
    var emoji by rememberSaveable { mutableStateOf(initialEmoji) }
    val initialSchedule = remember { existing?.schedule ?: NotificationProfileSchedule.Default.copy(enabled = false) }
    var schedule by rememberSaveable(stateSaver = ProfileScheduleSaver) { mutableStateOf(initialSchedule) }
    var timeField by rememberSaveable { mutableStateOf<ProfileTimeField?>(null) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var peoplePickerOpen by rememberSaveable { mutableStateOf(false) }
    val profileEmojiAnchor = rememberEmojiSheetFieldAnchor()
    val openBelowName = rememberEmojiSheetOpener(profileEmojiAnchor)
    var emojiPickerOpen by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }

    val trimmedName = normalizedNotificationProfileName(name.text.toString())
    val scheduleValid = !schedule.enabled || schedule.isValid
    val draftValid = trimmedName.isNotEmpty() && scheduleValid && !missing
    val dirty =
        trimmedName != existing?.name.orEmpty() ||
            allowedChats != initialChats ||
            allowedPeople != initialPeople ||
            allowMentions != initialMentions ||
            allowAllDms != initialDms ||
            allowRepeated != initialRepeated ||
            allowGroupChanges != initialGroupChanges ||
            emoji != initialEmoji ||
            schedule != initialSchedule

    /** Back waits for an in-flight write: it either closes the editor or hands the draft back. */
    fun back() {
        when {
            submitted -> Unit
            dirty && !missing -> discard = true
            else -> onClose()
        }
    }

    /** Draft edits apply only while no write is in flight, so nothing typed during a save is lost. */
    fun edit(change: () -> Unit) {
        if (!submitted) change()
    }
    BackHandler(onBack = ::back)

    /** Persists the draft off the main thread; a failure keeps the form and shows an error. */
    fun save() {
        if (!canMutate() || submitted || !draftValid) return
        submitted = true
        val profile =
            NotificationProfile(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = trimmedName,
                allowedChats = allowedChats,
                allowMentions = allowMentions,
                allowedPeople = allowedPeople,
                allowAllDirectMessages = allowAllDms,
                allowRepeatedMessages = allowRepeated,
                allowGroupChanges = allowGroupChanges,
                emoji = emoji,
                // A switched-off schedule keeps its times for next time; one never turned on is not stored.
                schedule = schedule.takeIf { it.enabled || existing?.schedule != null },
                createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
            )
        appState.launchMutation {
            val saved = withContext(Dispatchers.IO) { runCatching { store.save(profile) }.getOrDefault(false) }
            if (saved && active) {
                onClose()
            } else {
                submitted = false
                failed = true
            }
        }
    }

    fun delete() {
        confirmDelete = false
        if (!canMutate() || submitted || profileId == null) return
        submitted = true
        appState.launchMutation {
            val deleted = withContext(Dispatchers.IO) { runCatching { store.delete(profileId) }.getOrDefault(false) }
            if (deleted && active) {
                onClose()
            } else {
                submitted = false
                failed = true
            }
        }
    }

    val accountRef = appState.activeAccountRef
    val signedInAccounts = appState.accounts.count { it.isSignedInSigningAccount() }
    val accountName =
        appState.activeAccount
            ?.takeIf { signedInAccounts > 1 }
            ?.let { appState.accountDisplayNameCached(it.accountIdHex) }
    val activeChatIds =
        allowedChats.filter { it.accountRef == accountRef }.mapTo(HashSet()) { it.groupIdHex }
    val activePeopleIds =
        allowedPeople.filter { it.accountRef == accountRef }.mapTo(HashSet()) { it.accountIdHex }

    NotificationProfileEditContent(
        state =
            NotificationProfileEditFormState(
                isNew = profileId == null,
                name = name,
                allowedChatCount = activeChatIds.size,
                allowMentions = allowMentions,
                accountName = accountName,
                schedule = schedule,
                canSave = draftValid && !submitted && canMutate(),
                canDelete = profileId != null && !missing,
                busy = submitted,
                emoji = emoji,
                allowedPeopleCount = activePeopleIds.size,
                allowAllDirectMessages = allowAllDms,
                allowRepeatedMessages = allowRepeated,
                allowGroupChanges = allowGroupChanges,
                error =
                    when {
                        failed || (missing && !submitted) -> stringResource(R.string.notification_profile_save_failed)
                        else -> null
                    },
            ),
        actions =
            NotificationProfileEditActions(
                onBack = ::back,
                onSave = ::save,
                onOpenChats = { edit { if (accountRef != null) pickerOpen = true } },
                onMentionsChange = { edit { allowMentions = it } },
                onScheduleChange = { enabled -> edit { schedule = schedule.copy(enabled = enabled) } },
                onEditStart = { edit { timeField = ProfileTimeField.START } },
                onEditEnd = { edit { timeField = ProfileTimeField.END } },
                onToggleDay = { day -> edit { schedule = schedule.copy(days = schedule.days.toggled(day)) } },
                onDelete = { edit { if (canMutate()) confirmDelete = true } },
                onChooseEmoji = { edit { openBelowName { edit { emojiPickerOpen = true } } } },
                onRemoveEmoji = { edit { emoji = null } },
                onOpenPeople = { edit { if (accountRef != null) peoplePickerOpen = true } },
                onDirectMessagesChange = { edit { allowAllDms = it } },
                onRepeatedChange = { edit { allowRepeated = it } },
                onGroupChangesChange = { edit { allowGroupChanges = it } },
            ),
        profileEmojiAnchor = profileEmojiAnchor,
    )

    timeField?.let { field ->
        NotificationProfileTimeDialog(
            title =
                stringResource(
                    if (field == ProfileTimeField.START) {
                        R.string.notification_profile_schedule_start
                    } else {
                        R.string.notification_profile_schedule_end
                    },
                ),
            minuteOfDay = if (field == ProfileTimeField.START) schedule.startMinuteOfDay else schedule.endMinuteOfDay,
            onDismiss = { timeField = null },
            onConfirm = { minute ->
                timeField = null
                schedule =
                    if (field == ProfileTimeField.START) {
                        schedule.copy(startMinuteOfDay = minute)
                    } else {
                        schedule.copy(endMinuteOfDay = minute)
                    }
            },
        )
    }
    if (pickerOpen && accountRef != null) {
        AllowedChatsPicker(
            appState = appState,
            selectedIds = activeChatIds,
            onToggle = { id ->
                val chat = NotificationProfileChat(accountRef, id)
                allowedChats = if (chat in allowedChats) allowedChats - chat else allowedChats + chat
            },
            onClose = { pickerOpen = false },
        )
    }
    if (peoplePickerOpen && accountRef != null) {
        AllowedPeoplePicker(
            appState = appState,
            selectedIds = activePeopleIds,
            onToggle = { id ->
                val person = NotificationProfilePerson(accountRef, id)
                allowedPeople = if (person in allowedPeople) allowedPeople - person else allowedPeople + person
            },
            onClose = { peoplePickerOpen = false },
        )
    }
    if (emojiPickerOpen) {
        EmojiPickerSheet(
            onDismissRequest = { emojiPickerOpen = false },
            fieldAnchor = profileEmojiAnchor,
            onEmojiPicked = { picked ->
                emojiPickerOpen = false
                edit { emoji = normalizedNotificationProfileEmoji(picked) }
            },
        )
    }
    if (confirmDelete) {
        NotificationProfileDeleteDialog(
            name = existing?.name.orEmpty(),
            onDismiss = { confirmDelete = false },
            onConfirm = ::delete,
        )
    }
    if (discard) {
        NotificationProfileDiscardDialog(
            onDismiss = { discard = false },
            onDiscard = {
                discard = false
                onClose()
            },
        )
    }
}

/** The active account's loaded chats, multi-select, with the same rows Chat folders offers. */
@Suppress("FunctionNaming")
@Composable
private fun AllowedChatsPicker(
    appState: WhiteNoiseAppState,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    val profileRevision = appState.profileRevisionForCompose
    val rows =
        remember(appState.chatListItems, appState.archivedChatListItems, profileRevision, groupTitleCopy) {
            allLoadedFolderChats(appState.chatListItems, appState.archivedChatListItems).map { item ->
                WhiteNoisePickerItem(
                    id = item.id.lowercase(Locale.ROOT),
                    title = chatListItemDisplayTitle(item, appState, groupTitleCopy),
                    avatarSeed = item.id,
                )
            }
        }
    WhiteNoiseEntityPickerSheet(
        title = stringResource(R.string.notification_profile_allowed_chats),
        description = stringResource(R.string.notification_profile_allowed_hint),
        items = rows,
        onDismiss = onClose,
        onSelect = onToggle,
        selectedIds = selectedIds,
        multiple = true,
        onDone = onClose,
        searchTag = "notification-profile.pickerSearch",
        rowTagPrefix = "notification-profile.choice",
    )
}

/** People the active account knows from its chats, multi-select; they notify in any chat while the profile is on. */
@Suppress("FunctionNaming")
@Composable
private fun AllowedPeoplePicker(
    appState: WhiteNoiseAppState,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
) {
    val activeHex = appState.activeAccount?.accountIdHex
    val rows =
        remember(appState.chatListItems, activeHex, appState.profileRevisionForCompose, selectedIds) {
            val known =
                deriveRecipientCandidates(appState, activeHex).map { candidate ->
                    WhiteNoisePickerItem(
                        id = candidate.accountIdHex.lowercase(Locale.ROOT),
                        title = candidate.displayName,
                        avatarSeed = candidate.accountIdHex,
                    )
                }
            // Someone allowed earlier stays listed, and removable, after leaving every loaded chat.
            val knownIds = known.mapTo(HashSet()) { it.id }
            known +
                selectedIds.filterNot(knownIds::contains).map { id ->
                    WhiteNoisePickerItem(id = id, title = appState.displayName(id), avatarSeed = id)
                }
        }
    WhiteNoiseEntityPickerSheet(
        title = stringResource(R.string.notification_profile_allowed_people),
        description = stringResource(R.string.notification_profile_people_hint),
        items = rows,
        onDismiss = onClose,
        onSelect = onToggle,
        selectedIds = selectedIds,
        multiple = true,
        onDone = onClose,
        searchTag = "notification-profile.peopleSearch",
        rowTagPrefix = "notification-profile.person",
    )
}

@Suppress("FunctionNaming")
@Composable
private fun NotificationProfileDeleteDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        modifier = Modifier.testTag("notification-profile.delete-dialog"),
        title = stringResource(R.string.notification_profile_delete_confirm_title, name),
        message = stringResource(R.string.notification_profile_delete_confirm_body),
        confirmLabel = stringResource(R.string.delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        confirmTag = "notification-profile.delete-confirm",
    )
}

@Suppress("FunctionNaming")
@Composable
private fun NotificationProfileDiscardDialog(
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_discard_title)) },
        text = { Text(stringResource(R.string.notification_profile_discard_detail)) },
        confirmButton = {
            TextButton(
                onClick = { close(onDiscard) },
            ) { Text(stringResource(R.string.folder_discard)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.folder_keep_editing)) } },
    )
}

private fun Set<DayOfWeek>.toggled(day: DayOfWeek): Set<DayOfWeek> = if (day in this) this - day else this + day
