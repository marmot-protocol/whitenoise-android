package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.graphics.Bitmap
import android.os.PersistableBundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat

private const val CONTACT_ICON_SCOPE = "dev.ipf.whitenoise.PRIVATE_CONTACT_ICON_SCOPE"
private const val CONTACT_CONVERSATION_ICON = "dev.ipf.whitenoise.PRIVATE_CONTACT_CONVERSATION_ICON"

/**
 * Tags only platform-local picture ownership, never a file path, display handle, picker URI or Person name:
 * launcher clones keep extras, so nothing a shortcut's own label does not already show may live there.
 */
internal fun stampContactPictureShortcut(
    shortcut: ShortcutInfoCompat,
    contact: String,
    conversationIcon: Boolean,
) {
    shortcut.extras?.apply {
        putString(CONTACT_ICON_SCOPE, conversationShortcutAccountScope(contact))
        putBoolean(CONTACT_CONVERSATION_ICON, conversationIcon)
    }
}

/** Replaces cached/pinned pixels after Save or Clear, including shortcuts outside the recent chat window. */
internal fun refreshContactPictureShortcuts(
    context: Context,
    account: String,
    contact: String,
    currentAvatar: () -> Bitmap?,
    isCurrent: () -> Boolean,
    platform: ContactPictureShortcutPlatform = ContactPictureShortcutPlatform(context),
    legacyConversationId: String? = null,
    legacyConversationIcon: Boolean = true,
) = synchronized(UserEventNotificationGroup.mutationLock) {
    if (!isCurrent()) return@synchronized
    val shortcuts = platform.read()
    val accountScope = conversationShortcutAccountScope(account)
    if (legacyConversationId != null) {
        val legacyId = conversationShortcutId(account, legacyConversationId)
        shortcuts
            .filter { it.id == legacyId && it.extras?.getString(CONTACT_ICON_SCOPE) == null }
            .filter { it.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA) == accountScope }
            .filter { legacyShortcutRouteMatches(context, it, account, legacyConversationId) }
            .forEach { stampContactPictureShortcut(it, contact, legacyConversationIcon) }
    }
    val matches =
        shortcuts.filter {
            it.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA) == accountScope &&
                it.extras?.getString(CONTACT_ICON_SCOPE) == conversationShortcutAccountScope(contact)
        }
    if (matches.isNotEmpty()) {
        val bitmap = currentAvatar()
        platform.update(
            matches.map {
                if (retainContactPicturePreview(context, it)) {
                    withContactPictureIcon(context, it, contact, bitmap)
                } else {
                    genericNotificationShortcut(context, it)
                }
            },
        )
    }
}

/** Matches live-card retention: a process restart preserves visibility, a privacy transition never does. */
private fun retainContactPicturePreview(
    context: Context,
    shortcut: ShortcutInfoCompat,
): Boolean {
    val token = NotificationPreviewPreferences.capture(context)
    val extras = shortcut.extras ?: return false
    val legacy =
        token.revision == 0L &&
            !extras.containsKey(NotificationPreviewPreferences.EXTRA_SESSION) &&
            !extras.containsKey(NotificationPreviewPreferences.EXTRA_REVISION) &&
            !extras.containsKey(NotificationPreviewPreferences.EXTRA_ALLOWED)
    val currentEpoch =
        extras.getBoolean(NotificationPreviewPreferences.EXTRA_ALLOWED) &&
            extras.getLong(NotificationPreviewPreferences.EXTRA_REVISION, -1L) == token.revision
    val allowed =
        token.allowed &&
            !extras.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN) &&
            (legacy || currentEpoch)
    if (allowed) stampShortcutPreview(token, extras)
    return allowed
}

/** Public builder copy retains routing, privacy, rank and conversation metadata while changing owned pixels. */
internal fun withContactPictureIcon(
    context: Context,
    shortcut: ShortcutInfoCompat,
    contact: String,
    bitmap: Bitmap?,
    initialConversationIcon: androidx.core.graphics.drawable.IconCompat? = null,
): ShortcutInfoCompat {
    if (!shortcutPreviewAllowed(context, shortcut)) return genericNotificationShortcut(context, shortcut)
    val icon = notificationConversationIcon(shortcut.longLabel?.toString().orEmpty(), shortcut.id, bitmap)
    val builder =
        ShortcutInfoCompat
            .Builder(context, shortcut.id)
            .setShortLabel(shortcut.shortLabel)
            .setIntents(shortcut.intents)
            .setRank(shortcut.rank)
            .setLongLived(true)
            .setExtras(PersistableBundle(shortcut.extras ?: PersistableBundle()))
    shortcut.longLabel?.let(builder::setLongLabel)
    shortcut.activity?.let(builder::setActivity)
    shortcut.disabledMessage?.let(builder::setDisabledMessage)
    shortcut.locusId?.let(builder::setLocusId)
    shortcut.categories?.let(builder::setCategories)
    if (shortcut.excludedFromSurfaces != 0) builder.setExcludedFromSurfaces(shortcut.excludedFromSurfaces)
    if (shortcut.extras?.getBoolean(CONTACT_CONVERSATION_ICON) == true) {
        builder.setIcon(icon)
        // A direct conversation names exactly this contact, so its Person is rebuilt from the shortcut's own
        // label; group shortcuts set no Person here and keep whatever Android already retains for them.
        val person =
            androidx.core.app.Person
                .Builder()
                .setName(shortcut.longLabel ?: shortcut.shortLabel)
                .setKey(contact)
                .build()
        builder.setPerson(contactAvatarPerson(person, bitmap))
    } else {
        // Updates omit the icon to retain it in Android; initial publication supplies its known group icon.
        initialConversationIcon?.let(builder::setIcon)
    }
    return builder.build()
}

/** Narrow platform seam keeps icon write assertions independent of Android's icon-stripping readback. */
internal open class ContactPictureShortcutPlatform(
    private val context: Context,
) {
    /** Includes cached and pinned entries even when they no longer appear in the recent list. */
    open fun read(): List<ShortcutInfoCompat> =
        ShortcutManagerCompat.getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
                ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED,
        )

    /** Android retains properties omitted from an update, including group-owned icons. */
    open fun update(shortcuts: List<ShortcutInfoCompat>) {
        ShortcutManagerCompat.updateShortcuts(context, shortcuts)
    }
}

/** Existing canonical DM lookup can prove a legacy ID, but cannot turn an unrelated intent into that identity. */
private fun legacyShortcutRouteMatches(
    context: Context,
    shortcut: ShortcutInfoCompat,
    account: String,
    group: String,
): Boolean {
    val intent = shortcut.intent
    val component = android.content.ComponentName(context, dev.ipf.whitenoise.android.MainActivity::class.java)
    if (intent.component != component) return false
    return when (intent.action) {
        android.content.Intent.ACTION_VIEW -> true
        NotificationNavigation.ACTION_OPEN -> {
            val target = NotificationNavigation.parseTarget(intent)
            target?.accountRef == account && target.groupIdHex == group
        }
        else -> false
    }
}

/** Cheap platform metadata inspection avoids native lookup when all entries already carry ownership. */
internal fun ContactPictureShortcutPlatform.hasLegacyContactShortcuts(account: String): Boolean =
    read().any {
        it.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA) == conversationShortcutAccountScope(account) &&
            it.extras?.getString(CONTACT_ICON_SCOPE) == null
    }
