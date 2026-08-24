package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.media.editor.MessageDraftGeneration
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import kotlinx.coroutines.CompletableDeferred

internal data class ProfileGroupInviteToast(
    @param:StringRes val messageRes: Int,
    val detail: AppText? = null,
    // Failure outcomes carry a diagnostic detail worth pasting into a bug
    // report; pure-success toasts stay non-copyable (#796).
    val copyable: Boolean = false,
)

/** Generation fence for deleting only the composer draft represented by one send gesture. */
internal data class DraftSendClearToken(
    val accountRef: String,
    val groupIdHex: String,
    val generation: MessageDraftGeneration,
    val recoveryDraft: ComposerDraftSnapshot?,
)

internal class StartProfileChatNoActiveAccountException : IllegalStateException("No active account")

internal fun profileGroupInviteToast(outcome: ProfileGroupInviteOutcome): ProfileGroupInviteToast? {
    require(outcome.attempted >= 0) { "attempted must be non-negative" }
    require(outcome.failures in 0..outcome.attempted) { "failures must be between 0 and attempted" }
    if (outcome.attempted == 0) return null
    val failureDetail = outcome.firstFailure ?: AppText.Plain("")
    return when {
        outcome.failures == 0 && outcome.attempted == 1 ->
            ProfileGroupInviteToast(R.string.toast_invite_sent)
        outcome.failures == 0 ->
            ProfileGroupInviteToast(R.string.toast_invites_sent_to_groups)
        outcome.delivered == 0 ->
            ProfileGroupInviteToast(R.string.toast_couldnt_add_members, failureDetail, copyable = true)
        else ->
            ProfileGroupInviteToast(R.string.toast_invites_sent_to_groups_partial, failureDetail, copyable = true)
    }
}

/**
 * Whether the main shell should pop its in-shell navigation (Settings, an open
 * conversation, a Settings detail like Identity & Keys) back to the chat-list
 * root because the active account changed underneath it.
 *
 * The shell stays mounted whenever [AppPhase.Ready] is preserved across an
 * account change — e.g. Sign Out & Wipe of the active account while another
 * remains (issue #547), or the manual account switcher (#316). In those cases
 * the previously-rendered screen references an account that is no longer
 * active (or no longer exists), so it must be popped.
 *
 * Returns true only on a transition between two distinct non-null accounts.
 * The initial composition (and the recomposition after process death, where
 * the shell is being rebuilt from saved nav state) reports a null [previous],
 * so this returns false and the saved screen/conversation is preserved
 * (issue #386). A transition to null is the no-accounts case, which the
 * top-level phase router (AppPhase.Onboarding) already handles by tearing the
 * shell down, so it needs no in-shell reset here.
 */
internal fun shouldResetNavOnAccountChange(
    previous: String?,
    current: String?,
): Boolean = previous != null && current != null && previous != current

/**
 * The account ref the main shell should remember as "previous" after observing
 * [current], for the next [shouldResetNavOnAccountChange] comparison.
 *
 * Destructive Sign Out & Wipe drains the wiped account's live streams first,
 * which transiently sets activeAccountRef to null *before* it lands on the next
 * account (issue #610). If the shell adopted that intermediate null as its
 * previous ref, the eventual switch to the next account would look like a
 * null -> account transition — treated as a fresh composition — and the now-
 * deleted account's Identity & Keys screen would never be popped (regression of
 * #547). Keep the last real (non-null) account across the transient null so the
 * settle onto the next account is still seen as a distinct-account change. A
 * settle onto null is the no-accounts case, which AppPhase.Onboarding tears the
 * shell down for anyway, so retaining the old ref is harmless.
 */
internal fun nextNavAccountRef(
    previous: String?,
    current: String?,
): String? = current ?: previous

/**
 * Next exponential-backoff delay: double [current], clamped to [maxMillis].
 * Guards the multiply so a near-`Long.MAX_VALUE` input can't overflow to a
 * negative value below the clamp (returns [maxMillis] once at/over the cap).
 */
