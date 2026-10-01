package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue

internal class GroupFixture(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    val pacer = NotificationPostPacer(refillIntervalMillis = 1L, burstCapacity = 128)
    var now = 1_700_000_000_000L
    var childWrites = 0
    var summaryAttempts = 0
    var summaryCancelAttempts = 0
    var failRead = false
    var failChild = false
    var failSummary = false
    var dropSummaryCancels = 0
    val coordinator =
        NotificationGroupReconciler(
            context,
            scope,
            pacer,
            read = {
                if (failRead) throw IllegalStateException("tray unavailable")
                it.activeNotifications
            },
            post = { compat, tag, id, notification ->
                summaryAttempts++
                if (failSummary) throw IllegalStateException("summary unavailable")
                compat.notify(tag, id, notification)
            },
            cancel = { compat, tag, id ->
                summaryCancelAttempts++
                if (dropSummaryCancels > 0) dropSummaryCancels-- else compat.cancel(tag, id)
            },
        )
    val presenter =
        LocalNotificationPresenter(
            context,
            shortcutPublisher = {},
            nowMillis = { now },
            postPacer = pacer,
            groupReconciliation = coordinator::request,
            notificationPoster = { compat, tag, id, notification ->
                if (failChild) throw IllegalStateException("child unavailable")
                childWrites++
                compat.notify(tag, id, notification)
            },
            avatarBitmapResolver = { null },
            enrichmentLauncher = { block -> scope.launch { block() } },
        )

    suspend fun send(
        account: String,
        group: String,
        message: String,
        mention: Boolean = false,
    ): Notification {
        now += 6_000
        val update = alertBudgetUpdate(message, now, mention, account, group)
        assertTrue(presenter.show(update, shortNpub = { it }))
        val key = LocalNotificationFormatter.notificationDismissalKey(update)
        return manager.activeNotifications.single { it.tag == key.tag && it.id == key.id }.notification
    }

    fun summary(): Notification? =
        manager.activeNotifications
            .singleOrNull {
                it.tag == UserEventNotificationGroup.SUMMARY_TAG &&
                    it.id == UserEventNotificationGroup.SUMMARY_ID
            }?.notification
}
