package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.pm.ServiceInfo
import dev.ipf.whitenoise.android.audio.ConversationDictationForegroundService
import dev.ipf.whitenoise.android.audio.isForegroundServiceStartRejection

/** Main-owned leases and presentation for one concrete Android foreground-service record. */
internal class ConversationForegroundRecord(
    private val service: NotificationStreamForegroundService,
    private val isCurrent: () -> Boolean,
    private val serviceStartId: () -> Int,
    private val connectionStartId: () -> Int,
    private val onConnectionReleased: () -> Unit,
) {
    val dictation = ConversationDictationForegroundService(service)
    var connectionServiceType = 0
        private set
    private var foregroundPromoted = false

    fun foregroundNotification(): Notification {
        return dictation.notificationOrNull() ?: BackgroundConnectionNotification.build(service)
    }

    fun promoteConnection(trigger: ForegroundStartTrigger) {
        val type = foregroundServiceTypeForTrigger(trigger)
        publishForeground(
            foregroundNotification(),
            type or if (dictation.hasForegroundLease) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        connectionServiceType = type
    }

    private fun publishForeground(
        notification: Notification,
        type: Int,
    ) {
        NotificationStreamForegroundService.foregroundPublisher(service, notification, type)
        foregroundPromoted = true
    }

    fun promoteDictation(notification: Notification) {
        publishForeground(notification, connectionServiceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    fun updateDictationNotification(notification: Notification) {
        if (isCurrent() && dictation.hasForegroundLease) promoteDictation(notification)
    }

    /** A rejected ordinary card must never leave completed microphone controls behind. */
    @Suppress("TooGenericExceptionCaught") // API 31+ foreground-start rejection extends RuntimeException.
    fun releaseDictation(startId: Int = serviceStartId()) {
        if (dictation.hasForegroundLease || !isCurrent()) return
        if (connectionServiceType != 0) {
            try {
                publishForeground(BackgroundConnectionNotification.build(service), connectionServiceType)
            } catch (_: SecurityException) {
                releaseConnection()
            } catch (error: RuntimeException) {
                if (!error.isForegroundServiceStartRejection()) throw error
                releaseConnection()
            }
        } else {
            removeForegroundAndStop(startId)
        }
    }

    /** Releasing connection never interrupts a separately authorized microphone lease. */
    fun releaseConnection(expectedStartId: Int = connectionStartId()) {
        if (expectedStartId != connectionStartId() || !isCurrent()) return
        connectionServiceType = 0
        try {
            if (dictation.hasForegroundLease) {
                dictation.refreshNotification()
            } else {
                removeForegroundAndStop(serviceStartId())
            }
        } finally {
            onConnectionReleased()
        }
    }

    private fun removeForegroundAndStop(startId: Int) {
        if (foregroundPromoted) {
            NotificationStreamForegroundService.foregroundRemover(service)
            foregroundPromoted = false
        }
        service.stopSelfResult(startId)
    }

    fun onDestroy() {
        connectionServiceType = 0
        dictation.onDestroy()
        removeForegroundAndStop(serviceStartId())
    }
}
