package dev.ipf.whitenoise.android.audio

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Button
import android.widget.FrameLayout
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/** Shared deterministic foreground seams; each case restores them after execution. */
internal abstract class ConversationDictationForegroundTestCase {
    protected class RejectingForegroundStartContext(
        base: Context,
    ) : ContextWrapper(base) {
        /** Simulates Android rejecting a foreground-service launch before service creation. */
        override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("blocked")
    }

    /** Restores process-wide service seams so each Robolectric case starts isolated. */
    @After
    fun restoreResolver() {
        ConversationDictationForegroundService.hostResolver = defaultResolver
        ConversationDictationForegroundService.foregroundPromoter = defaultForegroundPromoter
    }

    protected fun assertCorrelatedServiceTrace() {
        val trace = ShadowLog.getLogsForTag("WNDictation").mapNotNull { DictationDiagnosticSchema.fields(it.msg) }
        assertTrue(trace.any { it["event"] == "foreground_service_on_start" && it["callback_session"] == 1L })
        assertTrue(trace.any { it["event"] == "foreground_service_promoted" && it["callback_session"] == 1L })
    }

    protected fun assertExplicitNotificationDestinations(
        service: NotificationStreamForegroundService,
        notification: Notification,
    ) {
        assertEquals(
            ComponentName(service, MainActivity::class.java),
            shadowOf(notification.contentIntent).savedIntent.component,
        )
        notificationActions.forEach { action ->
            assertEquals(
                ComponentName(service, NotificationStreamForegroundService::class.java),
                shadowOf(service.foreground.dictation.actionIntent(action, "test-token")).savedIntent.component,
            )
        }
    }

    /** Inflates the actual collapsed RemoteViews, including its enable-state actions. */
    protected fun assertCompactButtonsEnabled(
        service: NotificationStreamForegroundService,
        notification: Notification,
    ) {
        val compact = notification.contentView.apply(service, FrameLayout(service))
        assertTrue(compact.findViewById<Button>(R.id.dictation_notification_cancel).isEnabled)
        assertTrue(compact.findViewById<Button>(R.id.dictation_notification_paste).isEnabled)
        assertTrue(compact.findViewById<Button>(R.id.dictation_notification_send).isEnabled)
    }

    protected fun assertCompactCompletionButtonsDisabled(
        service: NotificationStreamForegroundService,
        notification: Notification,
    ) {
        val compact = notification.contentView.apply(service, FrameLayout(service))
        assertTrue(compact.findViewById<Button>(R.id.dictation_notification_cancel).isEnabled)
        assertFalse(compact.findViewById<Button>(R.id.dictation_notification_paste).isEnabled)
        assertFalse(compact.findViewById<Button>(R.id.dictation_notification_send).isEnabled)
    }

    protected fun actionCommand(
        service: NotificationStreamForegroundService,
        harness: DictationForegroundTestHost,
        action: String,
    ): Intent =
        shadowOf(
            service.foreground.dictation.actionIntent(
                action,
                requireNotNull(harness.conversationDictation.notificationSessionToken),
            ),
        ).savedIntent

    /** Installs a fresh process-owner harness into the service resolver seam. */
    protected fun installHost(): DictationForegroundTestHost =
        DictationForegroundTestHost().also { installed ->
            ConversationDictationForegroundService.hostResolver = { installed }
        }

    protected fun startIntent(
        service: Service,
        harness: DictationForegroundTestHost,
    ): Intent =
        Intent(service, service::class.java)
            .putExtra(
                ConversationDictationForegroundService.EXTRA_SESSION_TOKEN,
                requireNotNull(harness.conversationDictation.notificationSessionToken),
            )

    protected companion object {
        val notificationActions =
            listOf(
                ConversationDictationForegroundService.ACTION_CANCEL,
                ConversationDictationForegroundService.ACTION_PASTE,
                ConversationDictationForegroundService.ACTION_SEND,
            )
        val defaultResolver = ConversationDictationForegroundService.hostResolver
        val defaultForegroundPromoter = ConversationDictationForegroundService.foregroundPromoter
    }
}
