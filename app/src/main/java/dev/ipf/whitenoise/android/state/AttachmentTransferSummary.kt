package dev.ipf.whitenoise.android.state

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import dev.ipf.whitenoise.android.R

/** Groups every transfer card, so several live cards collapse under one count instead of piling up. */
internal const val ATTACHMENT_TRANSFER_GROUP = "attachment_transfers"

private const val TAG = "DMAttachmentTransfers"
private const val SUMMARY_NOTIFICATION_ID = -0x5A7D

/** Safety net: an orphaned summary vanishes on its own, because live counts re-post it and so reset the timeout. */
private const val SUMMARY_TIMEOUT_MILLIS = 30L * 60L * 1000L

/** Fewest live cards that get a count summary, since a single card needs no explanation. */
private const val SUMMARY_MIN_COUNT = 2

/**
 * The group summary shown while [count] transfers are live. It states only how many, never which attachments,
 * and is as quiet as the cards beneath it.
 */
internal fun attachmentTransferSummaryNotification(
    context: Context,
    count: Int,
): Notification =
    Notification
        .Builder(context, ensureAttachmentDownloadChannel(context))
        .setSmallIcon(R.drawable.ic_stat_whitenoise)
        .setContentTitle(context.getString(R.string.media_downloading))
        .setContentText(context.resources.getQuantityString(R.plurals.media_downloading_count, count, count))
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setGroup(ATTACHMENT_TRANSFER_GROUP)
        .setGroupSummary(true)
        .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .setTimeoutAfter(SUMMARY_TIMEOUT_MILLIS)
        .build()

/** Keeps one count summary in step with the number of live transfer cards. */
internal class AttachmentTransferSummaryNotifier(
    context: Context,
) : (Int) -> Unit {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    /** Posts or refreshes the summary from [count] live cards, or removes it when fewer than two remain. */
    override fun invoke(count: Int) {
        if (count < SUMMARY_MIN_COUNT) {
            manager.cancel(SUMMARY_NOTIFICATION_ID)
        } else {
            manager.notify(SUMMARY_NOTIFICATION_ID, attachmentTransferSummaryNotification(appContext, count))
        }
    }

    /** Removes a summary left by a process that ended while transfers were live, as none is live in this one. */
    fun clearStale() {
        manager.cancel(SUMMARY_NOTIFICATION_ID)
    }
}

/** The process-wide ledger of live transfers, wired to the count summary and the diagnostics log. */
internal object AttachmentTransfers {
    private val inert = AttachmentTransferLedger()

    @Volatile
    private var installed: AttachmentTransferLedger? = null

    /** The installed ledger, or an inert one before [install] so that early callers never fail. */
    val ledger: AttachmentTransferLedger
        get() = installed ?: inert

    /** Wires the ledger to notifications once per process, first removing any summary a dead process left. */
    fun install(context: Context) {
        val notifier = AttachmentTransferSummaryNotifier(context)
        notifier.clearStale()
        installed = AttachmentTransferLedger(onLiveCountChanged = notifier, log = { Log.i(TAG, it) })
    }

    /** Replaces the ledger so a test controls it, or restores the inert one with null. */
    fun installForTest(ledger: AttachmentTransferLedger?) {
        installed = ledger
    }
}

/**
 * The user-initiated service's view of the ledger: one token per job id, so a finished, stopped or replaced run
 * ends exactly the transfer it began and a destroyed service leaves nothing counted.
 */
internal class UserInitiatedTransfers(
    private val ledger: () -> AttachmentTransferLedger,
) {
    private val tokens = java.util.concurrent.ConcurrentHashMap<Int, AttachmentTransferToken>()

    /** Counts the job's transfer as live under this scheduler. */
    fun began(jobId: Int) {
        tokens[jobId] = ledger().begin(jobId, AttachmentTransferOwner.UserInitiatedJob)
    }

    /** Ends the job's current run as completed. A run already stopped or replaced changes nothing. */
    fun finished(jobId: Int) = end(jobId, AttachmentTransferOutcome.Completed)

    /** Ends the job's current run because the scheduler stopped it. */
    fun stopped(jobId: Int) = end(jobId, AttachmentTransferOutcome.Stopped)

    /** Ends every run this service still owns, because the service is going away. */
    fun stoppedAll() = tokens.keys.toList().forEach(::stopped)

    /** Removes the job's token and ends that run, so a replacement begun meanwhile is left alone. */
    private fun end(
        jobId: Int,
        outcome: AttachmentTransferOutcome,
    ) {
        tokens.remove(jobId)?.let { ledger().end(it, outcome) }
    }
}
