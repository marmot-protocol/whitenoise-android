package dev.ipf.whitenoise.android.share

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PersistableBundle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.notifications.CONVERSATION_SHARE_TARGET_CATEGORY
import dev.ipf.whitenoise.android.notifications.NotificationPreviewPreferences
import dev.ipf.whitenoise.android.notifications.NotificationPreviewToken
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup
import dev.ipf.whitenoise.android.notifications.conversationShortcutAccountExtras
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.conversationShortcutIsRich
import dev.ipf.whitenoise.android.notifications.genericNotificationShortcut
import dev.ipf.whitenoise.android.notifications.isConversationShortcutId
import dev.ipf.whitenoise.android.notifications.notificationConversationIcon
import dev.ipf.whitenoise.android.notifications.preferredConversationShortcutTitle
import dev.ipf.whitenoise.android.notifications.shortcutPreviewAllowed
import dev.ipf.whitenoise.android.notifications.stampShortcutPreview
import dev.ipf.whitenoise.android.state.ChatListItem

internal const val MAX_SHARE_SHORTCUTS = 8
private const val SHARE_SHORTCUT_SHORT_LABEL_MAX = 24

data class ShareShortcutTarget(
    val accountRef: String,
    val groupIdHex: String,
    val title: String,
    val avatarUrl: String? = null,
    val avatarBitmap: Bitmap? = null,
    val localContactAvatar: Boolean = false,
)

internal fun selectShareShortcutTargets(
    accountRef: String,
    chats: List<ChatListItem>,
    limit: Int = MAX_SHARE_SHORTCUTS,
    displayTitle: (ChatListItem) -> String,
): List<ShareShortcutTarget> =
    chats
        .filterNot { it.group.pendingConfirmation }
        .take(limit)
        .mapNotNull { item ->
            val groupId = item.group.groupIdHex.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ShareShortcutTarget(
                accountRef = accountRef,
                groupIdHex = groupId,
                title = displayTitle(item),
                avatarUrl =
                    ProfileSanitizer.protocolImageUrl(item.group.avatarUrl)
                        ?: ProfileSanitizer.protocolImageUrl(item.projection?.avatarUrl),
                avatarBitmap = item.firstFrameAvatar?.image?.asAndroidBitmap(),
            )
        }

/** The ranked targets to publish and the card-backed dynamic shortcuts that must stay published beside them. */
internal data class ShareShortcutPlan(
    val targets: List<ShareShortcutTarget>,
    val retained: List<ShortcutInfoCompat>,
)

/**
 * Fits the ranked [candidates] beside the [cardBacked] shortcuts under the platform's [maxShortcuts] cap.
 * Replacing the dynamic set deletes any dynamic shortcut it omits, and Android removes a deleted shortcut's
 * conversation channel together with the live cards posted into it, so a shortcut that still backs a card is
 * retained and the lowest-ranked targets give up their slots first. A card-backed shortcut that is also a
 * target is published once, as the target.
 */
internal fun planShareShortcuts(
    candidates: List<ShareShortcutTarget>,
    cardBacked: List<ShortcutInfoCompat>,
    maxShortcuts: Int,
): ShareShortcutPlan {
    val cards = cardBacked.distinctBy { it.id }.sortedBy { it.rank }
    for (count in candidates.size downTo 0) {
        val targets = candidates.take(count)
        val targetIds = targets.mapNotNull { conversationShortcutId(it.accountRef, it.groupIdHex) }.toSet()
        val retained = cards.filterNot { it.id in targetIds }
        if (targets.size + retained.size <= maxShortcuts) return ShareShortcutPlan(targets, retained)
    }
    return ShareShortcutPlan(emptyList(), cards.take(maxShortcuts.coerceAtLeast(0)))
}

/** Shortcut ids of this app's live conversation cards, read from the platform so no card state is duplicated here. */
internal fun liveCardShortcutIds(context: Context): Set<String> =
    runCatching {
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .mapNotNull { it.notification.shortcutId }
            .filter(::isConversationShortcutId)
            .toSet()
    }.getOrDefault(emptySet())

internal fun buildShareShortcutIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

/** Builds an account-routed recent-chat target with the captured preview policy and local conversation icon. */
internal fun buildShareShortcut(
    context: Context,
    target: ShareShortcutTarget,
    existingTitle: String? = null,
    rank: Int = 0,
    previewToken: NotificationPreviewToken? = null,
): ShortcutInfoCompat? {
    val shortcutId = conversationShortcutId(target.accountRef, target.groupIdHex) ?: return null
    val title = preferredConversationShortcutTitle(target.title, existingTitle)
    return ShortcutInfoCompat
        .Builder(context, shortcutId)
        .setShortLabel(title.take(SHARE_SHORTCUT_SHORT_LABEL_MAX).ifBlank { context.getString(R.string.app_name) })
        .setLongLabel(title)
        .setIcon(
            notificationConversationIcon(
                title = title,
                seed = shortcutId,
                avatarBitmap = target.avatarBitmap.takeIf { target.localContactAvatar },
            ),
        ).setIntent(buildShareShortcutIntent(context))
        .setRank(rank)
        .setLongLived(true)
        .setCategories(setOf(CONVERSATION_SHARE_TARGET_CATEGORY))
        .setExtras(shareShortcutExtras(target.accountRef, null, previewToken))
        .build()
}

/**
 * Rebuild a notification-published shortcut through the public AndroidX API.
 * AndroidX's shortcut copy constructor is library-internal, so copying it from
 * application code fails Android Lint's RestrictedApi check.
 */
private fun rebuildRichShareShortcut(
    context: Context,
    target: ShareShortcutTarget,
    existing: ShortcutInfoCompat,
    rank: Int,
    previewToken: NotificationPreviewToken,
): ShortcutInfoCompat =
    rebuildShortcut(
        context = context,
        existing = existing,
        title = preferredConversationShortcutTitle(target.title, existing.longLabel?.toString()),
        avatarBitmap = target.avatarBitmap ?: AvatarImageLoader.peekBitmap(target.avatarUrl),
        rank = rank,
        categories = existing.categories.orEmpty() + CONVERSATION_SHARE_TARGET_CATEGORY,
        extras = shareShortcutExtras(target.accountRef, existing.extras, previewToken),
    )

/**
 * Re-expresses a dynamic shortcut that still backs a live card so replacing the dynamic set cannot delete its
 * conversation. Ownership and routing carry over unchanged. A card's own shortcut is never stamped, so a label that
 * is not already hidden is stamped with [previewToken] and the privacy gate that follows keeps it only while the
 * policy still allows previews, as it does for every other entry. The share-target category is dropped because a
 * retained entry is not a recent chat of the active account.
 */
private fun rebuildCardBackedShortcut(
    context: Context,
    existing: ShortcutInfoCompat,
    rank: Int,
    previewToken: NotificationPreviewToken,
): ShortcutInfoCompat {
    val label = existing.longLabel?.toString()
    val extras = existing.extras?.let { PersistableBundle(it) }
    if (extras != null && !extras.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN)) {
        stampShortcutPreview(previewToken, extras)
    }
    return rebuildShortcut(
        context = context,
        existing = existing,
        title = if (label.isNullOrBlank()) context.getString(R.string.app_name) else label,
        avatarBitmap = null,
        rank = rank,
        categories = existing.categories.orEmpty() - CONVERSATION_SHARE_TARGET_CATEGORY,
        extras = extras,
    )
}

