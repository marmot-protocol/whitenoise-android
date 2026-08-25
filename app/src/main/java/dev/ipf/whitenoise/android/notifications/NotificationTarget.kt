package dev.ipf.whitenoise.android.notifications

import android.content.Intent
import android.net.Uri
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.state.nextRetryBackoffMillis
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/** What a tapped notification should open. */
enum class NotificationTargetKind { MESSAGE, INVITE, CHAT_LIST }

/**
 * Navigation target carried by a notification's content intent. Built from a
 * [NotificationUpdateFfi] on the producer side and parsed back (as untrusted
 * input) when the user taps the notification.
 */
data class NotificationTarget(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String?,
    val kind: NotificationTargetKind,
)

/**
 * True when [handledTarget] and [handledRequestId] still match the activity's
 * pending inbound notification — i.e. it is safe to clear that target on
 * consume. An equal target with a stale request id (same-target retap) must not
 * match.
 */
internal fun inboundNotificationHandledMatchesCurrent(
    inboundTarget: NotificationTarget?,
    inboundRequestId: Long,
    handledTarget: NotificationTarget,
    handledRequestId: Long,
): Boolean = inboundTarget == handledTarget && inboundRequestId == handledRequestId

/** One step of the tap-to-navigate state machine (see [resolveNotificationNav]). */
sealed interface NotificationNavStep {
    /** Active account differs from the target's — switch first, then re-evaluate. */
    data class SwitchAccount(
        val accountRef: String,
    ) : NotificationNavStep

    /** Right account is active; read the notified message conversation directly from SQLite. */
    data object LoadMessageDirectly : NotificationNavStep

    /** Right account is active but its chat list hasn't loaded yet — wait for an invite row. */
    data object AwaitChatList : NotificationNavStep

    /** Membership-removal target: land on Chats without opening the dead group. */
    data object OpenChatList : NotificationNavStep

    /**
     * Invite target: initial chat-list snapshot is ready but the pending invite
     * row has not materialized yet — wait for the live row or an authoritative
     * read before treating the invite as gone (#1767).
     */
    data object AwaitInviteRow : NotificationNavStep

    /** Ready: open this conversation and optionally persist its read-through cursor. */
    data class OpenConversation(
        val groupIdHex: String,
        val readThroughMessageIdHex: String?,
    ) : NotificationNavStep

    /** Target account no longer exists locally — fall back to the chat list. */
    data object MissingAccount : NotificationNavStep

    /** Account is loaded but the conversation is gone — fall back to the chat list. */
    data object MissingConversation : NotificationNavStep
}

/**
 * Pure decision for routing a tapped-notification [target] given the current
 * app state. Encodes the issue's multi-account rules:
 *
 *  - unknown account → [NotificationNavStep.MissingAccount] (untrusted input)
 *  - background account → [NotificationNavStep.SwitchAccount] (no switch when
 *    already active)
 *  - active account, chat list not ready + message → [NotificationNavStep.LoadMessageDirectly]
 *  - active account, chat list not ready + invite → [NotificationNavStep.AwaitChatList]
 *  - active account + chat-list target → [NotificationNavStep.OpenChatList]
 *  - ready + group present → [NotificationNavStep.OpenConversation], carrying the
 *    notified message id (already non-blank and MESSAGE-only by construction) so
 *    the persisted read cursor can advance before composition
 *  - ready + MESSAGE group absent → [NotificationNavStep.MissingConversation]
 *  - ready + INVITE absent from the snapshot → [NotificationNavStep.AwaitInviteRow]
 *    until the row materializes or [inviteAuthoritativelyUnavailable] is true
 *
 * @param chatListReady true only when the chat list is bound to [target]'s
 *   account AND finished its initial load, so a not-yet-loaded list never
 *   produces a spurious "conversation missing".
 * @param inviteRowMaterialized true when the invite's group id is present on the
 *   live chat-list backing rows, even if the debounced [availableGroupIds]
 *   snapshot has not caught up yet.
 * @param inviteRowMembershipOpenable when an invite row is reachable, false if
 *   terminal self-membership (LEFT/REMOVED) proves the invite is no longer
 *   openable even though the archived row still exists.
 * @param exactPreloadReady true only when this exact request's targeted local
 *   read has already produced the conversation. A ready message target opens
 *   immediately while account activation finishes behind it, its content read
 *   from local history for the notification's own account (#586).
 */
fun resolveNotificationNav(
    target: NotificationTarget,
    knownAccountRefs: Set<String>,
    activeAccountRef: String?,
    chatListReady: Boolean,
    availableGroupIds: Set<String>,
    inviteRowMaterialized: Boolean = false,
    inviteRowMembershipOpenable: Boolean = true,
    inviteAuthoritativelyUnavailable: Boolean = false,
    exactPreloadReady: Boolean = false,
): NotificationNavStep {
    if (target.accountRef !in knownAccountRefs) return NotificationNavStep.MissingAccount
    if (target.accountRef != activeAccountRef) {
        if (target.kind == NotificationTargetKind.MESSAGE && exactPreloadReady) {
            return NotificationNavStep.LoadMessageDirectly
        }
        return NotificationNavStep.SwitchAccount(target.accountRef)
    }
    if (target.kind == NotificationTargetKind.CHAT_LIST) return NotificationNavStep.OpenChatList
    if (!chatListReady) {
        return if (target.kind == NotificationTargetKind.MESSAGE) {
            NotificationNavStep.LoadMessageDirectly
        } else {
            NotificationNavStep.AwaitChatList
        }
    }
    if (target.kind == NotificationTargetKind.MESSAGE) {
        if (target.groupIdHex in availableGroupIds) {
            return NotificationNavStep.OpenConversation(target.groupIdHex, target.messageIdHex)
        }
        return NotificationNavStep.MissingConversation
    }
    if (inviteAuthoritativelyUnavailable) {
        return NotificationNavStep.MissingConversation
    }
    val inviteRowReachable =
        target.groupIdHex in availableGroupIds ||
            inviteRowMaterialized
    if (inviteRowReachable) {
        if (!inviteRowMembershipOpenable) {
            return NotificationNavStep.MissingConversation
        }
        return NotificationNavStep.OpenConversation(target.groupIdHex, target.messageIdHex)
    }
    return NotificationNavStep.AwaitInviteRow
}

/** Outcome of a targeted authoritative read while routing an invite notification (#1767). */
internal sealed interface NotificationInviteAuthoritativeOutcome {
    data object OpenConversation : NotificationInviteAuthoritativeOutcome

    /** Withdrawn, declined, or deleted — terminal fallback. */
    data object Unavailable : NotificationInviteAuthoritativeOutcome

    /** Transient failure or not yet materialized — keep waiting on the live row. */
    data object Inconclusive : NotificationInviteAuthoritativeOutcome
}

internal sealed interface NotificationMessageDirectLoadOutcome<out T> {
    data class OpenConversation<T>(
        val item: T,
    ) : NotificationMessageDirectLoadOutcome<T>

    /** Keep the tap pending and let the broad chat-list route settle. */
    data object AwaitChatList : NotificationMessageDirectLoadOutcome<Nothing>
}

/** Identity of one request-scoped message-notification preload. */
internal data class NotificationMessagePreloadKey(
    val requestId: Long,
    val accountRef: String,
    val groupIdHex: String,
)

/**
 * Transient result of reading a notification target while its account is activating.
 *
 * This deliberately lives only in the shell navigation composition. It is not a
 * protocol cache, and a result is usable only by the exact request that created it.
 */
internal sealed interface NotificationMessagePreloadState<out T> {
    data object Loading : NotificationMessagePreloadState<Nothing>

    data class Ready<T>(
        val item: T,
    ) : NotificationMessagePreloadState<T>

    /** The broad chat-list route remains the authoritative fallback. */
    data object Failed : NotificationMessagePreloadState<Nothing>
}

internal data class NotificationMessagePreload<out T>(
    val key: NotificationMessagePreloadKey,
    val state: NotificationMessagePreloadState<T>,
)

internal fun notificationMessagePreloadKey(
    target: NotificationTarget?,
    requestId: Long,
): NotificationMessagePreloadKey? =
    target
        ?.takeIf { it.kind == NotificationTargetKind.MESSAGE }
        ?.let {
            NotificationMessagePreloadKey(
                requestId = requestId,
                accountRef = it.accountRef,
                groupIdHex = it.groupIdHex,
            )
        }

