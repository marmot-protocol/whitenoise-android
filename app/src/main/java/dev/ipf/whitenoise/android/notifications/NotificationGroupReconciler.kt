package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Coalesces platform-only reconciliation. The OS tray is the sole child inventory; a failed read is
 * unknown, never empty. Retries cannot reconstruct a dismissed card from persisted messages.
 */
@SuppressLint("MissingPermission") // Only platform summary/adoption writes; child permission/eligibility policy is unchanged.
internal class NotificationGroupReconciler(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pacer: NotificationPostPacer = NotificationPostPacer.shared,
    private val read: (NotificationManager) -> Array<StatusBarNotification> = { it.activeNotifications },
    private val post: (NotificationManagerCompat, String, Int, Notification) -> Unit = { manager, tag, id, card -> manager.notify(tag, id, card) },
    private val cancel: (NotificationManagerCompat, String, Int) -> Unit = { manager, tag, id -> manager.cancel(tag, id) },
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val revision = AtomicLong()

    private val worker =
        scope.launch {
            for (request in requests) {
                delay(SETTLE_DELAY_MS)
                reconcile()
            }
        }

    fun request() {
        revision.incrementAndGet()
        requests.trySend(Unit)
    }

    fun close() {
        requests.close()
        worker.cancel()
    }

    private suspend fun reconcile() {
        repeat(MAX_ATTEMPTS) { attempt ->
            val expected = revision.get()
            try {
                if (reconcileSnapshot(expected, allowEmpty = attempt >= EMPTY_RECHECKS) && expected == revision.get()) return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                // Delivery already succeeded or failed independently. Summary failures never replay a child.
            }
            delay(SETTLE_DELAY_MS)
        }
    }

    @Suppress("ReturnCount") // Refuse superseded/unknown snapshots before pacing or platform mutation.
    private suspend fun reconcileSnapshot(expected: Long, allowEmpty: Boolean): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val compat = NotificationManagerCompat.from(context)
        val first = read(manager)
        if (adoptLegacyCards(manager, compat, first)) return false
        if (revision.get() != expected) return false
        val children = first.mapNotNull(UserEventNotificationGroup::child)
        val old = first.firstOrNull { it.tag == UserEventNotificationGroup.SUMMARY_TAG && it.id == UserEventNotificationGroup.SUMMARY_ID }
        if (children.isEmpty() && !allowEmpty) return false
        if (children.isNotEmpty() && matches(old, UserEventNotificationGroup.summaryState(children))) return true
        if (children.isEmpty() && old == null) return true
        if (manager.getNotificationChannel(NotificationChannelSpec.USER_EVENT_SUMMARY.id) == null) NotificationChannels.ensureChannels(context)
        // Pacing and coroutine suspension happen before the commit gate. Re-read after waiting.
        pacer.awaitSlot()
        return synchronized(UserEventNotificationGroup.mutationLock) {
            if (revision.get() != expected) return@synchronized false
            commitSummary(manager, compat, allowEmpty)
        }
    }

    /** Called only under the group mutation gate, after pacing and revision validation. */
    private fun commitSummary(manager: NotificationManager, compat: NotificationManagerCompat, allowEmpty: Boolean): Boolean {
        val latest = read(manager)
        val liveChildren = latest.mapNotNull(UserEventNotificationGroup::child)
        val liveSummary = latest.firstOrNull { it.tag == UserEventNotificationGroup.SUMMARY_TAG && it.id == UserEventNotificationGroup.SUMMARY_ID }
        if (liveChildren.isEmpty()) {
            if (!allowEmpty) return false
            if (liveSummary != null) cancel(compat, UserEventNotificationGroup.SUMMARY_TAG, UserEventNotificationGroup.SUMMARY_ID)
        } else {
            val liveFence = NotificationCardGenerations.captureFence()
            if (!matches(liveSummary, UserEventNotificationGroup.summaryState(liveChildren))) {
                post(compat, UserEventNotificationGroup.SUMMARY_TAG, UserEventNotificationGroup.SUMMARY_ID, UserEventNotificationGroup.summary(context, liveChildren, liveFence))
            }
        }
        // A subsequent bounded read confirms asynchronous platform visibility, including cancels.
        val after = read(manager)
        val afterChildren = after.mapNotNull(UserEventNotificationGroup::child)
        val afterSummary = after.firstOrNull { it.tag == UserEventNotificationGroup.SUMMARY_TAG && it.id == UserEventNotificationGroup.SUMMARY_ID }
        return if (afterChildren.isEmpty()) afterSummary == null else matches(afterSummary, UserEventNotificationGroup.summaryState(afterChildren))
    }

    private fun matches(summary: StatusBarNotification?, state: String): Boolean =
        summary != null && UserEventNotificationGroup.isSummary(summary.notification) &&
            summary.notification.group == UserEventNotificationGroup.KEY &&
            summary.notification.extras.getString(UserEventNotificationGroup.EXTRA_SUMMARY_STATE) == state

    /** Takes card stripes first and checks the current generation again before adopting a legacy card. */
    private suspend fun adoptLegacyCards(
        manager: NotificationManager,
        compat: NotificationManagerCompat,
        snapshot: Array<StatusBarNotification>,
    ): Boolean {
        var changed = false
        snapshot.filter { UserEventNotificationGroup.isChildCandidate(it) && UserEventNotificationGroup.child(it) == null }.forEach { candidate ->
            pacer.awaitSlot()
            ConversationCardPostSynchronizer.withLock(candidate.tag.orEmpty(), candidate.id, ConversationCardOp.REFRESH_CONTACT_NAME) {
                synchronized(UserEventNotificationGroup.mutationLock) {
                    val live = read(manager).firstOrNull { it.tag == candidate.tag && it.id == candidate.id } ?: return@synchronized
                    if (!UserEventNotificationGroup.isChildCandidate(live) || UserEventNotificationGroup.child(live) != null) return@synchronized
                    val generation = live.notification.extras.getString(UserEventNotificationGroup.EXTRA_GENERATION) ?: UUID.randomUUID().toString()
                    val builder = NotificationCompat.Builder(context, live.notification)
                        .addExtras(android.os.Bundle().apply { putLong(UserEventNotificationGroup.EXTRA_LEGACY_POST_TIME, UserEventNotificationGroup.dismissalTime(live)) })
                    val adopted = UserEventNotificationGroup.decorateChild(context, builder, NotificationGroupChild(requireNotNull(live.tag), live.id, generation), silent = true)
                        .setOnlyAlertOnce(true).setSilent(true).build()
                    post(compat, requireNotNull(live.tag), live.id, adopted)
                    ConversationCardPostedRegistry.markPosted(requireNotNull(live.tag), live.id, UserEventNotificationGroup.dismissalTime(live))
                    changed = true
                }
            }
        }
        return changed
    }

    companion object {
        private const val SETTLE_DELAY_MS = 150L
        private const val MAX_ATTEMPTS = 5
        private const val EMPTY_RECHECKS = 2
        private val sharedLock = Any()
        private var sharedContext: Context? = null
        private var shared: NotificationGroupReconciler? = null

        /** Exactly one coordinator per Android application; separate test applications cannot retain its worker. */
        fun shared(context: Context): NotificationGroupReconciler =
            synchronized(sharedLock) {
                val app = context.applicationContext
                if (sharedContext !== app) {
                    shared?.close()
                    sharedContext = app
                    shared = NotificationGroupReconciler(app)
                }
                requireNotNull(shared)
            }

        /** Every child mutation schedules reconciliation even when the platform call fails. */
        fun <T> mutate(context: Context, request: () -> Unit = { shared(context).request() }, block: () -> T): T =
            try {
                synchronized(UserEventNotificationGroup.mutationLock, block)
            } finally {
                request()
            }

        /** Final generation/live-card check shares the summary-dismissal commit gate. */
        fun postChild(
            context: Context,
            notification: Notification,
            request: () -> Unit = { shared(context).request() },
            isLive: (() -> Boolean)? = null,
            post: () -> Unit,
        ): Boolean =
            mutate(context, request) {
                if (NotificationCardGenerations.isDismissed(notification.extras.getString(UserEventNotificationGroup.EXTRA_GENERATION))) return@mutate false
                if (isLive != null && !isLive()) return@mutate false
                post()
                true
            }
    }
}
