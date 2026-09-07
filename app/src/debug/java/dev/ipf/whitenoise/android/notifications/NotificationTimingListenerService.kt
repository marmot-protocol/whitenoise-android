package dev.ipf.whitenoise.android.notifications

import android.os.SystemClock
import android.os.Trace
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Debug-only listener used by notification timing evidence tests. */
class NotificationTimingListenerService : NotificationListenerService() {
    /** Mirrors listener availability without exposing any notification payload. */
    override fun onListenerConnected() {
        NotificationTimingDeviceEvents.listenerConnected = true
    }

    /** Clears availability as soon as SystemUI disconnects the diagnostic listener. */
    override fun onListenerDisconnected() {
        NotificationTimingDeviceEvents.listenerConnected = false
    }

    /** Records only the package/tag/id tuple armed by the current synthetic probe. */
    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (!NotificationTimingDeviceEvents.accepts(notification)) return
        Trace.beginSection("WN notification listener post")
        try {
            NotificationTimingDeviceEvents.record(
                NotificationTimingListenerPost(
                    tag = notification.tag.orEmpty(),
                    id = notification.id,
                    key = notification.key,
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                ),
            )
        } finally {
            Trace.endSection()
        }
    }

    /**
     * Records framework card removal separately from a heads-up collapse. The
     * latter normally leaves the shade card active and therefore emits no
     * notification-listener removal callback.
     */
    override fun onNotificationRemoved(
        notification: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        if (!NotificationTimingDeviceEvents.accepts(notification)) return
        Trace.beginSection("WN notification listener removal")
        try {
            NotificationTimingDeviceEvents.record(
                NotificationTimingListenerRemoval(
                    tag = notification.tag.orEmpty(),
                    id = notification.id,
                    key = notification.key,
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    reason = reason,
                ),
            )
        } finally {
            Trace.endSection()
        }
    }
}

/** Privacy-safe identity and monotonic time for one framework post callback. */
internal data class NotificationTimingListenerPost(
    val tag: String,
    val id: Int,
    val key: String,
    val elapsedRealtimeNanos: Long,
)

/** Privacy-safe identity, monotonic time, and framework reason for one card removal. */
internal data class NotificationTimingListenerRemoval(
    val tag: String,
    val id: Int,
    val key: String,
    val elapsedRealtimeNanos: Long,
    val reason: Int,
)

/** Process-local queues scoped to one explicitly armed synthetic notification. */
internal object NotificationTimingDeviceEvents {
    @Volatile
    var listenerConnected: Boolean = false

    @Volatile
    private var expectedTarget: NotificationTimingTarget? = null

    private val posts = LinkedBlockingQueue<NotificationTimingListenerPost>()
    private val removals = LinkedBlockingQueue<NotificationTimingListenerRemoval>()

    /** Clears prior events and admits only the next exact package/tag/id target. */
    fun arm(
        packageName: String,
        notificationTag: String,
        notificationId: Int,
    ) {
        expectedTarget = null
        posts.clear()
        removals.clear()
        expectedTarget = NotificationTimingTarget(packageName, notificationTag, notificationId)
    }

    /** Rejects every callback not owned by the armed synthetic target. */
    fun accepts(notification: StatusBarNotification): Boolean {
        val target = expectedTarget ?: return false
        return notification.packageName == target.packageName &&
            notification.tag == target.tag &&
            notification.id == target.id
    }

    /** Enqueues one accepted post/update callback for the device fixture. */
    fun record(post: NotificationTimingListenerPost) {
        posts.offer(post)
    }

    /** Enqueues one accepted card-removal callback for the device fixture. */
    fun record(removal: NotificationTimingListenerRemoval) {
        removals.offer(removal)
    }

    /** Awaits a framework post without interpreting it as visible pixels. */
    fun awaitPost(timeoutMillis: Long) = posts.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Awaits a shade-card removal, which is distinct from heads-up collapse. */
    fun awaitRemoval(timeoutMillis: Long) = removals.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Disarms the target and drops all process-local diagnostic events. */
    fun clear() {
        expectedTarget = null
        posts.clear()
        removals.clear()
    }

    private data class NotificationTimingTarget(
        val packageName: String,
        val tag: String,
        val id: Int,
    )
}
