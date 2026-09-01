package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import java.security.MessageDigest

private const val CONVERSATION_SHORTCUT_LABEL_MAX_LENGTH = 24

internal fun notificationChannelSettingsIntent(
    context: Context,
    channelId: String,
): Intent =
    if (channelId.isNotBlank()) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        appNotificationSettingsIntent(context)
    }

/** Opens one global channel and reports whether Android required a broader fallback. */
internal fun openNotificationChannelSettings(
    context: Context,
    channel: NotificationChannelSpec,
    trace: ConversationNotificationSettingsTrace = defaultConversationNotificationSettingsTrace,
): ConversationNotificationSettingsLaunchAttempt {
    val clickTrace = trace.clickReceived()
    return launchNotificationSettingsIntent(
        context = context,
        preferred = notificationChannelSettingsIntent(context, channel.id),
        clickTrace = clickTrace,
        trace = trace,
    )
}

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

internal fun conversationSettingsShortcut(
    context: Context,
    shortcutId: String,
    accountRef: String,
    groupIdHex: String,
    title: String,
    avatarUrl: String?,
    existing: ShortcutInfoCompat? = null,
): ShortcutInfoCompat {
    val requestedTitle = title.trim().ifBlank { context.getString(R.string.app_name) }
    val displayTitle = preferredConversationShortcutTitle(requestedTitle, existing?.longLabel?.toString())
    val avatarBitmap = AvatarImageLoader.peekBitmap(avatarUrl)
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
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            NotificationNavigation.applyToIntent(
                intent = this,
                target =
                    NotificationTarget(
                        accountRef = accountRef,
                        groupIdHex = groupIdHex,
                        messageIdHex = null,
                        kind = NotificationTargetKind.MESSAGE,
                    ),
                notificationKey = shortcutId,
                tapToken = NotificationTapTokens.create(context).tokenFor(shortcutId),
            )
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
        .setExtras(checkNotNull(conversationShortcutAccountExtras(accountRef)))
        .build()
}

internal fun sha256Hex(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
