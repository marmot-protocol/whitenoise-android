package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import dev.ipf.whitenoise.android.R

/** Builds the exact prepared conversation deep link without touching shortcuts or channels. */
internal fun preparedConversationNotificationSettingsIntent(
    context: Context,
    target: PreparedConversationNotificationSettingsTarget,
): Intent =
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, target.channelId)
        .putExtra(Settings.EXTRA_CONVERSATION_ID, target.conversationShortcutId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Launches an already-prepared target; this path performs no shortcut or channel work. */
internal fun openPreparedConversationNotificationSettings(
    context: Context,
    target: PreparedConversationNotificationSettingsTarget,
    trace: ConversationNotificationSettingsTrace = defaultConversationNotificationSettingsTrace,
): ConversationNotificationSettingsLaunchAttempt {
    val clickTrace = trace.clickReceived(target.operationId)
    return launchNotificationSettingsIntent(
        context = context,
        preferred = preparedConversationNotificationSettingsIntent(context, target),
        clickTrace = clickTrace,
        trace = trace,
    )
}

/** Uses the existing app-settings/details fallback chain after preparation fails. */
internal fun openConversationNotificationSettingsFallback(
    context: Context,
    operationId: Long,
    trace: ConversationNotificationSettingsTrace = defaultConversationNotificationSettingsTrace,
): ConversationNotificationSettingsLaunchAttempt {
    val clickTrace = trace.clickReceived(operationId)
    return launchNotificationSettingsIntent(
        context = context,
        preferred = appNotificationSettingsIntent(context),
        clickTrace = clickTrace,
        trace = trace,
    )
}

/** Dispatches one preferred intent followed by the established bounded fallbacks. */
internal fun launchNotificationSettingsIntent(
    context: Context,
    preferred: Intent,
    clickTrace: ConversationNotificationSettingsClickTrace,
    trace: ConversationNotificationSettingsTrace,
): ConversationNotificationSettingsLaunchAttempt {
    trace.startActivityCalled(clickTrace)
    val callStartedAt = trace.now()
    val openedPreferred = context.tryStartActivity(preferred)
    trace.startActivityReturned(clickTrace, callStartedAt, openedPreferred)
    val opened =
        openedPreferred ||
            (
                preferred.action != Settings.ACTION_APP_NOTIFICATION_SETTINGS &&
                    context.tryStartActivity(appNotificationSettingsIntent(context))
            ) ||
            context.tryStartActivity(appDetailsSettingsIntent(context))
    if (!opened) {
        Toast.makeText(context, R.string.toast_notification_settings_unavailable, Toast.LENGTH_SHORT).show()
    }
    return ConversationNotificationSettingsLaunchAttempt(
        opened = opened,
        usedFallback = !openedPreferred && opened,
        clickTrace = clickTrace,
    )
}

/** Opens the app-level notification surface when a scoped channel cannot be resolved. */
internal fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Last-resort application-details intent for OEMs without notification settings handlers. */
private fun appDetailsSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Returns whether Android accepted an intent without leaking handler details into logs. */
private fun Context.tryStartActivity(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess
