package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import dev.ipf.whitenoise.android.R
import java.security.MessageDigest

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
    channelId: String,
) {
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