internal fun nextRetryBackoffMillis(
    current: Long,
    maxMillis: Long,
): Long {
    val positiveCurrent = current.coerceAtLeast(1L)
    return if (positiveCurrent >= maxMillis) {
        maxMillis
    } else if (positiveCurrent > Long.MAX_VALUE / 2) {
        maxMillis
    } else {
        (positiveCurrent * 2).coerceAtMost(maxMillis)
    }
}

data class ToastMessage(
    val title: AppText,
    val detail: AppText? = null,
    // Explicit copy-affordance gate (#796): only error/diagnostic toasts
    // should offer the snackbar Copy icon. Success confirmations and
    // transient state changes leave this false (the default) so the emit
    // site — not a message-body heuristic — decides.
    val copyable: Boolean = false,
    val tier: NoticeTier = NoticeTier.ActionableError,
    val diagnosticReport: String? = null,
)

data class TransientNotice(
    val id: Long,
    val title: AppText,
    val detail: AppText? = null,
    val conversation: ConversationNoticeDestination? = null,
)

data class ConversationNoticeDestination(
    val accountRef: String,
    val groupIdHex: String,
)

internal fun TransientNotice.isForConversation(
    accountRef: String,
    groupIdHex: String,
): Boolean =
    conversation?.let { destination ->
        destination.accountRef == accountRef &&
            destination.groupIdHex.equals(groupIdHex, ignoreCase = true)
    } == true

internal data class ProfilePresentation(
    val displayName: String?,
    val avatarUrl: String?,
) {
    companion object {
        val Empty = ProfilePresentation(displayName = null, avatarUrl = null)
    }
}

internal data class ProfileMaterializationReservation(
    val completion: CompletableDeferred<Unit>,
    val ownsRead: Boolean,
)

internal data class InviteNotificationIdentityRefreshResult(
    val posted: Boolean,
    val displayedName: String?,
    val contentRedacted: Boolean,
)

internal data class PostedGroupInviteIdentity(
    val update: NotificationUpdateFfi,
    val displayedName: String?,
)

internal fun postedGroupInviteIdentity(
    update: NotificationUpdateFfi,
    posted: Boolean,
    redactContent: Boolean,
    displayedName: String?,
): PostedGroupInviteIdentity? =
    if (posted && update.trigger == NotificationTriggerFfi.GROUP_INVITE) {
        PostedGroupInviteIdentity(
            update = update,
            displayedName = displayedName.takeUnless { redactContent },
        )
    } else {
        null
    }

internal data class NotificationAvatarPreWarmTarget(
    val senderAccountIdHex: String?,
    val senderAvatarUrl: String?,
    val resolveGroupAvatar: Boolean,
    val preWarmRemoteImages: Boolean,
)

internal fun shouldPreWarmNotificationAvatars(
    update: NotificationUpdateFfi,
    shouldPost: Boolean,
    canPost: Boolean,
): Boolean =
    shouldPost &&
        canPost &&
        !update.isFromSelf &&
        update.trigger == NotificationTriggerFfi.NEW_MESSAGE &&
        !LocalNotificationFormatter.isReaction(update)

internal fun notificationAvatarPreWarmTarget(
    update: NotificationUpdateFfi,
    appLockScreenVisible: Boolean,
): NotificationAvatarPreWarmTarget =
    NotificationAvatarPreWarmTarget(
        senderAccountIdHex =
            update.sender.accountIdHex
                .trim()
                .takeIf { it.isNotEmpty() },
        senderAvatarUrl = ProfileSanitizer.protocolImageUrl(update.sender.pictureUrl),
        resolveGroupAvatar = !update.isDm,
        preWarmRemoteImages = !appLockScreenVisible,
    )

internal data class PreWarmedNotificationAvatars(
    val senderAvatarUrl: String?,
    val groupAvatarUrl: String?,
)

/** Posts the privacy-correct fallback card before scheduling optional enrichment. */
internal suspend fun postBeforeNotificationEnrichment(
    post: suspend () -> Boolean,
    scheduleEnrichment: () -> Unit,
): Boolean {
    val posted = post()
    if (posted) scheduleEnrichment()
    return posted
}
