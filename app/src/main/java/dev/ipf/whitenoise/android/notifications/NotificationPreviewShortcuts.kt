package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.whitenoise.android.R

/** Preserve shortcut IDs and conversation channels, replacing their display metadata in place. */
internal fun genericNotificationShortcut(
    context: Context,
    original: ShortcutInfoCompat,
): ShortcutInfoCompat {
    val name = context.getString(R.string.app_name)
    val extras =
        PersistableBundle().apply {
            original.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA)?.let {
                putString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA, it)
            }
            putBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN, true)
        }
    return ShortcutInfoCompat
        .Builder(original)
        .setShortLabel(name)
        .setLongLabel(name)
        .setDisabledMessage(name)
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
        .setPersons(arrayOf(Person.Builder().setName(name).build()))
        .setCategories(emptySet())
        // Compat inventory reconstruction omits this framework flag; all owned conversation IDs are long-lived.
        .setLongLived(true)
        .setExtras(extras)
        .setTransientExtras(Bundle())
        .build()
}

private fun notificationShortcuts(context: Context): List<ShortcutInfoCompat> =
    ShortcutManagerCompat
        .getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED or
                ShortcutManagerCompat.FLAG_MATCH_CACHED,
        ).filter { isConversationShortcutId(it.id) }

/** Called under the shared commit gate so delayed rich publishers cannot restore private labels. */
internal fun redactNotificationShortcuts(context: Context): Boolean =
    runCatching {
        val shortcuts = notificationShortcuts(context).filterNot(::shortcutPreviewHidden)
        shortcuts.isEmpty() ||
            ShortcutManagerCompat.updateShortcuts(
                context,
                shortcuts.map { genericNotificationShortcut(context, it) },
            )
    }.getOrDefault(false)

/** A generic card may keep only a shortcut whose identity metadata was successfully scrubbed. */
internal fun redactNotificationShortcut(
    context: Context,
    id: String,
): Boolean =
    runCatching {
        val original = notificationShortcuts(context).firstOrNull { it.id == id } ?: return@runCatching false
        shortcutPreviewHidden(original) ||
            ShortcutManagerCompat.updateShortcuts(
                context,
                listOf(genericNotificationShortcut(context, original)),
            )
    }.getOrDefault(false)

private fun shortcutPreviewHidden(
    shortcut: ShortcutInfoCompat,
): Boolean = shortcut.extras?.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN) == true

internal fun stampShortcutPreview(
    token: NotificationPreviewToken,
    extras: PersistableBundle,
) {
    extras.putString(NotificationPreviewPreferences.EXTRA_SESSION, token.session)
    extras.putLong(NotificationPreviewPreferences.EXTRA_REVISION, token.revision)
    extras.putBoolean(NotificationPreviewPreferences.EXTRA_ALLOWED, token.allowed)
}

internal fun shortcutPreviewAllowed(
    context: Context,
    shortcut: ShortcutInfoCompat,
): Boolean {
    val token = NotificationPreviewPreferences.capture(context)
    val extras = shortcut.extras ?: return false
    val preparedAllowed =
        extras.getBoolean(NotificationPreviewPreferences.EXTRA_ALLOWED) &&
            extras.getString(NotificationPreviewPreferences.EXTRA_SESSION) == token.session &&
            extras.getLong(NotificationPreviewPreferences.EXTRA_REVISION, -1L) == token.revision
    return token.allowed && preparedAllowed
}