/** Rejects a completed read from an older tap, even when the target is identical. */
internal fun <T> NotificationMessagePreload<T>?.stateFor(key: NotificationMessagePreloadKey?) =
    if (this != null && key != null && this.key == key) {
        state
    } else {
        null
    }

/** Performs the one-group local read without swallowing structured cancellation (#586). */
@Suppress("MaxLineLength")
internal suspend fun <T> loadNotificationMessageDirectly(load: suspend () -> T): NotificationMessageDirectLoadOutcome<T> =
    try {
        NotificationMessageDirectLoadOutcome.OpenConversation(load())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        NotificationMessageDirectLoadOutcome.AwaitChatList
    }

/**
 * Classifies whether an authoritative invite target is still openable.
 * Build [result] with [dev.ipf.whitenoise.android.state.runCatchingCancellable] so cancellation propagates.
 */
internal fun classifyInviteAuthoritativeLoad(result: Result<Boolean>): NotificationInviteAuthoritativeOutcome {
    if (result.isSuccess) {
        return if (result.getOrThrow()) {
            NotificationInviteAuthoritativeOutcome.OpenConversation
        } else {
            NotificationInviteAuthoritativeOutcome.Unavailable
        }
    }
    return NotificationInviteAuthoritativeOutcome.Inconclusive
}

/** Whether an authoritative group read still represents an openable invite target. */
internal fun inviteAuthoritativeGroupAvailable(
    pendingConfirmation: Boolean,
    selfMembership: SelfMembershipFfi,
): Boolean =
    when (selfMembership) {
        SelfMembershipFfi.LEFT, SelfMembershipFfi.REMOVED -> false
        else -> pendingConfirmation || selfMembership == SelfMembershipFfi.MEMBER
    }

/** Max authoritative invite probe attempts before releasing the route (#1767). */
internal const val NOTIFICATION_INVITE_AUTHORITATIVE_MAX_PROBE_ATTEMPTS = 3

internal const val NOTIFICATION_INVITE_AUTHORITATIVE_PROBE_INITIAL_BACKOFF_MILLIS = 250L

internal const val NOTIFICATION_INVITE_AUTHORITATIVE_PROBE_MAX_BACKOFF_MILLIS = 2_000L

internal fun inviteAuthoritativeProbeShouldRetry(
    probeAttempts: Int,
    maxProbeAttempts: Int = NOTIFICATION_INVITE_AUTHORITATIVE_MAX_PROBE_ATTEMPTS,
): Boolean = probeAttempts < maxProbeAttempts

internal fun inviteAuthoritativeProbeBackoffMillis(
    probeAttempts: Int,
    initialMillis: Long = NOTIFICATION_INVITE_AUTHORITATIVE_PROBE_INITIAL_BACKOFF_MILLIS,
    maxMillis: Long = NOTIFICATION_INVITE_AUTHORITATIVE_PROBE_MAX_BACKOFF_MILLIS,
): Long {
    var delayMillis = initialMillis
    repeat((probeAttempts - 1).coerceAtLeast(0)) {
        delayMillis = nextRetryBackoffMillis(delayMillis, maxMillis)
    }
    return delayMillis
}

internal suspend fun retryInviteAuthoritativeLoad(
    probeAttempts: Int = 0,
    maxProbeAttempts: Int = NOTIFICATION_INVITE_AUTHORITATIVE_MAX_PROBE_ATTEMPTS,
    onProbeAttempt: (Int) -> Unit = {},
    load: suspend () -> Result<Boolean>,
    sleep: suspend (Long) -> Unit = { delay(it) },
): NotificationInviteAuthoritativeOutcome {
    var attempts = probeAttempts
    while (inviteAuthoritativeProbeShouldRetry(attempts, maxProbeAttempts)) {
        attempts += 1
        // Commit the budget before entering the cancellable load so an effect
        // restart cannot silently restore this attempt.
        onProbeAttempt(attempts)
        val outcome = classifyInviteAuthoritativeLoad(load())
        if (outcome != NotificationInviteAuthoritativeOutcome.Inconclusive ||
            !inviteAuthoritativeProbeShouldRetry(attempts, maxProbeAttempts)
        ) {
            return outcome
        }
        sleep(inviteAuthoritativeProbeBackoffMillis(attempts))
    }
    return NotificationInviteAuthoritativeOutcome.Inconclusive
}

