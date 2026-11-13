package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/** Replaces WorkManager with a synchronous test instance whose delays elapse only when a test says so. */
internal fun useTestWorkManager(context: Context) {
    WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
}

/** The one scheduled clear still waiting for its deadline. */
internal fun pendingSensitiveClear(context: Context): WorkInfo =
    WorkManager
        .getInstance(context)
        .getWorkInfosForUniqueWork(SENSITIVE_CLIP_CLEAR_WORK)
        .get()
        .single { it.state == WorkInfo.State.ENQUEUED }

/** Runs the pending clear as though its delay had elapsed. */
internal fun reachSensitiveClipDeadline(context: Context) {
    val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    driver.setInitialDelayMet(pendingSensitiveClear(context).id)
}
