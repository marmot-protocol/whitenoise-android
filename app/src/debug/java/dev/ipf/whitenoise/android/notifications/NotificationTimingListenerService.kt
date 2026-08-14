package dev.ipf.whitenoise.android.notifications

import android.os.SystemClock
import android.os.Trace
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Debug-only listener used by [NotificationHapticVisualTimingDeviceTest]. */
class NotificationTimingListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationTimingDeviceEvents.listenerConnected = true
    }

    override fun onListenerDisconnected() {
        NotificationTimingDeviceEvents.listenerConnected = false
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (!NotificationTimingDeviceEvents.accepts(notification)) return
        Trace.beginSection("WN notification listener post")
        try {
            NotificationTimingDeviceEvents.record(
                NotificationTimingListenerPost(
                    tag = notification.tag.orEmpty(),
                    id = notification.id,
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                ),
            )
        } finally {
            Trace.endSection()
        }
    }
}

internal data class NotificationTimingListenerPost(
    val tag: String,
    val id: Int,
    val elapsedRealtimeNanos: Long,
)

internal object NotificationTimingDeviceEvents {
    @Volatile
    var listenerConnected: Boolean = false

    @Volatile
    private var expectedPackageName: String? = null

    @Volatile
    private var expectedTag: String? = null

    private val posts = LinkedBlockingQueue<NotificationTimingListenerPost>()

    fun arm(
        packageName: String,
        notificationTag: String,
    ) {
        posts.clear()
        expectedPackageName = packageName
        expectedTag = notificationTag
    }

    fun accepts(notification: StatusBarNotification): Boolean =
        notification.packageName == expectedPackageName &&
            notification.tag == expectedTag

    fun record(post: NotificationTimingListenerPost) {
        posts.offer(post)
    }

    @Suppress("MaxLineLength")
    fun awaitPost(timeoutMillis: Long): NotificationTimingListenerPost? = posts.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    fun clear() {
        expectedPackageName = null
        expectedTag = null
        posts.clear()
    }
}