/** The activity's pending inbound-intent routing: a tapped-notification target,
 *  a system-share request, and/or a White Noise profile deep link awaiting
 *  consumption by the UI. */
data class InboundIntentRouting(
    val notificationTarget: NotificationTarget?,
    val profilePayload: String?,
    val shareRequest: ShareRequest? = null,
    /** Advances for every parsed notification tap so equal targets remain distinct UI requests. */
    val notificationRequestId: Long = 0L,
)

/**
 * Resolve a newly-arrived intent against the [current] pending routing:
 * - a notification tap ([parsedTarget] non-null) wins and clears any pending
 *   profile link (the two are mutually exclusive);
 * - otherwise a White Noise data URI ([dataString]) becomes the profile
 *   payload;
 * - otherwise — a dataless, non-notification intent such as a bare launcher
 *   relaunch — the [current] target/link is left intact rather than being
 *   silently discarded. See issue #67.
 */
fun routeInboundIntent(
    parsedTarget: NotificationTarget?,
    shareRequest: ShareRequest?,
    dataString: String?,
    current: InboundIntentRouting,
): InboundIntentRouting =
    when {
        parsedTarget != null ->
            current.copy(
                notificationTarget = parsedTarget,
                profilePayload = null,
                shareRequest = null,
                notificationRequestId = current.notificationRequestId + 1L,
            )
        shareRequest != null ->
            current.copy(
                notificationTarget = null,
                profilePayload = null,
                shareRequest = shareRequest,
            )
        dataString != null ->
            current.copy(
                notificationTarget = null,
                profilePayload = dataString,
                shareRequest = null,
            )
        else -> current
    }

object NotificationNavigation {
    /** Constant action marking a content intent as a notification tap. */
    const val ACTION_OPEN = "dev.ipf.whitenoise.android.action.OPEN_NOTIFICATION"

    private const val EXTRA_ACCOUNT_REF = "dev.ipf.whitenoise.android.extra.ACCOUNT_REF"
    private const val EXTRA_GROUP_ID = "dev.ipf.whitenoise.android.extra.GROUP_ID_HEX"
    private const val EXTRA_MESSAGE_ID = "dev.ipf.whitenoise.android.extra.MESSAGE_ID_HEX"
    private const val EXTRA_KIND = "dev.ipf.whitenoise.android.extra.KIND"
    private const val EXTRA_TAP_TOKEN = "dev.ipf.whitenoise.android.extra.TAP_TOKEN"
    private const val URI_SCHEME = "whitenoise-notify"
    private const val URI_HOST_OPEN = "open"

    /**
     * Per-notification data URI. Android compares a PendingIntent's *data*
     * (not its extras) for equivalence, so a unique URI per [notificationKey]
     * keeps each notification's click target distinct — otherwise a later
     * notification would overwrite an earlier one's target.
     */
    fun targetUriString(notificationKey: String): String = "$URI_SCHEME://open/" + notificationKey.ifBlank { "unknown" }

    /** Stable request code per notification (belt-and-suspenders with the URI). */
    fun requestCode(notificationKey: String): Int = notificationKey.hashCode()

    /** Build a target from an FFI update, or null if required ids are missing. */
    fun fromUpdate(update: NotificationUpdateFfi): NotificationTarget? {
        val accountRef = update.accountRef.takeIf { it.isNotBlank() } ?: return null
        val groupIdHex = update.groupIdHex.takeIf { it.isNotBlank() } ?: return null
        val kind =
            when (update.trigger) {
                NotificationTriggerFfi.NEW_MESSAGE -> NotificationTargetKind.MESSAGE
                NotificationTriggerFfi.GROUP_INVITE -> NotificationTargetKind.INVITE
                NotificationTriggerFfi.REMOVED_FROM_GROUP -> NotificationTargetKind.CHAT_LIST
                NotificationTriggerFfi.MADE_ADMIN,
                NotificationTriggerFfi.REMOVED_AS_ADMIN,
                -> return null
            }
        // messageId is only meaningful for message notifications.
        val messageIdHex =
            update.messageIdHex
                ?.takeIf { it.isNotBlank() && kind == NotificationTargetKind.MESSAGE }
        return NotificationTarget(accountRef, groupIdHex, messageIdHex, kind)
    }

