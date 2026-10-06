package dev.ipf.whitenoise.android.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader

/** Request acceptance is distinct from the launcher's later approval; no app-owned pinned flag is stored. */
internal enum class ConversationPinResult { REQUESTED, ALREADY_PINNED, UNSUPPORTED, UNAVAILABLE, FAILED }

/** A transient source projection; no launcher presentation or protocol data is persisted by Android. */
internal data class PinnedConversationPresentation(
    val title: String,
    val avatar: Bitmap? = null,
)

/** The platform owns approved pins, including denial, duplicate requests and process recreation. */
internal interface PinnedShortcutPlatform {
    /** Availability belongs to the current launcher and may change while the app is backgrounded. */
    fun supported(): Boolean

    /** Reads OS inventory; accepted requests are absent until the user actually approves them. */
    fun shortcuts(): List<ShortcutInfoCompat>

    /** True means the request was handed off, not that the user approved or installed the shortcut. */
    fun request(
        shortcut: ShortcutInfoCompat,
        callback: IntentSender,
    ): Boolean

    /** Returns whether platform presentation updates were accepted so callers can try generic fallback. */
    fun update(shortcuts: List<ShortcutInfoCompat>): Boolean

    /** Retires only the exact IDs supplied; a recreated destination has a distinct generation-bound ID. */
    fun disable(ids: List<String>)
}

/** Android launcher adapter; app-private credentials remain separate from platform inventory. */
internal class AndroidPinnedShortcutPlatform(
    private val context: Context,
) : PinnedShortcutPlatform {
    /** Rechecks the active launcher instead of caching support across foreground sessions. */
    override fun supported(): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Includes cached and pinned variants so cleanup does not depend on dynamic-shortcut recency. */
    override fun shortcuts(): List<ShortcutInfoCompat> =
        ShortcutManagerCompat.getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
                ShortcutManagerCompat.FLAG_MATCH_CACHED,
        )

    /** The system owns approval and invokes the explicit callback only after a successful pin. */
    override fun request(
        shortcut: ShortcutInfoCompat,
        callback: IntentSender,
    ): Boolean = ShortcutManagerCompat.requestPinShortcut(context, shortcut, callback)

    /** Updates existing IDs in place without requesting approval or creating a new pin. */
    override fun update(shortcuts: List<ShortcutInfoCompat>): Boolean =
        ShortcutManagerCompat.updateShortcuts(
            context,
            shortcuts,
        )

    /** Disables removed destinations behind the localized unavailable message, then drops their other variants. */
    override fun disable(ids: List<String>) {
        if (ids.isEmpty()) return
        ShortcutManagerCompat.disableShortcuts(context, ids, context.getString(R.string.pinned_shortcut_unavailable))
        ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
        ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
    }
}

