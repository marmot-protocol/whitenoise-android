package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ipf.whitenoise.android.ForegroundConversationDismissalCoordinator
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SignInAlertNotifications
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.signInAlerts

/**
 * Android notifications for unknown sign-ins and the compromise warning, on the account security channel. The text
 * names neither the account nor the sign-in's self-reported app, which whoever holds the key writes; on a locked
 * screen only a generic public version shows. A tap opens the account's Devices screen.
 */
internal class SignInAlertNotifier(
    context: Context,
) : SignInAlertNotifications {
    private val context = context.applicationContext
    private val tapTokens = NotificationTapTokens.create(context)

    @SuppressLint("MissingPermission")
    override fun postReview(
        accountRef: String,
        count: Int,
        alert: Boolean,
    ): Boolean {
        if (!notificationPermissionGranted(context)) return false
        val title = context.resources.getQuantityString(R.plurals.sign_in_alert_review_title, count, count)
        val body = context.getString(R.string.sign_in_alert_review_body)
        return post(
            tag = REVIEW_TAG,
            accountRef = accountRef,
            notification =
                builder(accountRef, REVIEW_ID, NotificationChannelSpec.SIGN_IN_REVIEW)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setOnlyAlertOnce(!alert)
                    .setPublicVersion(
                        publicVersion(R.string.sign_in_alert_review_public, NotificationChannelSpec.SIGN_IN_REVIEW),
                    ).build(),
        )
    }

    override fun withdrawReview(accountRef: String) {
        tapTokens.remove(SignInAlertNavigation.tapKey(accountRef, REVIEW_ID))
        NotificationManagerCompat.from(context).cancel(tag(REVIEW_TAG, accountRef), REVIEW_ID)
    }

    @SuppressLint("MissingPermission")
    override fun postCompromise(accountRef: String): Boolean {
        if (!notificationPermissionGranted(context)) return false
        val body = context.getString(R.string.sign_in_alert_compromise_body)
        val channel = NotificationChannelSpec.ACCOUNT_SECURITY
        return post(
            tag = COMPROMISE_TAG,
            accountRef = accountRef,
            notification =
                builder(accountRef, COMPROMISE_ID, channel)
                    .setContentTitle(context.getString(R.string.sign_in_alert_compromise_public))
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setPublicVersion(publicVersion(R.string.sign_in_alert_compromise_public, channel))
                    .build(),
        )
    }

    override fun withdrawCompromise(accountRef: String) {
        tapTokens.remove(SignInAlertNavigation.tapKey(accountRef, COMPROMISE_ID))
        NotificationManagerCompat.from(context).cancel(tag(COMPROMISE_TAG, accountRef), COMPROMISE_ID)
    }

    @SuppressLint("MissingPermission")
    private fun post(
        tag: String,
        accountRef: String,
        notification: Notification,
    ): Boolean =
        runCatching {
            NotificationChannels.ensureChannels(context)
            NotificationManagerCompat.from(context).notify(tag(tag, accountRef), idFor(tag), notification)
        }.isSuccess

    private fun builder(
        accountRef: String,
        id: Int,
        channel: NotificationChannelSpec,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentIntent(SignInAlertNavigation.pendingIntent(context, accountRef, id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    /** The locked-screen version: the app's name and one generic line, nothing about the account or the sign-in. */
    private fun publicVersion(
        text: Int,
        channel: NotificationChannelSpec,
    ): Notification =
        NotificationCompat
            .Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun idFor(tag: String) = if (tag == COMPROMISE_TAG) COMPROMISE_ID else REVIEW_ID

    internal companion object {
        const val REVIEW_TAG = "sign-in-review"
        const val COMPROMISE_TAG = "sign-in-compromise"

        // Stable ids beside the app-update notice's 410; the tag carries the account.
        const val REVIEW_ID = 411
        const val COMPROMISE_ID = 412

        /** The tag for one account's notification of one kind. */
        fun tag(
            kind: String,
            accountRef: String,
        ) = "$kind|$accountRef"
    }
}

/** The tap on a sign-in notification: which account's Devices screen to open. */
internal object SignInAlertNavigation {
    const val ACTION_OPEN = "dev.ipf.whitenoise.android.action.OPEN_SIGN_IN_ALERT"
    private const val URI_SCHEME = "whitenoise-sign-in"
    private const val URI_HOST = "devices"
    private const val EXTRA_ID = "dev.ipf.whitenoise.android.extra.SIGN_IN_ALERT_ID"
    private const val EXTRA_ACCOUNT_REF = "dev.ipf.whitenoise.android.extra.SIGN_IN_ALERT_ACCOUNT"

    /** Lets an accepted security tap replace any pending conversation navigation. */
    fun open(
        accountRef: String,
        appState: WhiteNoiseAppState,
        pinTapGate: PinnedShortcutTapGate,
        inbound: NotificationInboundState,
        foreground: ForegroundConversationDismissalCoordinator,
    ) {
        pinTapGate.supersede()
        inbound.target = null
        appState.conversationDictation.clearMicrophoneRecovery()
        foreground.onNotificationRouteObserved()
        appState.signInAlerts.requestDevices(accountRef)
    }

    /** The intent that opens [accountRef]'s Devices screen. The data URI only keeps accounts' intents apart. */
    fun intent(
        context: Context,
        accountRef: String,
        id: Int,
    ): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_OPEN
            val token = NotificationTapTokens.create(context).tokenFor(tapKey(accountRef, id))
            data =
                Uri
                    .Builder()
                    .scheme(URI_SCHEME)
                    .authority(URI_HOST)
                    .appendPath(token)
                    .build()
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_ACCOUNT_REF, accountRef)
        }

    /** The account a tapped sign-in notification is for, or null when [intent] is not such a tap. */
    fun accountRef(
        intent: Intent?,
        tokens: NotificationTapTokens,
    ): String? {
        val data =
            intent
                ?.takeIf { it.action == ACTION_OPEN }
                ?.data
                ?.takeIf { it.scheme == URI_SCHEME && it.host == URI_HOST }
                ?: return null
        val account = intent.getStringExtra(EXTRA_ACCOUNT_REF)?.takeIf(String::isNotBlank)
        val id = intent.getIntExtra(EXTRA_ID, -1)
        return account?.takeIf {
            (id == SignInAlertNotifier.REVIEW_ID || id == SignInAlertNotifier.COMPROMISE_ID) &&
                tokens.isValid(tapKey(it, id), data.pathSegments.singleOrNull())
        }
    }

    fun tapKey(
        accountRef: String,
        id: Int,
    ): String = "sign-in-alert|$id|$accountRef"

    fun pendingIntent(
        context: Context,
        accountRef: String,
        id: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            "$id|$accountRef".hashCode(),
            intent(context, accountRef, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
