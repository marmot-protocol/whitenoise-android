package dev.ipf.whitenoise.android.state

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.Data
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val TAG = "DMAttachmentJob"
private const val JOB_NAMESPACE = "attachment_download_v1"
private const val CHANNEL_ID = "attachment_download_v1"
private const val KEY_IDENTITY = "identity"
private const val KEY_ACCOUNT = "account_ref"
private const val KEY_GROUP = "group_id_hex"
private const val KEY_MESSAGE = "message_id_hex"
private const val KEY_INDEX = "attachment_index"
private const val KEY_SOURCE = "source_message_id_hex"

/** Wakes the installer handoff when a JobScheduler transfer finishes without WorkManager state. */
internal object AttachmentDownloadJobEvents {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    fun changed() {
        mutableRevision.update { it + 1 }
    }
}

/** Encodes only MDK's lookup identity in JobScheduler's persisted extras. */
internal fun attachmentJobExtras(request: AttachmentTransferRequest): PersistableBundle =
    PersistableBundle().apply {
        putString(KEY_IDENTITY, attachmentDownloadWorkName(request))
        putString(KEY_ACCOUNT, request.accountRef)
        putString(KEY_GROUP, request.groupIdHex)
        putString(KEY_MESSAGE, request.messageIdHex)
        putInt(KEY_INDEX, request.attachmentIndex)
        request.sourceMessageIdHex?.let { putString(KEY_SOURCE, it) }
    }

/** Reuses the WorkManager decoder so both schedulers reject the same malformed identity. */
internal fun decodeAttachmentJobExtras(extras: PersistableBundle): AttachmentTransferRequest? =
    AttachmentDownloadWorkData
        .decode(
            Data
                .Builder()
                .putString(KEY_ACCOUNT, extras.getString(KEY_ACCOUNT))
                .putString(KEY_GROUP, extras.getString(KEY_GROUP))
                .putString(KEY_MESSAGE, extras.getString(KEY_MESSAGE))
                .putInt(KEY_INDEX, extras.getInt(KEY_INDEX, -1))
                .putString(KEY_SOURCE, extras.getString(KEY_SOURCE))
                .build(),
        )?.takeIf { attachmentDownloadWorkName(it) == extras.getString(KEY_IDENTITY) }

/** A stable private ID; JobScheduler's namespace separates it from WorkManager IDs. */
internal fun attachmentJobId(request: AttachmentTransferRequest): Int =
    attachmentDownloadWorkName(request)
        .takeLast(DIGEST_PREFIX_HEX_LENGTH)
        .toLong(RADIX_HEX)
        .toInt() and Int.MAX_VALUE

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object AttachmentUserInitiatedDownloads {
    /** Schedules a visible user tap without App Standby job quota; failures use the worker fallback. */
    fun schedule(
        context: Context,
        request: AttachmentTransferRequest,
    ): Boolean =
        runCatching {
            val scheduler = scheduler(context) ?: return false
            val id = attachmentJobId(request)
            val existing = scheduler.getPendingJob(id)
            if (existing != null) {
                return existing.extras.getString(KEY_IDENTITY) == attachmentDownloadWorkName(request)
            }
            val job =
                JobInfo
                    .Builder(id, ComponentName(context, AttachmentUserInitiatedDownloadService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresStorageNotLow(true)
                    .setExtras(attachmentJobExtras(request))
                    .build()
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }.getOrElse {
            Log.w(TAG, "attachment_user_job_schedule_failed")
            false
        }

    /** Stops a user transfer after its durable intent is revoked. */
    fun cancel(
        context: Context,
        request: AttachmentTransferRequest,
    ) {
        runCatching {
            val owner = scheduler(context) ?: return@runCatching
            val id = attachmentJobId(request)
            val pending = owner.getPendingJob(id)
            if (pending == null || pending.extras.getString(KEY_IDENTITY) == attachmentDownloadWorkName(request)) {
                owner.cancel(id)
            }
        }.onFailure { Log.w(TAG, "attachment_user_job_cancel_failed") }
        AttachmentDownloadJobEvents.changed()
    }

    private fun scheduler(context: Context): JobScheduler? {
        val systemScheduler = context.getSystemService(JobScheduler::class.java)
        return systemScheduler?.forNamespace(JOB_NAMESPACE)
    }
}

/** Notification contains no conversation, attachment, account, or file metadata. */
internal fun attachmentDownloadNotification(context: Context): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.media_downloading),
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
    return Notification
        .Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_whitenoise)
        .setContentTitle(context.getString(R.string.media_downloading))
        .setContentText(context.getString(R.string.media_attachment))
        .setOngoing(true)
        .build()
}

/** Runs an explicit attachment fetch through Android's user-initiated transfer job. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class AttachmentUserInitiatedDownloadService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = AttachmentDownloadJobRuns(scope)

    override fun onStartJob(params: JobParameters): Boolean {
        val request = decodeAttachmentJobExtras(params.extras)
        if (request == null) {
            Log.w(TAG, "attachment_user_job_invalid_identity")
            return false
        }
        setNotification(
            params,
            params.jobId,
            attachmentDownloadNotification(this),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        runs.start(
            jobId = params.jobId,
            download = { runDownload(request) },
            onFinished = {
                attachmentIntentStore(applicationContext).setInteractive(request, interactive = false)
                AttachmentDownloadJobEvents.changed()
                jobFinished(params, false)
            },
        )
        return true
    }

    @Suppress("TooGenericExceptionCaught") // This JobService boundary must finish jobs for all native failures.
    private suspend fun runDownload(
        request: AttachmentTransferRequest,
    ) {
        val store = attachmentIntentStore(applicationContext)
        try {
            if (store.isInteractive(request)) {
                val app = application as WhiteNoiseApplication
                withContext(Dispatchers.Main.immediate) {
                    app.appState.ensureNotificationRuntimeStarted()
                }
                val retained =
                    app.appState.downloadAttachmentForDurableWork(
                        request,
                        AttachmentDownloadPriority.Interactive,
                    )
                if (!retained) Log.w(TAG, "attachment_user_job_not_retained")
            }
        } catch (cancel: CancellationException) {
            Log.w(TAG, "attachment_user_job_cancelled type=${cancel.javaClass.simpleName}")
            throw cancel
        } catch (failure: Throwable) {
            Log.w(TAG, "attachment_user_job_failed type=${failure.javaClass.simpleName}")
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.w(TAG, "attachment_user_job_stopped reason=${params.stopReason}")
        runs.stop(params.jobId)
        AttachmentDownloadJobEvents.changed()
        return decodeAttachmentJobExtras(params.extras)?.let { request ->
            attachmentIntentStore(applicationContext).isInteractive(request)
        } == true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private const val DIGEST_PREFIX_HEX_LENGTH = 8
private const val RADIX_HEX = 16
