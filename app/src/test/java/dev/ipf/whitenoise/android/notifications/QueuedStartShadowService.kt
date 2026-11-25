package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.Service
import android.os.Build
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/** Captures foreground publication before removal and lets tests simulate a queued newer startId. */
@Implements(Service::class)
class QueuedStartShadowService : ShadowService() {
    val rejectedStopSelfIds = mutableSetOf<Int>()
    val publishedNotifications = mutableListOf<PublishedForegroundNotification>()
    val stopSelfResultCalls = mutableListOf<Int>()
    val stopForegroundCalls = mutableListOf<Boolean>()
    val events = mutableListOf<String>()
    private var recordingTypedStart = false

    /** Records untyped foreground promotions before any later STOP_FOREGROUND_REMOVE clears them. */
    @Implementation
    public override fun startForeground(
        id: Int,
        notification: Notification,
    ) {
        super.startForeground(id, notification)
        if (!recordingTypedStart) {
            publishedNotifications += PublishedForegroundNotification(id, notification, 0)
            events += "startForeground:0"
        }
    }

    /** Records typed foreground promotions without double-counting Robolectric's untyped delegation. */
    @Implementation(minSdk = Build.VERSION_CODES.Q)
    public override fun startForeground(
        id: Int,
        notification: Notification,
        foregroundServiceType: Int,
    ) {
        recordingTypedStart = true
        try {
            super.startForeground(id, notification, foregroundServiceType)
        } finally {
            recordingTypedStart = false
        }
        publishedNotifications += PublishedForegroundNotification(id, notification, foregroundServiceType)
        events += "startForeground:$foregroundServiceType"
    }

    /** Records foreground removal calls in event order before delegating to ShadowService. */
    @Implementation
    public override fun stopForeground(removeNotification: Boolean) {
        stopForegroundCalls += removeNotification
        events += "stopForeground:$removeNotification"
        super.stopForeground(removeNotification)
    }

    /** Rejects configured startIds to simulate a newer queued command before delegating to ShadowService. */
    @Implementation
    public override fun stopSelfResult(id: Int): Boolean {
        stopSelfResultCalls += id
        val accepted =
            if (id in rejectedStopSelfIds) {
                false
            } else {
                super.stopSelfResult(id)
            }
        events += "stopSelfResult:$id:$accepted"
        return accepted
    }
}

/** Foreground notification metadata recorded at publication time before STOP_FOREGROUND_REMOVE clears it. */
data class PublishedForegroundNotification(
    val id: Int,
    val notification: Notification,
    val serviceType: Int,
)
