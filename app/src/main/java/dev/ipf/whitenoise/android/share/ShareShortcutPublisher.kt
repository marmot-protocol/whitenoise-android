package dev.ipf.whitenoise.android.share

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
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup
import dev.ipf.whitenoise.android.notifications.conversationShortcutAccountExtras
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.conversationShortcutIsRich
import dev.ipf.whitenoise.android.notifications.genericNotificationShortcut
import dev.ipf.whitenoise.android.notifications.notificationConversationIcon
import dev.ipf.whitenoise.android.notifications.preferredConversationShortcutTitle
import dev.ipf.whitenoise.android.notifications.shortcutPreviewAllowed
import dev.ipf.whitenoise.android.notifications.stampShortcutPreview
import dev.ipf.whitenoise.android.state.ChatListItem
import kotlin.math.min

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

internal fun buildShareShortcutIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

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
): ShortcutInfoCompat {
    val title = preferredConversationShortcutTitle(target.title, existing.longLabel?.toString())
    val icon =
        notificationConversationIcon(
            title = title,
            seed = existing.id,
            avatarBitmap = target.avatarBitmap ?: AvatarImageLoader.peekBitmap(target.avatarUrl),
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
            .setCategories(existing.categories.orEmpty() + CONVERSATION_SHARE_TARGET_CATEGORY)
            .setExtras(
                shareShortcutExtras(target.accountRef, existing.extras, previewToken),
            )
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
    ) {
        if (accountRef.isBlank()) return
        val previewToken = NotificationPreviewPreferences.capture(context)
        val maxShortcuts = maxShortcutCount().coerceAtLeast(0)
        val limit = min(MAX_SHARE_SHORTCUTS, maxShortcuts)
        val targets =
            selectShareShortcutTargets(accountRef, chats, limit, displayTitle).map { target ->
                val item = chats.first { it.group.groupIdHex == target.groupIdHex }
                val (changed, bitmap) = avatarOverride(accountRef, item)
                if (changed) target.copy(avatarBitmap = bitmap, avatarUrl = null, localContactAvatar = true) else target
            }
        val existing = existingShortcuts()
        val existingById = existing.associateBy { it.id }
        val shortcuts =
            if (targets.isEmpty()) {
                emptyList()
            } else {
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
            }
        synchronized(UserEventNotificationGroup.mutationLock) {
            setDynamicShortcuts(
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
                        if (peer != null && current?.first == true) {
                            dev.ipf.whitenoise.android.notifications
                                .stampContactPictureShortcut(shortcut, peer, true)
                            dev.ipf.whitenoise.android.notifications
                                .withContactPictureIcon(context, shortcut, peer, current.second)
                        } else {
                            shortcut
                        }
                    } else {
                        genericNotificationShortcut(context, shortcut)
                    }
                },
            )
        }
    }
}
