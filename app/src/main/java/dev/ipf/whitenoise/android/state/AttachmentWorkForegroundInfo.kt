package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo

/** Gives WorkManager's foreground fallback the data-sync type declared by its service. */
internal fun attachmentWorkForegroundInfo(
    context: Context,
    request: AttachmentTransferRequest,
): ForegroundInfo =
    ForegroundInfo(
        attachmentJobId(request),
        attachmentDownloadNotification(context),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
