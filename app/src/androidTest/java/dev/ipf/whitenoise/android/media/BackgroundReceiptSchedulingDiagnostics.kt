package dev.ipf.whitenoise.android.media

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkInfo
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_SCHEDULING_ROWS = 12

/** Observes stalled fixture work without enqueueing it, changing its deadline or exporting work identities. */
internal fun backgroundReceiptSchedulingDiagnostics(
    context: Context,
    work: List<WorkInfo>,
): JSONObject {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    val jobs = pendingJobs(scheduler)
    val power = context.getSystemService(PowerManager::class.java)
    val activity = context.getSystemService(ActivityManager::class.java)
    val now = System.currentTimeMillis()
    return JSONObject()
        .put("platform_snapshot_available", jobs != null)
        .put("device_idle", power?.isDeviceIdleMode ?: JSONObject.NULL)
        .put("power_save", power?.isPowerSaveMode ?: JSONObject.NULL)
        .put(
            "background_restricted",
            if (Build.VERSION.SDK_INT >= 28) activity?.isBackgroundRestricted ?: JSONObject.NULL else JSONObject.NULL,
        ).put(
            "work",
            JSONArray(
                work.take(MAX_SCHEDULING_ROWS).map { info ->
                    val matching =
                        jobs.orEmpty().flatMap { (namespace, pending) ->
                            pending
                                .filter { it.extras.getString("EXTRA_WORK_SPEC_ID") == info.id.toString() }
                                .map { namespace to it }
                        }
                    val next = info.nextScheduleTimeMillis
                    JSONObject()
                        .put("state", info.state.name)
                        .put("run_attempt_count", info.runAttemptCount)
                        .put("stop_reason", info.stopReason)
                        .put("eligible_in_ms", if (next == Long.MAX_VALUE) JSONObject.NULL else next - now)
                        .put("network_requirement", info.constraints.requiredNetworkType.name)
                        .put("storage_not_low_required", info.constraints.requiresStorageNotLow())
                        .put("system_job_count", matching.size)
                        .put(
                            "pending_reasons",
                            JSONArray(matching.map { (namespace, job) -> pendingReason(scheduler, namespace, job.id) }),
                        )
                },
            ),
        )
}

/** Reads the app's platform jobs, retaining an unavailable snapshot separately from an empty one. */
private fun pendingJobs(scheduler: JobScheduler?): Map<String?, List<JobInfo>>? =
    try {
        if (Build.VERSION.SDK_INT >= 34) {
            scheduler?.pendingJobsInAllNamespaces
        } else {
            scheduler?.allPendingJobs?.let { mapOf(null to it) }
        }
    } catch (_: RuntimeException) {
        null
    }

/** Captures only Android's closed pending-reason code, never namespace names, job ids or exception text. */
private fun pendingReason(
    scheduler: JobScheduler?,
    namespace: String?,
    jobId: Int,
): Any =
    if (Build.VERSION.SDK_INT >= 34 && scheduler != null) {
        try {
            val owner = if (namespace == null) scheduler else scheduler.forNamespace(namespace)
            owner.getPendingJobReason(jobId)
        } catch (_: RuntimeException) {
            JSONObject.NULL
        }
    } else {
        JSONObject.NULL
    }
