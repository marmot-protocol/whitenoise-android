package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.graphics.Bitmap
import android.os.PersistableBundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat

private const val CONTACT_ICON_SCOPE = "dev.ipf.whitenoise.PRIVATE_CONTACT_ICON_SCOPE"
private const val CONTACT_CONVERSATION_ICON = "dev.ipf.whitenoise.PRIVATE_CONTACT_CONVERSATION_ICON"

/** Tags only platform-local picture ownership, never a file path, display handle, or picker URI. */
internal fun stampContactPictureShortcut(
    shortcut: ShortcutInfoCompat,
    contact: String,
    conversationIcon: Boolean,
    person: androidx.core.app.Person? = null,
) {
    shortcut.extras?.apply {
        putString(CONTACT_ICON_SCOPE, conversationShortcutAccountScope(contact))
        putBoolean(CONTACT_CONVERSATION_ICON, conversationIcon)
        person?.let {
            putString(CONTACT_ICON_SCOPE + ".name", it.name?.toString())
            putString(CONTACT_ICON_SCOPE + ".uri", it.uri)
            putBoolean(CONTACT_ICON_SCOPE + ".bot", it.isBot)
            putBoolean(CONTACT_ICON_SCOPE + ".important", it.isImportant)
        }
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
) = synchronized(UserEventNotificationGroup.mutationLock) {
    if (!isCurrent()) return@synchronized
    val shortcuts = platform.read()
    val accountScope = conversationShortcutAccountScope(account)
    val matches =
        shortcuts.filter {
            it.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA) == accountScope &&
                it.extras?.getString(CONTACT_ICON_SCOPE) == conversationShortcutAccountScope(contact)
        }
    if (matches.isNotEmpty()) {
        val bitmap = currentAvatar()
        platform.update(matches.map { withContactPictureIcon(context, it, contact, bitmap) })
    }
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
    } else {
        // Updates omit the icon to retain it in Android; initial publication supplies its known group icon.
        initialConversationIcon?.let(builder::setIcon)
    }
    val extras = shortcut.extras
    extras?.getString(CONTACT_ICON_SCOPE + ".name")?.let { name ->
        val person =
            androidx.core.app.Person
                .Builder()
                .setName(name)
                .setKey(contact)
                .setUri(extras.getString(CONTACT_ICON_SCOPE + ".uri"))
                .setBot(extras.getBoolean(CONTACT_ICON_SCOPE + ".bot"))
                .setImportant(extras.getBoolean(CONTACT_ICON_SCOPE + ".important"))
                .build()
        builder.setPerson(contactAvatarPerson(person, bitmap))
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
