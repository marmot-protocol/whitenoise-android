package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import dev.ipf.whitenoise.android.audio.ConversationDictationForegroundService
import dev.ipf.whitenoise.android.audio.conversationDictationDiagnostic
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
    private var publishedNotificationId = 0

    fun foregroundNotification(): Notification = dictation.notificationOrNull() ?: connectionNotification

    private val connectionNotification: Notification
        get() = BackgroundConnectionNotification.build(service)

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
        val notificationId = NotificationStreamForegroundService.foregroundNotificationId(notification)
        val canUpdatePresentation = keepsExistingType && notificationId == publishedNotificationId
        if (foregroundPromoted && !requiresRecordUpdate && canUpdatePresentation) {
            // Keep the authorized record intact while capture continues. Removed ordinary type
            // bits are narrowed after capture closes; never reassert microphone from a wake.
            notifyPresentation(notification)
            return
        }
        try {
            NotificationStreamForegroundService.foregroundPublisher(service, notification, type)
            publishedServiceType = type
            publishedNotificationId = notificationId
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
        val presentation =
            if (NotificationStreamForegroundService.foregroundNotificationId(notification) != publishedNotificationId) {
                // A rejected identity switch must clear controls on the still-authorized channel.
                Notification.Builder.recoverBuilder(service, notification)
                    .setChannelId(ConversationDictationForegroundService.CHANNEL_ID)
                    .build()
            } else {
                notification
            }
        service.getSystemService(NotificationManager::class.java).notify(
            publishedNotificationId,
            presentation,
        )
    }

    /** An earlier rejected narrowing must retry even when dictation has already completed. */
    fun reconcileAfterForegroundReturn() {
        if (!isCurrent() || !foregroundPromoted) return
        val type = connectionServiceType or dictation.foregroundServiceType
        val notification = foregroundNotification()
        val sameId =
            NotificationStreamForegroundService.foregroundNotificationId(notification) == publishedNotificationId
        val microphoneActive = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
        if (type == publishedServiceType && sameId || microphoneActive) return
        if (type == 0) {
            removeForegroundAndStop(serviceStartId())
        } else {
            publishForeground(notification, type, replaceRecord = true)
        }
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
        val current = isCurrent()
        conversationDictationDiagnostic(
            "event=foreground_service_stop source=teardown active=$current " +
                "durable=${dictation.hasForegroundLease} pending=${connectionServiceType != 0}",
        )
        if (dictation.hasForegroundLease || !current) return
        if (connectionServiceType != 0) {
            publishForeground(connectionNotification, connectionServiceType, replaceRecord = true)
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
            publishedNotificationId = 0
        }
        val stopped = service.stopSelfResult(startId)
        conversationDictationDiagnostic(
            "event=foreground_notification_closed accepted=$stopped foreground=$foregroundPromoted",
        )
    }

    fun onDestroy() {
        connectionServiceType = 0
        dictation.onDestroy()
        removeForegroundAndStop(serviceStartId())
    }
}
