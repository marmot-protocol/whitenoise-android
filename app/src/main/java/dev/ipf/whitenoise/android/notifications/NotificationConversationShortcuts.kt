package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val GROUP_ID_LOG_PREFIX_LENGTH = 8

internal fun preferredConversationShortcutTitle(
    candidate: String,
    existing: String?,
): String {
    val existingTitle = existing?.takeIf { it.isNotBlank() }
    return if (
        IdentityFormatter.isNostrIdentityFallback(candidate) &&
        existingTitle != null &&
        !IdentityFormatter.isNostrIdentityFallback(existingTitle)
    ) {
        existingTitle
    } else {
        candidate
    }
}

/** Owns conversation shortcut publication, cached platform snapshots and bounded eviction. */
internal class NotificationConversationShortcuts(
    private val context: Context,
    private val shortcutPublisher: (ShortcutInfoCompat) -> Unit,
    private val dynamicShortcutIds: () -> Set<String>,
    private val activeNotificationsProvider: (NotificationManager) -> Array<StatusBarNotification>,
    private val currentContactAvatar: (String, String) -> Pair<Boolean, Bitmap?>,
    private val interactions: NotificationInteractionFactory,
) {
    private val shortcutSnapshots = ConcurrentHashMap<String, ConversationShortcutSnapshot>()
    private val shortcutLastUsed = ConcurrentHashMap<String, Long>()
    private val shortcutAccessClock = AtomicLong()

    fun hideConversationShortcutsFromDirectShare() {
        hideConversationShortcutsFromDirectShare(context)
        shortcutSnapshots.clear()
        InFlightConversationShortcuts.forgetAllPublished()
        shortcutLastUsed.clear()
    }

    /** Clears platform/cache presentation while the caller holds an acknowledged credential-revocation fence. */
    fun clearRevokedConversationShortcutsForAccount(
        accountRef: String,
        includeUnscopedLegacy: Boolean,
    ) {
        runCatching { clearRevokedConversationShortcutsForAccount(context, accountRef, includeUnscopedLegacy) }
        val accountScope = conversationShortcutAccountScope(accountRef) ?: return
        shortcutSnapshots
            .filterValues { it.accountScope == accountScope }
            .keys
            .forEach { shortcutId ->
                forgetShortcutSnapshot(shortcutId)
                shortcutLastUsed.remove(shortcutId)
            }
    }

    /**
     * Forgets the published-shortcut snapshot of one locally removed conversation (abandon, reset or local delete).
     * A new conversation under the same group id then publishes its identity shortcut again before its first card,
     * instead of only reporting use of a shortcut that no longer exists. It takes the shared publication lock, so the
     * caller runs it off the main thread, inside the same lock section as the platform removal.
     */
    internal fun forgetConversationShortcut(
        accountRef: String,
        groupIdHex: String,
    ) {
        val shortcutId = conversationShortcutId(accountRef, groupIdHex) ?: return
        synchronized(UserEventNotificationGroup.mutationLock) {
            forgetShortcutSnapshot(shortcutId)
            shortcutLastUsed.remove(shortcutId)
        }
    }

    /** A direct chat's current private contact choice. A group shows no member's picture, so it reads none. */
    private fun directContactAvatar(update: NotificationUpdateFfi): Pair<Boolean, Bitmap?> =
        if (update.isDm) currentContactAvatar(update.accountRef, update.sender.accountIdHex) else false to null

    /**
     * Cached private pixels replace a direct chat's protocol-sourced bitmaps; when none are cached, the public ones
     * stay. A group keeps only its own picture and drops its member's.
     */
    private fun ownedShortcutAvatars(
        update: NotificationUpdateFfi,
        conversationAvatarBitmap: Bitmap?,
        senderAvatarBitmap: Bitmap?,
        sender: Person,
    ): Triple<Bitmap?, Bitmap?, Person> {
        if (!update.isDm) return Triple(conversationAvatarBitmap, null, sender)
        val (override, currentAvatar) = currentContactAvatar(update.accountRef, update.sender.accountIdHex)
        return if (!override) {
            Triple(conversationAvatarBitmap, senderAvatarBitmap, sender)
        } else {
            val senderAvatar = currentAvatar ?: senderAvatarBitmap
            Triple(currentAvatar ?: conversationAvatarBitmap, senderAvatar, contactAvatarPerson(sender, senderAvatar))
        }
    }

    /**
     * Revalidates account-private icon overrides at the serialized shortcut write while retaining route and
     * preview ownership. An unchanged snapshot only reports the shortcut used. A changed one, such as a picture
     * that arrived after the post, is republished and reported used once more. A cached snapshot counts as published
     * only while the platform still lists the shortcut, so a dropped push or a shortcut another publisher removed is
     * published again instead of leaving every later card naming a missing shortcut. With [holdForPost] the
     * published shortcut is held in [InFlightConversationShortcuts], under the same lock as the write, for the
     * caller's coming card write. Returns whether it was held.
     */
    @Suppress("LongMethod") // One serialized platform write retains its alert, routing and privacy snapshot.
    fun publishConversationShortcut(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        shortcutId: String,
        locusId: LocusIdCompat,
        conversationAvatarUrl: String?,
        conversationAvatarBitmap: android.graphics.Bitmap?,
        senderAvatarUrl: String?,
        senderAvatarBitmap: android.graphics.Bitmap?,
        sender: Person,
        directShareEligible: Boolean,
        isPublishAllowed: () -> Boolean,
        holdForPost: Boolean = false,
    ): Boolean =
        runCatching {
            val (ownedConversationAvatar, ownedSenderAvatar, ownedSender) =
                ownedShortcutAvatars(update, conversationAvatarBitmap, senderAvatarBitmap, sender)
            val candidateTitle = content.conversationTitle ?: content.title
            val existingTitle =
                shortcutSnapshots[shortcutId]?.longLabel
                    ?: ShortcutManagerCompat
                        .getDynamicShortcuts(context)
                        .firstOrNull { it.id == shortcutId }
                        ?.longLabel
                        ?.toString()
            val title = preferredConversationShortcutTitle(candidateTitle, existingTitle)
            val accountScope = conversationShortcutAccountScope(update.accountRef) ?: return false
            val snapshot =
                ConversationShortcutSnapshot(
                    shortcutId = shortcutId,
                    accountScope = accountScope,
                    shortLabel = title.take(24).ifBlank { context.getString(R.string.app_name) },
                    longLabel = title,
                    notificationTag = content.notificationTag,
                    senderName = content.senderName,
                    senderKey = content.senderKey,
                    avatarUrl = conversationAvatarUrl,
                    avatarGenerationId = ownedConversationAvatar?.generationId,
                    senderAvatarUrl = senderAvatarUrl.takeIf { update.isDm },
                    senderAvatarGenerationId = ownedSenderAvatar?.generationId,
                    directShareEligible = directShareEligible,
                    previewRevision = NotificationPreviewPreferences.capture(context).revision,
                )
            shortcutLastUsed[shortcutId] = shortcutAccessClock.incrementAndGet()
            if (shortcutSnapshots[shortcutId] == snapshot) {
                reuseCachedShortcut(shortcutId, snapshot, isPublishAllowed, holdForPost)?.let { return it }
            }
            val intent = interactions.conversationShortcutOpenIntent(update, content)
            val build = { shareEligible: Boolean ->
                conversationShortcutInfo(
                    shortcutId = shortcutId,
                    snapshot = snapshot,
                    intent = intent,
                    locusId = locusId,
                    sender = notificationShortcutSender(content, ownedSender),
                    conversationAvatarBitmap = ownedConversationAvatar,
                    directShareEligible = shareEligible,
                )
            }
            val shortcut = build(directShareEligible)
            // A refresh that retains this shortcut for its card keeps a twin that is never a share target (#3322).
            val retainable = if (directShareEligible) build(false) else shortcut
            synchronized(UserEventNotificationGroup.mutationLock) {
                // A cap filled by protected shortcuts skips the publication, and the card keeps the existing fallback.
                if (isPublishAllowed() && pruneConversationShortcutsBeforePublish(shortcutId)) {
                    val (currentChoice, pixels) = directContactAvatar(update)
                    val preview = NotificationPreviewPreferences.capture(context)
                    val finish = { built: ShortcutInfoCompat ->
                        built.extras?.let { stampShortcutPreview(preview, it) }
                        stampContactPictureShortcut(built, update.sender.accountIdHex, update.isDm)
                        if (!shortcutPreviewAllowed(context, built)) {
                            genericNotificationShortcut(context, built)
                        } else if (currentChoice) {
                            withContactPictureIcon(context, built, update.sender.accountIdHex, pixels)
                        } else {
                            built
                        }
                    }
                    val currentShortcut = finish(shortcut)
                    val retainedShortcut = if (retainable === shortcut) currentShortcut else finish(retainable)
                    shortcutPublisher(currentShortcut)
                    shortcutSnapshots[shortcutId] = snapshot
                    InFlightConversationShortcuts.consumeIdentityLost(shortcutId)
                    ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
                    InFlightConversationShortcuts.rememberPublished(shortcutId, currentShortcut, retainedShortcut)
                    if (holdForPost) InFlightConversationShortcuts.hold(shortcutId)
                    holdForPost
                } else {
                    false
                }
            }
        }.onFailure {
            notificationDebug {
                "conversation shortcut skipped group=${update.groupIdHex.take(GROUP_ID_LOG_PREFIX_LENGTH)}"
            }
        }.getOrDefault(false)

    /**
     * Reuses an unchanged cached publication. Re-read under the lock: a local removal may have forgotten the snapshot
     * after the caller's check, or the platform may have lost the shortcut, and then it must be published again rather
     * than only reported used. Returns whether the reused shortcut was held, or null when it must be republished.
     */
    private fun reuseCachedShortcut(
        shortcutId: String,
        snapshot: ConversationShortcutSnapshot,
        isPublishAllowed: () -> Boolean,
        holdForPost: Boolean,
    ): Boolean? =
        synchronized(UserEventNotificationGroup.mutationLock) {
            // A Direct Share refresh that rebuilt the shortcut without its picture leaves the snapshot stale too.
            val published =
                shortcutSnapshots[shortcutId] == snapshot &&
                    shortcutId in dynamicShortcutIds() &&
                    !InFlightConversationShortcuts.consumeIdentityLost(shortcutId)
            if (!published) {
                if (shortcutSnapshots.remove(shortcutId, snapshot)) {
                    InFlightConversationShortcuts.forgetPublished(shortcutId)
                }
                null
            } else if (isPublishAllowed()) {
                ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
                if (holdForPost) InFlightConversationShortcuts.hold(shortcutId)
                holdForPost
            } else {
                false
            }
        }

    /** Forgets one shortcut's publication snapshot together with the rich copy kept for holds of it. */
    fun forgetShortcutSnapshot(shortcutId: String) {
        shortcutSnapshots.remove(shortcutId)
        InFlightConversationShortcuts.forgetPublished(shortcutId)
    }

    private fun conversationShortcutInfo(
        shortcutId: String,
        snapshot: ConversationShortcutSnapshot,
        intent: Intent,
        locusId: LocusIdCompat,
        sender: Person,
        conversationAvatarBitmap: android.graphics.Bitmap?,
        directShareEligible: Boolean,
    ): ShortcutInfoCompat {
        val builder =
            ShortcutInfoCompat
                .Builder(context, shortcutId)
                .setShortLabel(snapshot.shortLabel)
                .setLongLabel(snapshot.longLabel)
                .setIcon(
                    notificationConversationIcon(
                        title = snapshot.longLabel,
                        seed = snapshot.shortcutId,
                        avatarBitmap = conversationAvatarBitmap,
                    ),
                ).setIntent(intent)
                .setLocusId(locusId)
                .setPerson(sender)
                .setLongLived(true)
                .setExtras(conversationShortcutAccountScopeExtras(snapshot.accountScope))
        if (directShareEligible) {
            builder.setCategories(setOf(CONVERSATION_SHARE_TARGET_CATEGORY))
        }
        return builder.build()
    }

    /**
     * Makes room for [shortcutId] under the platform's per-activity cap, which counts every dynamic shortcut and every
     * manifest shortcut, by removing the least recently used app-owned conversation shortcuts. Shortcuts backing a
     * live card or held for a pending card write are never removed. Returns false when they leave no room, so the
     * caller skips the publication instead of letting the platform evict one of them.
     */
    private fun pruneConversationShortcutsBeforePublish(shortcutId: String): Boolean {
        val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        if (maxShortcuts <= 0) return true
        val manifestCount =
            runCatching {
                ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).size
            }.getOrDefault(0)
        val protectedIds = liveCardShortcutIds() + InFlightConversationShortcuts.ids()
        val idsToRemove =
            conversationShortcutPrunePlan(
                dynamicShortcutIds(),
                manifestCount,
                maxShortcuts,
                shortcutId,
                protectedIds,
                shortcutLastUsed,
            )
        if (!idsToRemove.isNullOrEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, idsToRemove)
            idsToRemove.forEach {
                forgetShortcutSnapshot(it)
                shortcutLastUsed.remove(it)
            }
        }
        return idsToRemove != null
    }

    /** Conversation shortcut ids that this app's live cards name, read through the injected notification source. */
    private fun liveCardShortcutIds(): Set<String> =
        runCatching {
            context
                .getSystemService(NotificationManager::class.java)
                ?.let(activeNotificationsProvider)
                .orEmpty()
                .mapNotNullTo(mutableSetOf()) { it.notification.shortcutId?.takeIf(::isConversationShortcutId) }
        }.getOrDefault(emptySet())
}

/** Pixel generations invalidate image-only changes without retaining another copy of bitmap memory. */
private data class ConversationShortcutSnapshot(
    val shortcutId: String,
    val accountScope: String,
    val shortLabel: String,
    val longLabel: String,
    val notificationTag: String,
    val senderName: String,
    val senderKey: String,
    val avatarUrl: String?,
    val avatarGenerationId: Int?,
    val senderAvatarUrl: String?,
    val senderAvatarGenerationId: Int?,
    val directShareEligible: Boolean,
    val previewRevision: Long,
)
