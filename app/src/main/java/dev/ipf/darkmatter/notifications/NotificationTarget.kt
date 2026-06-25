package dev.ipf.darkmatter.notifications

import android.content.Intent
import android.net.Uri
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi

/** What a tapped notification should open. */
enum class NotificationTargetKind { MESSAGE, INVITE }

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

/** One step of the tap-to-navigate state machine (see [resolveNotificationNav]). */
sealed interface NotificationNavStep {
    /** Active account differs from the target's — switch first, then re-evaluate. */
    data class SwitchAccount(
        val accountRef: String,
    ) : NotificationNavStep

    /** Right account is active but its chat list hasn't loaded yet — wait. */
    data object AwaitChatList : NotificationNavStep

    /** Ready: open this conversation. */
    data class OpenConversation(
        val groupIdHex: String,
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
 *  - active account, chat list not ready → [NotificationNavStep.AwaitChatList]
 *  - ready + group present → [NotificationNavStep.OpenConversation]
 *  - ready + group absent → [NotificationNavStep.MissingConversation]
 *
 * @param chatListReady true only when the chat list is bound to [target]'s
 *   account AND finished its initial load, so a not-yet-loaded list never
 *   produces a spurious "conversation missing".
 */
fun resolveNotificationNav(
    target: NotificationTarget,
    knownAccountRefs: Set<String>,
    activeAccountRef: String?,
    chatListReady: Boolean,
    availableGroupIds: Set<String>,
): NotificationNavStep {
    if (target.accountRef !in knownAccountRefs) return NotificationNavStep.MissingAccount
    if (target.accountRef != activeAccountRef) return NotificationNavStep.SwitchAccount(target.accountRef)
    if (!chatListReady) return NotificationNavStep.AwaitChatList
    if (target.groupIdHex in availableGroupIds) return NotificationNavStep.OpenConversation(target.groupIdHex)
    return NotificationNavStep.MissingConversation
}

/** The activity's pending inbound-intent routing: a tapped-notification target
 *  and/or a White Noise profile deep link awaiting consumption by the UI. */
data class InboundIntentRouting(
    val notificationTarget: NotificationTarget?,
    val profilePayload: String?,
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
    dataString: String?,
    current: InboundIntentRouting,
): InboundIntentRouting =
    when {
        parsedTarget != null -> InboundIntentRouting(parsedTarget, null)
        dataString != null -> InboundIntentRouting(null, dataString)
        else -> current
    }

object NotificationNavigation {
    /** Constant action marking a content intent as a notification tap. */
    const val ACTION_OPEN = "dev.ipf.darkmatter.action.OPEN_NOTIFICATION"

    private const val EXTRA_ACCOUNT_REF = "dev.ipf.darkmatter.extra.ACCOUNT_REF"
    private const val EXTRA_GROUP_ID = "dev.ipf.darkmatter.extra.GROUP_ID_HEX"
    private const val EXTRA_MESSAGE_ID = "dev.ipf.darkmatter.extra.MESSAGE_ID_HEX"
    private const val EXTRA_KIND = "dev.ipf.darkmatter.extra.KIND"
    private const val URI_SCHEME = "darkmatter-notify"

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
    ) {
        intent.action = ACTION_OPEN
        intent.data = Uri.parse(targetUriString(notificationKey))
        applyTargetExtras(intent, target)
    }

    /** Parse a tapped content [intent] back into a target (untrusted). */
    fun parse(intent: Intent?): NotificationTarget? {
        intent ?: return null
        return parseExtras(
            action = intent.action,
            accountRef = intent.getStringExtra(EXTRA_ACCOUNT_REF),
            groupIdHex = intent.getStringExtra(EXTRA_GROUP_ID),
            messageIdHex = intent.getStringExtra(EXTRA_MESSAGE_ID),
            kindName = intent.getStringExtra(EXTRA_KIND),
        )
    }
}
