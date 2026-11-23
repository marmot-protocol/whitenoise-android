@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.authoritativeConversationMuteSettings
import dev.ipf.whitenoise.android.state.conversationRestoreNotifyMode
import dev.ipf.whitenoise.android.state.groupDetailsMuteDisplay
import dev.ipf.whitenoise.android.state.isConversationMutePending
import dev.ipf.whitenoise.android.state.muteConversationFor
import dev.ipf.whitenoise.android.state.muteConversationUntil
import dev.ipf.whitenoise.android.state.setConversationNotifyMode

/**
 * One mute/unmute owner for a conversation, shared by chat details and the optional header shortcut so both
 * read the same MDK state and run the same commands. Muting asks for a duration; unmuting restores the
 * All/Only-mentions preference the mute hid.
 */
internal class ConversationMuteControl(
    private val appState: WhiteNoiseAppState,
    private val groupIdHex: String,
    val muted: Boolean,
    val mutedUntilMs: Long?,
    val pending: Boolean,
    val restoreMode: ChatNotifyMode,
    private val dialogOpen: MutableState<Boolean>,
    /** False once the account, runtime or chat that built this control is no longer current. */
    private val isCurrent: () -> Boolean,
) {
    val durationDialogOpen: Boolean get() = dialogOpen.value

    @get:StringRes
    val labelRes: Int get() = if (muted) R.string.chat_row_action_unmute else R.string.mute

    @get:DrawableRes
    val iconRes: Int get() = if (muted) R.drawable.ic_settings_notifications else R.drawable.ic_notifications_off

    @get:StringRes
    val explanationRes: Int
        get() = if (muted) R.string.explanation_chat_info_unmute else R.string.explanation_chat_info_mute

    /** The details button and header shortcut both call this. */
    fun toggle() {
        if (pending) return
        if (muted) unmute() else requestMute()
    }

    /** Opens the duration choice; the mute itself runs from [MuteDurationHost]. */
    fun requestMute() {
        dialogOpen.value = true
    }

    /** Restores the notify mode saved before the mute; no confirmation. */
    fun unmute() {
        if (!isCurrent()) return
        appState.setConversationNotifyMode(groupIdHex, restoreMode)
    }

    /** Closes the duration choice and mutes until [target]. */
    fun mute(target: MuteTarget) {
        dialogOpen.value = false
        if (!isCurrent()) return
        when (target) {
            is MuteTarget.After -> appState.muteConversationFor(groupIdHex, target.durationMillis)
            is MuteTarget.At -> appState.muteConversationUntil(groupIdHex, target.expiryMillis)
            MuteTarget.Always -> appState.muteConversationFor(groupIdHex, durationMillis = 0L)
        }
    }

    /** Closes the duration choice without muting. */
    fun dismissDurationDialog() {
        dialogOpen.value = false
    }
}

/** Reads the conversation's mute state the way chat details always has: list row first, targeted read otherwise. */
@Composable
internal fun rememberConversationMuteControl(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): ConversationMuteControl {
    val groupIdHex = controller.group.groupIdHex
    val chatNotificationState by appState.chatMutePreferences.state.collectAsState()
    val notificationModes = chatNotificationState.notificationModes
    var targetedMuteSettings by remember(appState.activeAccountRef, groupIdHex) {
        mutableStateOf<ChatNotificationSettingsFfi?>(null)
    }
    LaunchedEffect(appState.activeAccountRef, groupIdHex, controller.latestChatListRow) {
        targetedMuteSettings = null
        if (controller.latestChatListRow == null) {
            targetedMuteSettings = appState.authoritativeConversationMuteSettings(groupIdHex)
        }
    }
    val display = appState.groupDetailsMuteDisplay(groupIdHex, controller.latestChatListRow, targetedMuteSettings)
    val pending = appState.isConversationMutePending(groupIdHex)
    // Android retains only the All/Only-mentions preference behind MDK's mute.
    val restoreMode =
        remember(appState.activeAccountRef, groupIdHex, notificationModes) {
            appState.conversationRestoreNotifyMode(groupIdHex)
        }
    val dialogOpen = remember(controller) { mutableStateOf(false) }
    // A callback captured before an account switch or runtime restart must not write to the new session.
    val runtime = appState.runtimeGeneration
    return ConversationMuteControl(
        appState,
        groupIdHex,
        display.muted,
        display.mutedUntilMs,
        pending,
        restoreMode,
        dialogOpen,
        isCurrent = { controller.isCurrentGroupMutationOwner(appState, runtime) },
    )
}

/** Shows the mute duration choice while [control] has one open. */
@Composable
internal fun MuteDurationHost(control: ConversationMuteControl) {
    if (control.durationDialogOpen) {
        MuteDurationDialog(onDismiss = control::dismissDurationDialog, onSelect = control::mute)
    }
}
