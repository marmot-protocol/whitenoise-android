package dev.ipf.whitenoise.android.notifications

import android.app.Notification
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
        NotificationTimingDeviceEvents.recordPost(notification)
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
        NotificationTimingDeviceEvents.recordRemoval(notification, reason)
    }
}

/** Privacy-safe identity and monotonic time for one framework post callback. */
internal data class NotificationTimingListenerPost(
    val tag: String,
    val id: Int,
    val key: String,
    val elapsedRealtimeNanos: Long,
    val contentText: String?,
    val contentRevision: NotificationTimingContentRevision,
    val onlyAlertOnce: Boolean,
)

/** Classification of an explicitly armed synthetic notification body; no real content is reported. */
internal enum class NotificationTimingContentRevision {
    Fallback,
    Resolved,
    Other,
}

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

    private var expectedTarget: NotificationTimingTarget? = null

    private val posts = LinkedBlockingQueue<NotificationTimingListenerPost>()
    private val removals = LinkedBlockingQueue<NotificationTimingListenerRemoval>()

    /** Clears prior events and admits only the next exact package/tag/id target. */
    @Synchronized
    fun arm(
        packageName: String,
        notificationTag: String,
        notificationId: Int,
        fallbackContent: String? = null,
        resolvedContent: String? = null,
    ) {
        expectedTarget = null
        posts.clear()
        removals.clear()
        expectedTarget =
            NotificationTimingTarget(packageName, notificationTag, notificationId, fallbackContent, resolvedContent)
    }

    /** Rejects every callback not owned by the armed synthetic target. */
    private fun accepts(notification: StatusBarNotification): Boolean {
        val target = expectedTarget ?: return false
        return notification.packageName == target.packageName &&
            notification.tag == target.tag &&
            notification.id == target.id
    }

    /** Matches and records under the target lock so rearming cannot retain an old callback. */
    @Synchronized
    fun recordPost(notification: StatusBarNotification) {
        if (!accepts(notification)) return
        Trace.beginSection("WN notification listener post")
        try {
            val content =
                notification.notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT)
                    ?.toString()
            val revision = contentRevision(content)
            posts.offer(
                NotificationTimingListenerPost(
                    tag = notification.tag.orEmpty(),
                    id = notification.id,
                    key = notification.key,
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    contentText = content.takeIf { revision != NotificationTimingContentRevision.Other },
                    contentRevision = revision,
                    onlyAlertOnce = notification.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
                ),
            )
        } finally {
            Trace.endSection()
        }
    }

    /** Serializes removal capture with arm/clear, including event materialization and enqueueing. */
    @Synchronized
    fun recordRemoval(
        notification: StatusBarNotification,
        reason: Int,
    ) {
        if (!accepts(notification)) return
        Trace.beginSection("WN notification listener removal")
        try {
            removals.offer(
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

    /** Called under the capture monitor and matches only explicitly supplied fixture bodies. */
    private fun contentRevision(content: String?): NotificationTimingContentRevision {
        val target = expectedTarget ?: return NotificationTimingContentRevision.Other
        return when {
            target.fallbackContent != null && content == target.fallbackContent ->
                NotificationTimingContentRevision.Fallback
            target.resolvedContent != null && content == target.resolvedContent ->
                NotificationTimingContentRevision.Resolved
            else -> NotificationTimingContentRevision.Other
        }
    }

    /** Awaits a framework post without interpreting it as visible pixels. */
    fun awaitPost(timeoutMillis: Long) = posts.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Awaits a shade-card removal, which is distinct from heads-up collapse. */
    fun awaitRemoval(timeoutMillis: Long) = removals.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Disarms the target and drops all process-local diagnostic events. */
    @Synchronized
    fun clear() {
        expectedTarget = null
        posts.clear()
        removals.clear()
    }

    private data class NotificationTimingTarget(
        val packageName: String,
        val tag: String,
        val id: Int,
        val fallbackContent: String?,
        val resolvedContent: String?,
    )
}