/** Copies the routing fields of a platform readback onto a fresh long-lived shortcut with a rebuilt icon and person. */
private fun rebuildShortcut(
    context: Context,
    existing: ShortcutInfoCompat,
    title: String,
    avatarBitmap: Bitmap?,
    rank: Int,
    categories: Set<String>,
    extras: PersistableBundle?,
): ShortcutInfoCompat {
    val icon =
        notificationConversationIcon(
            title = title,
            seed = existing.id,
            avatarBitmap = avatarBitmap,
        )
    val person =
        Person
            .Builder()
            .setName(title)
            .setKey(existing.id)
            .setIcon(icon)
            .build()
    val builder =
        ShortcutInfoCompat
            .Builder(context, existing.id)
            .setShortLabel(title.take(SHARE_SHORTCUT_SHORT_LABEL_MAX).ifBlank { context.getString(R.string.app_name) })
            .setLongLabel(title)
            .setIcon(icon)
            .setIntents(existing.intents)
            .setPerson(person)
            .setRank(rank)
            .setLongLived(true)
            .setCategories(categories)
    extras?.let(builder::setExtras)
    existing.activity?.let(builder::setActivity)
    existing.disabledMessage?.let(builder::setDisabledMessage)
    existing.locusId?.let(builder::setLocusId)
    if (existing.excludedFromSurfaces != 0) {
        builder.setExcludedFromSurfaces(existing.excludedFromSurfaces)
    }
    return builder.build()
}

private fun shareShortcutExtras(
    accountRef: String,
    original: PersistableBundle?,
    token: NotificationPreviewToken?,
): PersistableBundle {
    val extras = PersistableBundle(original ?: checkNotNull(conversationShortcutAccountExtras(accountRef)))
    if (token != null) {
        extras.remove(NotificationPreviewPreferences.EXTRA_HIDDEN)
        stampShortcutPreview(token, extras)
    }
    return extras
}

/**
 * Publishes the active account's recent chats as Direct Share targets. [dynamicShortcuts] and [cardShortcutIds]
 * identify the dynamic shortcuts that still back live cards, which every publication keeps.
 */
class ShareShortcutPublisher(
    private val context: Context,
    private val maxShortcutCount: () -> Int = {
        ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(0)
    },
    private val setDynamicShortcuts: (List<ShortcutInfoCompat>) -> Unit = { shortcuts ->
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    },
    private val avatarOverride: (String, ChatListItem) -> Pair<Boolean, Bitmap?> = { _, _ -> false to null },
    private val existingShortcuts: () -> List<ShortcutInfoCompat> = {
        ShortcutManagerCompat.getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED,
        )
    },
    private val dynamicShortcuts: () -> List<ShortcutInfoCompat> = {
        ShortcutManagerCompat.getDynamicShortcuts(context)
    },
    private val cardShortcutIds: () -> Set<String> = { liveCardShortcutIds(context) },
) {
    /**
     * Publish recent-chat Direct Share targets for the active account only.
     * Preserves rich notification shortcuts (person + locus) instead of
     * downgrading them, and applies the ranked list in one [setDynamicShortcuts]
     * call so recency is not reversed by per-item pushes.
     */
    @Suppress("LongMethod") // Selection and final privacy-gated publication form one platform operation.
    fun publish(
        accountRef: String,
        chats: List<ChatListItem>,
        displayTitle: (ChatListItem) -> String,
    ) = publish(accountRef, chats, { true }, displayTitle)

    /** Rechecks a replaceable off-main refresh under the privacy lock before changing Direct Share inventory. */
    fun publish(
        accountRef: String,
        chats: List<ChatListItem>,
        isCurrent: () -> Boolean,
        displayTitle: (ChatListItem) -> String,
    ) {
        val publicationGeneration = PinnedConversationTokens.captureRequest()
        if (accountRef.isBlank() || !isCurrent()) return
        if (!PinnedConversationTokens.isPublicationCurrent(publicationGeneration)) return
        val previewToken = NotificationPreviewPreferences.capture(context)
        val maxShortcuts = maxShortcutCount().coerceAtLeast(0)
        val chatsByGroup = chats.associateBy { it.group.groupIdHex }
        val candidates =
            selectShareShortcutTargets(accountRef, chats, MAX_SHARE_SHORTCUTS, displayTitle).map { target ->
                val item = chatsByGroup[target.groupIdHex] ?: return@map target
                val (changed, bitmap) = avatarOverride(accountRef, item)
                if (changed) target.copy(avatarBitmap = bitmap, avatarUrl = null, localContactAvatar = true) else target
            }
        val existingById = existingShortcuts().associateBy { it.id }
        synchronized(UserEventNotificationGroup.mutationLock) {
            if (!isCurrent()) return@synchronized
            if (!PinnedConversationTokens.isPublicationCurrent(publicationGeneration)) return@synchronized
            // Card posts hold this lock while they publish a shortcut, so the live-card read cannot miss one.
            val plan = planShareShortcuts(candidates, cardBackedShortcuts(), maxShortcuts)
            val targetShortcuts = targetShortcuts(accountRef, plan.targets, existingById, previewToken)
            val retainedShortcuts =
                plan.retained.mapIndexed { index, shortcut ->
                    rebuildCardBackedShortcut(context, shortcut, targetShortcuts.size + index, previewToken)
                }
            setDynamicShortcuts(currentPublicationShortcuts(accountRef, chats, targetShortcuts + retainedShortcuts))
        }
    }

    /** Dynamic conversation shortcuts that still back a live card, which replacing the dynamic set must not delete. */
    private fun cardBackedShortcuts(): List<ShortcutInfoCompat> {
        val cards = cardShortcutIds()
        if (cards.isEmpty()) return emptyList()
        return dynamicShortcuts().filter { it.id in cards && isConversationShortcutId(it.id) }
    }

    /** Builds the ranked recent-chat shortcuts, upgrading rich notification shortcuts instead of downgrading them. */
    private fun targetShortcuts(
        accountRef: String,
        targets: List<ShareShortcutTarget>,
        existingById: Map<String, ShortcutInfoCompat>,
        previewToken: NotificationPreviewToken,
    ): List<ShortcutInfoCompat> =
        targets.mapIndexedNotNull { rank, target ->
            val shortcutId =
                conversationShortcutId(accountRef, target.groupIdHex)
                    ?: return@mapIndexedNotNull null
            val existing = existingById[shortcutId]
            if (existing != null && conversationShortcutIsRich(existing)) {
                rebuildRichShareShortcut(context, target, existing, rank, previewToken)
            } else {
                buildShareShortcut(
                    context,
                    target,
                    existing?.longLabel?.toString(),
                    rank,
                    previewToken,
                )
            }
        }

    /** Resolves final privacy and private-contact pixels only inside the caller's publication lock. */
    private fun currentPublicationShortcuts(
        accountRef: String,
        chats: List<ChatListItem>,
        shortcuts: List<ShortcutInfoCompat>,
    ): List<ShortcutInfoCompat> =
        shortcuts.map { shortcut ->
            if (shortcutPreviewAllowed(context, shortcut)) {
                val item =
                    chats.firstOrNull {
                        conversationShortcutId(accountRef, it.group.groupIdHex) == shortcut.id
                    }
                val peer =
                    item?.let {
                        dev.ipf.whitenoise.android.core.GroupProjector.avatarAccount(
                            it.group,
                            it.presentationOtherMemberAccount,
                            it.presentationMemberCount,
                        )
                    }
                val current = item?.let { avatarOverride(accountRef, it) }
                if (peer != null) stampPeerShortcut(shortcut, peer, item)
                if (peer != null && current?.first == true) {
                    dev.ipf.whitenoise.android.notifications
                        .withContactPictureIcon(context, shortcut, peer, current.second)
                } else {
                    shortcut
                }
            } else {
                genericNotificationShortcut(context, shortcut)
            }
        }

    /** Preserve contact ownership before the first override, without borrowing group-owned icons. */
    private fun stampPeerShortcut(
        shortcut: ShortcutInfoCompat,
        peer: String,
        item: ChatListItem,
    ) {
        val ownsConversationIcon =
            !dev.ipf.whitenoise.android.core.GroupProjector
                .ownsGroupPicture(item)
        dev.ipf.whitenoise.android.notifications
            .stampContactPictureShortcut(shortcut, peer, ownsConversationIcon)
    }
}
