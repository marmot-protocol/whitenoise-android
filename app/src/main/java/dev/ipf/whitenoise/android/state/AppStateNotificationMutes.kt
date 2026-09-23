package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.notifications.LocalNotificationPolicy

private val memberMuteLock = Any()

private var memberMuteHolder: Pair<Context, MemberMutePreferences>? = null

/**
 * The process-wide per-member group mute store (#2782).
 *
 * One instance per application context so every surface — the profile sheet that toggles an entry
 * and the notification policy that reads it — observes the same state without [WhiteNoiseAppState]
 * owning another field. A different context (a fresh test application) builds its own store.
 */
internal val WhiteNoiseAppState.memberMutePreferences: MemberMutePreferences
    get() =
        synchronized(memberMuteLock) {
            memberMuteHolder?.takeIf { it.first === appContext }?.second
                ?: MemberMutePreferences(appContext).also { memberMuteHolder = appContext to it }
        }

/**
 * Whether this update may be posted, given the app's foreground state and every local mute.
 *
 * Lives beside the per-member store rather than in [WhiteNoiseAppState] itself so the whole local
 * notification-mute decision — conversation mode, engine mute and per-member group mute — reads as
 * one adapter onto [LocalNotificationPolicy].
 */
internal fun WhiteNoiseAppState.shouldPostNotification(
    update: NotificationUpdateFfi,
    engineMuted: Boolean,
): Boolean =
    LocalNotificationPolicy.shouldPost(
        update = update,
        appInForeground = appInForeground,
        activeConversationGroupIdHex = activeConversationGroupIdHex,
        activeConversationAccountRef = activeConversationAccountRef,
        appLockScreenVisible = appLockScreenVisible,
        conversationNotifyMode = chatMutePreferences::mode,
        engineMuted = engineMuted,
        senderMutedInGroup = memberMutePreferences::isMuted,
    )

/** Whether [memberIdHex] is silenced for [accountRef] inside [groupIdHex]. */
internal fun WhiteNoiseAppState.isMemberMutedInGroup(
    accountRef: String?,
    groupIdHex: String?,
    memberIdHex: String?,
): Boolean = memberMutePreferences.isMuted(accountRef, groupIdHex, memberIdHex)

/** Silences or restores [memberIdHex] for [accountRef] inside [groupIdHex] alone. */
internal fun WhiteNoiseAppState.setMemberMutedInGroup(
    accountRef: String?,
    groupIdHex: String?,
    memberIdHex: String?,
    muted: Boolean,
) {
    memberMutePreferences.setMuted(accountRef, groupIdHex, memberIdHex, muted)
}

/** Drops per-member mutes belonging to local accounts that no longer exist on this device. */
internal fun WhiteNoiseAppState.retainMemberMutesForAccounts(accountRefs: Collection<String>) {
    memberMutePreferences.retainAccounts(accountRefs)
}