/** Explicit launcher requests and refreshes share the notification privacy publication lock. */
internal class PinnedConversationShortcuts(
    private val context: Context,
    private val platform: PinnedShortcutPlatform = AndroidPinnedShortcutPlatform(context),
    private val tokens: PinnedConversationTokens = PinnedConversationTokens.create(context),
) {
    /** Runs off-main after credential creation; revalidates ownership under the publication lock at commit. */
    fun request(
        capability: PinnedConversationCapability,
        title: String,
        avatarUrl: String?,
        avatar: Bitmap? = null,
        stillCurrent: () -> Boolean,
    ): ConversationPinResult =
        synchronized(UserEventNotificationGroup.mutationLock) {
            runCatching {
                when {
                    !platform.supported() -> ConversationPinResult.UNSUPPORTED
                    !stillCurrent() || !tokens.isValid(capability) -> ConversationPinResult.UNAVAILABLE
                    platform.shortcuts().any { it.id == capability.shortcutId && it.isPinned && it.isEnabled } -> {
                        val current = build(capability, title, avatarUrl, avatar)
                        if (!stillCurrent() || !tokens.isValid(capability)) {
                            return@synchronized ConversationPinResult.UNAVAILABLE
                        }
                        if (!runCatching { platform.update(listOf(current)) }.getOrDefault(false)) {
                            platform.update(listOf(genericNotificationShortcut(context, current)))
                        }
                        ConversationPinResult.ALREADY_PINNED
                    }
                    else -> {
                        val callback =
                            PendingIntent
                                .getBroadcast(
                                    context,
                                    0,
                                    PinnedConversationNavigation.callbackIntent(context, capability),
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                                ).intentSender
                        val current = build(capability, title, avatarUrl, avatar)
                        if (!stillCurrent() || !tokens.isValid(capability)) {
                            return@synchronized ConversationPinResult.UNAVAILABLE
                        }
                        val accepted = platform.request(current, callback)
                        if (accepted) ConversationPinResult.REQUESTED else ConversationPinResult.FAILED
                    }
                }
            }.getOrDefault(ConversationPinResult.FAILED)
        }

    /** A stale approval can scrub/disable only its old platform ID; it never recreates a shortcut. */
    fun approved(capability: PinnedConversationCapability) =
        synchronized(UserEventNotificationGroup.mutationLock) {
            val prior = runCatching { platform.shortcuts().firstOrNull { it.id == capability.shortcutId } }.getOrNull()
            if (!tokens.isValid(capability)) {
                try {
                    prior?.let { platform.update(listOf(genericNotificationShortcut(context, it))) }
                } finally {
                    platform.disable(listOf(capability.shortcutId))
                }
            } else if (prior != null && !shortcutPreviewAllowed(context, prior)) {
                platform.update(listOf(genericNotificationShortcut(context, prior)))
            }
        }

    /** Queries launcher ownership off-main before the caller prepares titles or cached avatar pixels. */
    fun hasPinnedConversations(accountRef: String): Boolean =
        synchronized(UserEventNotificationGroup.mutationLock) {
            platform.shortcuts().any { it.isPinned && pinCapability(it)?.accountRef == accountRef }
        }

    /**
     * Current labels come from source projections; omitted valid pins retain only current-privacy presentations.
     * Obsolete refreshes cannot publish after waiting for the lock.
     */
    fun refresh(
        accountRef: String,
        presentations: Map<String, PinnedConversationPresentation>,
        isCurrent: () -> Boolean = { true },
    ): Boolean =
        synchronized(UserEventNotificationGroup.mutationLock) {
            if (!isCurrent()) return@synchronized false
            val pinned = platform.shortcuts().filter { it.isPinned && pinCapability(it)?.accountRef == accountRef }
            if (pinned.isEmpty()) return@synchronized true
            val revoked = mutableListOf<String>()
            val prepared =
                pinned.mapNotNull { original ->
                    val capability = checkNotNull(pinCapability(original))
                    val presentation = presentations[capability.groupIdHex]
                    if (!tokens.isValid(capability)) {
                        revoked += original.id
                        genericNotificationShortcut(context, original)
                    } else if (presentation == null && shortcutPreviewAllowed(context, original)) {
                        null
                    } else if (presentation == null) {
                        genericNotificationShortcut(context, original)
                    } else {
                        runCatching { build(capability, presentation.title, null, presentation.avatar) }
                            .getOrElse { genericNotificationShortcut(context, original) }
                    }
                }
            val generic = prepared.map { genericNotificationShortcut(context, it) }
            if (!isCurrent()) return@synchronized false
            if (prepared.isEmpty()) {
                platform.disable(revoked)
                return@synchronized true
            }
            val updated =
                runCatching { platform.update(prepared) }.getOrDefault(false) ||
                    (isCurrent() && runCatching { platform.update(generic) }.getOrDefault(false))
            platform.disable(revoked)
            updated
        }

    /** Revoke pending requests before consulting inventory, which cannot yet contain an unapproved launcher request. */
    fun removeGroup(
        accountRef: String,
        groupIdHex: String,
    ) = synchronized(UserEventNotificationGroup.mutationLock) {
        tokens.revokeGroup(accountRef, groupIdHex)
        removeRevokedGroup(accountRef, groupIdHex)
    }

    /** Platform-only cleanup; the caller must already hold the durable account/group revocation fence. */
    fun removeRevokedGroup(
        accountRef: String,
        groupIdHex: String,
    ) = synchronized(UserEventNotificationGroup.mutationLock) {
        val baseId = conversationShortcutId(accountRef, groupIdHex) ?: return@synchronized
        val old = platform.shortcuts().filter { it.id == baseId || it.id.startsWith("$baseId-pin-") }
        val ids = old.map { it.id }
        try {
            if (old.isNotEmpty()) platform.update(old.map { genericNotificationShortcut(context, it) })
        } finally {
            platform.disable(ids)
        }
    }

    /** Builds privacy-checked labels and cached pixels only; it never acquires media or includes message text. */
    private fun build(
        capability: PinnedConversationCapability,
        title: String,
        avatarUrl: String?,
        avatar: Bitmap?,
    ): ShortcutInfoCompat {
        val privacy = NotificationPreviewPreferences.capture(context)
        val label =
            if (privacy.allowed) {
                title.trim().ifBlank { context.getString(R.string.app_name) }
            } else {
                context.getString(R.string.app_name)
            }
        val icon =
            if (privacy.allowed) {
                val bitmap = avatar ?: AvatarImageLoader.peekBitmap(avatarUrl)
                notificationConversationIcon(label, capability.shortcutId, bitmap?.let(::boundedPinnedAvatar))
            } else {
                IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise)
            }
        val extras =
            checkNotNull(conversationShortcutAccountExtras(capability.accountRef)).apply {
                stampShortcutPreview(privacy, this)
                putBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN, !privacy.allowed)
            }
        return ShortcutInfoCompat
            .Builder(context, capability.shortcutId)
            .setShortLabel(label.take(MAX_LABEL_LENGTH))
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(PinnedConversationNavigation.intent(context, capability))
            .setLongLived(true)
            .setExtras(extras)
            .build()
    }

    /** Bound platform icon bytes without acquiring media or mutating the source bitmap. */
    private fun boundedPinnedAvatar(bitmap: Bitmap): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= MAX_ICON_EDGE) return bitmap
        val scale = MAX_ICON_EDGE.toFloat() / edge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** Only our explicit pin action can identify an owned pinned conversation. */
    private fun pinCapability(shortcut: ShortcutInfoCompat): PinnedConversationCapability? =
        shortcut.intent
            .takeIf { it.action == PinnedConversationNavigation.ACTION_OPEN }
            ?.let(PinnedConversationNavigation::capability)
            ?.takeIf { it.shortcutId == shortcut.id }

    private companion object {
        const val MAX_LABEL_LENGTH = 24
        const val MAX_ICON_EDGE = 192
    }
}

/** Receives only the app-created explicit success callback; denial produces no callback or persisted pin state. */
class PinnedConversationPinReceiver : BroadcastReceiver() {
    /** Rejects unrelated broadcasts and limits late approval to scrubbing or disabling its exact old ID. */
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != PinnedConversationNavigation.ACTION_PINNED) return
        val capability = PinnedConversationNavigation.capability(intent) ?: return
        runCatching { PinnedConversationShortcuts(context).approved(capability) }
    }
}
