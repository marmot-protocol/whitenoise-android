package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationManager
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
    private var publishedServiceType = 0
    private var connectionUserOwned = false

    fun foregroundNotification(): Notification = dictation.notificationOrNull() ?: connectionNotification()

    private fun connectionNotification(): Notification = BackgroundConnectionNotification.build(service)

    fun promoteConnection(trigger: ForegroundStartTrigger) {
        val type = foregroundServiceTypeForTrigger(trigger)
        publishForeground(
            foregroundNotification(),
            type or if (dictation.hasForegroundLease) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        connectionServiceType = type
        connectionUserOwned = connectionUserOwned || trigger == ForegroundStartTrigger.UserToggle
    }

    private fun publishForeground(
        notification: Notification,
        type: Int,
    ) {
        if (!foregroundPromoted || publishedServiceType != type) {
            NotificationStreamForegroundService.foregroundPublisher(service, notification, type)
            publishedServiceType = type
            foregroundPromoted = true
        } else {
            service.getSystemService(NotificationManager::class.java).notify(
                BackgroundConnectionNotification.NOTIFICATION_ID,
                notification,
            )
        }
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
                publishForeground(connectionNotification(), connectionServiceType)
            } catch (_: SecurityException) {
                val reconcilePreference = connectionUserOwned
                releaseConnection()
                if (reconcilePreference) service.onConnectionRestoreRejected()
            } catch (error: RuntimeException) {
                if (!error.isForegroundServiceStartRejection()) throw error
                val reconcilePreference = connectionUserOwned
                releaseConnection()
                if (reconcilePreference) service.onConnectionRestoreRejected()
            }
        } else {
            removeForegroundAndStop(startId)
        }
    }

    /** Releasing connection never interrupts a separately authorized microphone lease. */
    fun releaseConnection(expectedStartId: Int = connectionStartId()) {
        if (expectedStartId != connectionStartId() || !isCurrent()) return
        if (connectionServiceType == 0) {
            if (!dictation.hasForegroundLease) removeForegroundAndStop(serviceStartId())
            return
        }
        connectionServiceType = 0
        connectionUserOwned = false
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
            publishedServiceType = 0
        }
        service.stopSelfResult(startId)
    }

    fun onDestroy() {
        connectionServiceType = 0
        connectionUserOwned = false
        dictation.onDestroy()
        removeForegroundAndStop(serviceStartId())
    }
}
