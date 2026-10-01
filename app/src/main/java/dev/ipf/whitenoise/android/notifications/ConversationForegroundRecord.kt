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

    fun foregroundNotification(): Notification = dictation.notificationOrNull() ?: connectionNotification()

    private fun connectionNotification(): Notification = BackgroundConnectionNotification.build(service)

    fun promoteConnection(trigger: ForegroundStartTrigger) {
        // Automatic nudges cannot change an already authorized connection's type. Dictation
        // takes specialUse while visible, so a new connection can share that existing protection.
        val type =
            connectionServiceType.takeIf { it != 0 }
                ?: if (dictation.hasForegroundLease) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    foregroundServiceTypeForTrigger(trigger)
                }
        publishForeground(
            foregroundNotification(),
            type or dictation.foregroundServiceType,
            replaceRecord = !dictation.hasForegroundLease,
        )
        connectionServiceType = type
    }

    @Suppress("TooGenericExceptionCaught") // Android foreground rejection subclasses RuntimeException.
    private fun publishForeground(
        notification: Notification,
        type: Int,
        acquireMicrophone: Boolean = false,
        replaceRecord: Boolean = false,
    ) {
        val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val keepsAuthorizedMicrophone =
            foregroundPromoted &&
                publishedServiceType and microphone != 0 &&
                type and microphone != 0
        val keepsExistingType = publishedServiceType == type || keepsAuthorizedMicrophone
        val requiresRecordUpdate = acquireMicrophone || replaceRecord
        if (foregroundPromoted && !requiresRecordUpdate && keepsExistingType) {
            // Keep the authorized record intact while capture continues. Removed ordinary type
            // bits are narrowed after capture closes; never reassert microphone from a wake.
            notifyPresentation(notification)
            return
        }
        try {
            NotificationStreamForegroundService.foregroundPublisher(service, notification, type)
            publishedServiceType = type
            foregroundPromoted = true
        } catch (error: RuntimeException) {
            val addsType = type and publishedServiceType.inv() != 0
            val narrowingExistingRecord = foregroundPromoted && !acquireMicrophone && !addsType
            val foregroundRejection = error is SecurityException || error.isForegroundServiceStartRejection()
            if (!narrowingExistingRecord || !foregroundRejection) throw error
            // Detaching would lose foreground protection and a background restart can fail.
            // Keep the existing record, replace its controls, and retry narrowing on foreground.
            notifyPresentation(notification)
        }
    }

    private fun notifyPresentation(notification: Notification) {
        service.getSystemService(NotificationManager::class.java).notify(
            BackgroundConnectionNotification.NOTIFICATION_ID,
            notification,
        )
    }

    fun promoteDictation(
        notification: Notification,
        type: Int,
    ) {
        publishForeground(
            notification,
            connectionServiceType or type,
            acquireMicrophone = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0,
            replaceRecord = true,
        )
    }

    fun updateDictationNotification(notification: Notification) {
        if (isCurrent() && dictation.hasForegroundLease) {
            val type = connectionServiceType or dictation.foregroundServiceType
            publishForeground(
                notification,
                type,
                replaceRecord = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0,
            )
        }
    }

    /** A rejected ordinary card must never leave completed microphone controls behind. */
    @Suppress("TooGenericExceptionCaught") // API 31+ foreground-start rejection extends RuntimeException.
    fun releaseDictation(startId: Int = serviceStartId()) {
        if (dictation.hasForegroundLease || !isCurrent()) return
        if (connectionServiceType != 0) {
            publishForeground(connectionNotification(), connectionServiceType, replaceRecord = true)
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
        dictation.onDestroy()
        removeForegroundAndStop(serviceStartId())
    }
}
