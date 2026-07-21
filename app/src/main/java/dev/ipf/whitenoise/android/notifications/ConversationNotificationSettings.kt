package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import java.security.MessageDigest

private const val CONVERSATION_SHORTCUT_LABEL_MAX_LENGTH = 24

internal fun conversationShortcutId(
    accountRef: String,
    groupIdHex: String,
): String? {
    if (accountRef.isBlank() || groupIdHex.isBlank()) return null
    return CONVERSATION_SHORTCUT_PREFIX + sha256Hex("$accountRef\u0000$groupIdHex").take(32)
}

@SuppressLint("InlinedApi")
internal fun conversationNotificationSettingsIntent(
    context: Context,
    accountRef: String,
    groupIdHex: String,
    channelId: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Intent {
    val shortcutId = conversationShortcutId(accountRef, groupIdHex)
    if (sdkInt >= Build.VERSION_CODES.R && shortcutId != null && channelId.isNotBlank()) {
        // ACTION_CONVERSATION_SETTINGS is a hidden framework action that opens
        // the conversation list. The public, scoped API is channel settings with
        // both the parent channel and conversation shortcut IDs.
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            .putExtra(Settings.EXTRA_CONVERSATION_ID, shortcutId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return appNotificationSettingsIntent(context)
}

internal fun openConversationNotificationSettings(
    context: Context,
    accountRef: String,
    groupIdHex: String,
    isDm: Boolean,
    parent: NotificationChannelSpec? = null,
    conversationTitle: String? = null,
    conversationAvatarUrl: String? = null,
) {
    val shortcutId = conversationShortcutId(accountRef, groupIdHex)
    val targetParent = parent ?: ConversationNotificationChannels.primaryMessageParent(isDm)
    var channelConversationTitle = conversationTitle
    val channelId =
        if (shortcutId != null) {
            conversationTitle?.trim()?.takeIf(String::isNotEmpty)?.let { title ->
                // The settings screen may be opened before this chat has ever
                // posted a notification. Publish its identity first so Android
                // can associate the child channel with a named conversation.
                runCatching {
                    val existing =
                        ShortcutManagerCompat
                            .getDynamicShortcuts(context)
                            .firstOrNull { it.id == shortcutId }
                    val shortcut =
                        conversationSettingsShortcut(
                            context = context,
                            shortcutId = shortcutId,
                            title = title,
                            avatarUrl = conversationAvatarUrl,
                            existing = existing,
                        )
                    // If the UI briefly regressed to an npub fallback, preserve
                    // a previously resolved shortcut name in the channel too.
                    channelConversationTitle = shortcut.longLabel.toString()
                    ShortcutManagerCompat.pushDynamicShortcut(
                        context,
                        shortcut,
                    )
                }
            }
            // Create the per-conversation channels up front so the deep link
            // resolves even before any notification has posted for this
            // conversation, then target the requested typed child channel (or
            // the primary message child by default) — never a bare parent.
            ConversationNotificationChannels.ensureConversationChannels(
                context = context,
                conversationShortcutId = shortcutId,
                isDm = isDm,
                conversationTitle = channelConversationTitle,
            )
            ConversationNotificationChannels.conversationChannelId(targetParent.id, shortcutId)
        } else {
            targetParent.id
        }
    val preferred =
        conversationNotificationSettingsIntent(
            context = context,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            channelId = channelId,
        )
    if (context.tryStartActivity(preferred)) return
    if (preferred.action != Settings.ACTION_APP_NOTIFICATION_SETTINGS && context.tryStartActivity(appNotificationSettingsIntent(context))) return

    val appDetailsIntent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (context.tryStartActivity(appDetailsIntent)) return

    Toast.makeText(context, R.string.toast_notification_settings_unavailable, Toast.LENGTH_SHORT).show()
}

internal fun conversationSettingsShortcut(
    context: Context,
    shortcutId: String,
    title: String,
    avatarUrl: String?,
    existing: ShortcutInfoCompat? = null,
): ShortcutInfoCompat {
    val requestedTitle = title.trim().ifBlank { context.getString(R.string.app_name) }
    val displayTitle = preferredConversationShortcutTitle(requestedTitle, existing?.longLabel?.toString())
    val avatarBitmap = AvatarImageLoader.peekBitmap(avatarUrl)
    if (existing != null) {
        return ShortcutInfoCompat
            .Builder(existing)
            .setShortLabel(displayTitle.take(CONVERSATION_SHORTCUT_LABEL_MAX_LENGTH))
            .setLongLabel(displayTitle)
            .setLongLived(true)
            .apply {
                // Keep an existing notification shortcut's richer icon when
                // this settings tap does not have a cached avatar to improve it.
                if (avatarBitmap != null) {
                    setIcon(notificationConversationIcon(displayTitle, shortcutId, avatarBitmap))
                }
            }.build()
    }
    val icon =
        notificationConversationIcon(
            title = displayTitle,
            seed = shortcutId,
            avatarBitmap = avatarBitmap,
        )
    val person =
        Person
            .Builder()
            .setName(displayTitle)
            .setKey(shortcutId)
            .setIcon(icon)
            .build()
    val intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    return ShortcutInfoCompat
        .Builder(context, shortcutId)
        .setShortLabel(displayTitle.take(CONVERSATION_SHORTCUT_LABEL_MAX_LENGTH))
        .setLongLabel(displayTitle)
        .setIcon(icon)
        .setIntent(intent)
        .setLocusId(LocusIdCompat(shortcutId))
        .setPerson(person)
        .setLongLived(true)
        .build()
}

private fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun Context.tryStartActivity(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess

private fun sha256Hex(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
