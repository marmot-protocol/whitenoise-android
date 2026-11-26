package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.notifications.ConversationAlertPreferences
import dev.ipf.whitenoise.android.notifications.LocalNotificationPolicy
import dev.ipf.whitenoise.android.notifications.NotificationProfilePreferences
import dev.ipf.whitenoise.android.notifications.NotificationProfileRepeats
import dev.ipf.whitenoise.android.notifications.ProfileNotificationMode
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverridePreferences
import dev.ipf.whitenoise.android.notifications.isNotificationProfileMessage
import dev.ipf.whitenoise.android.notifications.notificationProfileSuppresses
import dev.ipf.whitenoise.android.notifications.retainConversationChannelOwners

private val memberMuteLock = Any()
private var profileNotificationHolder: Pair<Context, ProfileNotificationOverridePreferences>? = null

/** One account-scoped Android alert store observed by the profile UI and notification policy. */
internal val WhiteNoiseAppState.profileNotificationOverrides: ProfileNotificationOverridePreferences
    get() =
        synchronized(memberMuteLock) {
            profileNotificationHolder?.takeIf { it.first === appContext }?.second
                ?: ProfileNotificationOverridePreferences(appContext).also {
                    profileNotificationHolder = appContext to it
                }
        }

private var memberMuteHolder: Pair<Context, MemberMutePreferences>? = null

private var alertPreferenceHolder: Pair<Context, ConversationAlertPreferences>? = null

/** One process-wide adapter for this device's chat/category alert choices. */
internal val WhiteNoiseAppState.conversationAlertPreferences: ConversationAlertPreferences
    get() =
        synchronized(memberMuteLock) {
            alertPreferenceHolder?.takeIf { it.first === appContext }?.second
                ?: ConversationAlertPreferences(appContext).also { alertPreferenceHolder = appContext to it }
        }

/**
 * One process-wide store of this device's notification profiles (#3601), read by the UI, the post gate and the
 * Quick Settings tile.
 */
internal val WhiteNoiseAppState.notificationProfiles: NotificationProfilePreferences
    get() = NotificationProfilePreferences.forContext(appContext)

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
 * one adapter onto [LocalNotificationPolicy]. An active notification profile is a final AND gate: it can
 * only hold back an update the other rules allowed.
 */
internal fun WhiteNoiseAppState.shouldPostNotification(
    update: NotificationUpdateFfi,
    engineMuted: Boolean,
    directConversation: Boolean = update.isDm,
    reserveRepeat: Boolean = false,
): Boolean {
    val allowedByLocalPolicy =
        LocalNotificationPolicy.shouldPost(
            update = update,
            appInForeground = appInForeground,
            activeConversationGroupIdHex = activeConversationGroupIdHex,
            activeConversationAccountRef = activeConversationAccountRef,
            appLockScreenVisible = appLock.screenVisible,
            conversationNotifyMode = chatMutePreferences::mode,
            engineMuted = engineMuted,
            senderMutedInGroup = memberMutePreferences::isMuted,
            categoryEnabled = conversationAlertPreferences::choice,
            profileMuted = { account, author ->
                profileNotificationOverrides.get(account, author).mode == ProfileNotificationMode.MUTED
            },
        )
    return allowedByLocalPolicy && !suppressedByNotificationProfile(update, directConversation, reserveRepeat)
}

private var notificationProfileRepeatHolder: Pair<Context, NotificationProfileRepeats>? = null

/** Who was just held back by a notification profile, for the repeated-message exception. */
internal val WhiteNoiseAppState.notificationProfileRepeats: NotificationProfileRepeats
    get() =
        synchronized(memberMuteLock) {
            notificationProfileRepeatHolder?.takeIf { it.first === appContext }?.second
                ?: NotificationProfileRepeats(appContext).also { notificationProfileRepeatHolder = appContext to it }
        }

/**
 * Whether the notification profile in force now holds [update] back. A held-back message is remembered when
 * the profile lets repeated messages through, so the sender's next message within the window can notify.
 * Logs only a fixed vocabulary.
 */
private fun WhiteNoiseAppState.suppressedByNotificationProfile(
    update: NotificationUpdateFfi,
    directConversation: Boolean,
    reserveRepeat: Boolean,
): Boolean {
    val active = notificationProfiles.active() ?: return false
    val profile = active.profile
    val eligibleForRepeat = profile.allowRepeatedMessages && isNotificationProfileMessage(update)
    val suppressed =
        notificationProfileSuppresses(profile, update, directConversation = directConversation) &&
            !(
                eligibleForRepeat &&
                    notificationProfileRepeats.admit(profile.id, update, System.currentTimeMillis(), reserveRepeat)
            )
    if (suppressed) {
        appStateDebug { "notification suppressed_by=notification_profile source=${active.source.name.lowercase()}" }
    }
    return suppressed
}

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
    conversationAlertPreferences.retainAccounts(accountRefs)
    notificationProfiles.retainAccounts(accountRefs)
    notificationProfileRepeats.retainAccounts(accountRefs)
    publicJpegPreviewPreferences
        .retainAccounts(accounts.filter { it.label in accountRefs }.map { it.accountIdHex })
        .forEach(publicJpegPreviewLoader::clearPixels)
}

/** Public-profile rename updates only existing Android channel labels for currently signed-in local owners. */
internal fun WhiteNoiseAppState.refreshProfileNotificationChannelLabels(
    author: String,
    publicName: String?,
) {
    val generation = runtimeGeneration
    accounts.filter { it.isSignedInSigningAccount() }.forEach { account ->
        if (profileNotificationOverrides.hasChoice(account.label, author)) {
            val name = contactNicknameFor(account.label, author) ?: publicName
            launchMutation {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    dev.ipf.whitenoise.android.notifications.ProfileNotificationChannels(appContext).refreshExisting(
                        account.label,
                        author,
                        name,
                        isCurrent = {
                            runtimeGeneration == generation &&
                                !signOutInProgress &&
                                !wipeInProgress &&
                                accounts.any { it.label == account.label && it.isSignedInSigningAccount() }
                        },
                    )
                }
            }
        }
    }
}

/** A successful refresh retries sign-out/wipe cleanup; failed account reads must never prune other accounts. */
internal suspend fun WhiteNoiseAppState.retainProfileAlertsForAccounts(
    accounts: List<dev.ipf.marmotkit.AccountSummaryFfi>,
    canRetain: () -> Boolean,
): Boolean =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val retained = accounts.filterNot { it.signedOut }.map { it.label }
        val alertsSaved =
            runCatching {
                profileNotificationOverrides.retainAccounts(retained, canRetain)
            }.getOrDefault(false)
        val ownersSaved =
            runCatching {
                retainConversationChannelOwners(
                    context = appContext,
                    retainedAccountRefs = retained,
                    signingAccountRemains = accounts.any { it.isSignedInSigningAccount() },
                    canRetain = canRetain,
                )
            }.getOrDefault(false)
        val saved = alertsSaved && ownersSaved
        if (!saved) appStateDebug { "profile alert preference retention failed; retry on the next account refresh" }
        saved
    }
