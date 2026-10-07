package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.ipf.whitenoise.android.MainActivity

/** Launcher intents carry revocable credentials for one exact account and conversation incarnation. */
internal object PinnedConversationNavigation {
    const val ACTION_OPEN = "dev.ipf.whitenoise.android.OPEN_PINNED_CONVERSATION"
    const val ACTION_PINNED = "dev.ipf.whitenoise.android.CONVERSATION_PINNED"
    private const val SCHEME = "whitenoise-pinned"

    /** Uses an explicit app component; no message content, notification token or unbound fallback is carried. */
    fun intent(
        context: Context,
        capability: PinnedConversationCapability,
    ): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            data = uri(capability)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    /** The unique platform ID binds the callback to this incarnation, never a later recreated pin. */
    fun callbackIntent(
        context: Context,
        capability: PinnedConversationCapability,
    ): Intent =
        Intent(context, PinnedConversationPinReceiver::class.java).apply {
            action = ACTION_PINNED
            data = uri(capability)
        }

    /** Accepts only the strict launcher URI shape; credentials are validated separately against private storage. */
    fun capability(intent: Intent?): PinnedConversationCapability? =
        runCatching {
            val data = intent?.data ?: return@runCatching null
            val expectedLocation = data.scheme == SCHEME && data.host == "conversation" && data.fragment.isNullOrEmpty()
            if (!expectedLocation) return@runCatching null
            val account = data.getQueryParameter("account") ?: return@runCatching null
            val group = data.getQueryParameter("group") ?: return@runCatching null
            val accountToken = data.getQueryParameter("account_token") ?: return@runCatching null
            val groupToken = data.getQueryParameter("group_token") ?: return@runCatching null
            val capability = PinnedConversationCapability(account, group, accountToken, groupToken)
            capability.takeIf { data.path == "/${it.shortcutId}" }
        }.getOrNull()

    /** A recognized invalid or locked pin deliberately replaces any retained conversation with the app root. */
    fun target(
        context: Context,
        intent: Intent?,
        activeAccountRef: String?,
        locked: Boolean,
    ): NotificationTarget? {
        if (intent?.action != ACTION_OPEN) return null
        val capability = capability(intent)
        return if (!locked && capability != null && PinnedConversationTokens.create(context).isValid(capability)) {
            NotificationTarget(
                capability.accountRef,
                capability.groupIdHex,
                null,
                NotificationTargetKind.MESSAGE,
                shortcutCapability = capability,
            )
        } else {
            NotificationTarget(activeAccountRef.orEmpty(), "", null, NotificationTargetKind.CHAT_LIST)
        }
    }

    /** Never reconstructs a missing account/group from another account's same-ID conversation. */
    fun isCurrent(
        context: Context,
        target: NotificationTarget,
        signedInAccounts: Set<String>,
        locked: Boolean,
    ): Boolean {
        val capability = target.shortcutCapability ?: return true
        return !locked &&
            target.accountRef in signedInAccounts &&
            capability.accountRef == target.accountRef &&
            capability.groupIdHex == target.groupIdHex &&
            PinnedConversationTokens.create(context).isValid(capability)
    }

    /** Android's launcher is the sole owner of the destination mapping; no routing table is persisted in the app. */
    private fun uri(capability: PinnedConversationCapability): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority("conversation")
            .appendPath(capability.shortcutId)
            .appendQueryParameter("account", capability.accountRef)
            .appendQueryParameter("group", capability.groupIdHex)
            .appendQueryParameter("account_token", capability.accountToken)
            .appendQueryParameter("group_token", capability.groupToken)
            .build()
}