    /**
     * Pure parse from already-extracted intent fields. The Android [parse]
     * overload pulls these out of an [Intent] and delegates here so the
     * validation is unit-testable without the framework.
     */
    fun parseExtras(
        action: String?,
        accountRef: String?,
        groupIdHex: String?,
        messageIdHex: String?,
        kindName: String?,
    ): NotificationTarget? {
        if (action != ACTION_OPEN) return null
        return parseTargetExtras(accountRef, groupIdHex, messageIdHex, kindName)
    }

    /** Parse target extras independent of the Intent action. Used by notification actions. */
    fun parseTargetExtras(
        accountRef: String?,
        groupIdHex: String?,
        messageIdHex: String?,
        kindName: String?,
    ): NotificationTarget? {
        val account = accountRef?.takeIf { it.isNotBlank() } ?: return null
        val group = groupIdHex?.takeIf { it.isNotBlank() } ?: return null
        val kind = NotificationTargetKind.entries.firstOrNull { it.name == kindName } ?: return null
        val message = messageIdHex?.takeIf { it.isNotBlank() && kind == NotificationTargetKind.MESSAGE }
        return NotificationTarget(account, group, message, kind)
    }

    /** Stamp [target]'s validated routing fields onto [intent]. */
    fun applyTargetExtras(
        intent: Intent,
        target: NotificationTarget,
    ) {
        intent.putExtra(EXTRA_ACCOUNT_REF, target.accountRef)
        intent.putExtra(EXTRA_GROUP_ID, target.groupIdHex)
        intent.putExtra(EXTRA_MESSAGE_ID, target.messageIdHex)
        intent.putExtra(EXTRA_KIND, target.kind.name)
    }

    /** Parse target extras from an [Intent] whose action has already been validated. */
    fun parseTarget(intent: Intent): NotificationTarget? =
        parseTargetExtras(
            accountRef = intent.getStringExtra(EXTRA_ACCOUNT_REF),
            groupIdHex = intent.getStringExtra(EXTRA_GROUP_ID),
            messageIdHex = intent.getStringExtra(EXTRA_MESSAGE_ID),
            kindName = intent.getStringExtra(EXTRA_KIND),
        )

    /** Stamp [target] onto a content [intent] (action + unique data + extras). */
    fun applyToIntent(
        intent: Intent,
        target: NotificationTarget,
        notificationKey: String,
        tapToken: String,
    ) {
        intent.action = ACTION_OPEN
        intent.data = Uri.parse(targetUriString(notificationKey))
        intent.putExtra(EXTRA_TAP_TOKEN, tapToken)
        applyTargetExtras(intent, target)
    }

    /** Parse a tapped content [intent] back into a target (untrusted). */
    fun parse(
        intent: Intent?,
        isTrustedTapToken: (notificationKey: String, tapToken: String?) -> Boolean = { _, _ -> false },
    ): NotificationTarget? {
        intent ?: return null
        val notificationKey = notificationKeyFrom(intent) ?: return null
        if (!isTrustedNotificationTap(notificationKey, intent.getStringExtra(EXTRA_TAP_TOKEN), isTrustedTapToken)) return null
        return parseExtras(
            action = intent.action,
            accountRef = intent.getStringExtra(EXTRA_ACCOUNT_REF),
            groupIdHex = intent.getStringExtra(EXTRA_GROUP_ID),
            messageIdHex = intent.getStringExtra(EXTRA_MESSAGE_ID),
            kindName = intent.getStringExtra(EXTRA_KIND),
        )
    }

    internal fun isTrustedNotificationTap(
        notificationKey: String?,
        tapToken: String?,
        isTrustedTapToken: (notificationKey: String, tapToken: String?) -> Boolean,
    ): Boolean {
        val key = notificationKey?.takeIf { it.isNotBlank() } ?: return false
        return isTrustedTapToken(key, tapToken)
    }

    internal fun notificationKeyFrom(intent: Intent): String? {
        val uri = intent.data ?: return null
        if (uri.scheme != URI_SCHEME || uri.host != URI_HOST_OPEN) return null
        return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
