package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationForegroundService
import dev.ipf.whitenoise.android.audio.VoiceNoteForegroundService
import dev.ipf.whitenoise.android.audio.conversationDictationDiagnostic
import dev.ipf.whitenoise.android.audio.isForegroundServiceStartRejection

/** Main-owned leases and presentation for one concrete Android foreground-service record. */
@Suppress("TooManyFunctions") // Connection and capture share one Android foreground record.
internal class ConversationForegroundRecord(
    private val service: NotificationStreamForegroundService,
    private val isCurrent: () -> Boolean,
    private val serviceStartId: () -> Int,
    private val connectionStartId: () -> Int,
    private val onConnectionReleased: () -> Unit,
) {
    val dictation = ConversationDictationForegroundService(service)
    val voice = VoiceNoteForegroundService(service)
    private val microphoneServiceType: Int
        get() = dictation.foregroundServiceType or voice.serviceType
    private val hasCaptureLease: Boolean
        get() = dictation.hasForegroundLease || voice.notification != null
    private val hasActiveLease: Boolean
        get() = connectionServiceType != 0 || hasCaptureLease

    var connectionServiceType = 0
        private set
    private var foregroundPromoted = false
    private var publishedServiceType = 0
    private var publishedNotificationId = 0
    private var showingDeadlinePlaceholder = false
    private var pendingStartDeadlineId: Int? = null
    private var stoppedStartId: Int? = null

    /** Records an unmet startForegroundService command so re-entrant releases satisfy the deadline first. */
    fun noteForegroundStartPending(startId: Int) {
        if (!foregroundPromoted) {
            pendingStartDeadlineId = startId
        }
        if (stoppedStartId == startId) {
            stoppedStartId = null
        }
    }

    fun foregroundNotification(): Notification {
        val capture = voice.notification ?: dictation.notificationOrNull()
        return capture ?: connectionNotification
    }

    private val connectionNotification: Notification
        get() = BackgroundConnectionNotification.build(service)

    fun promoteConnection(trigger: ForegroundStartTrigger) {
        // Automatic nudges cannot change an already authorized connection's type. Dictation
        // takes specialUse while visible, so a new connection can share that existing protection.
        val type =
            connectionServiceType.takeIf { it != 0 }
                ?: if (hasCaptureLease) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    foregroundServiceTypeForTrigger(trigger)
                }
        publishForeground(
            foregroundNotification(),
            type or microphoneServiceType,
            replaceRecord = !hasCaptureLease,
        )
        connectionServiceType = type
    }

    /** Publishes or updates the active foreground notification and clears any pending start deadline. */
    @Suppress("TooGenericExceptionCaught") // Android foreground rejection subclasses RuntimeException.
    private fun publishForeground(
        notification: Notification,
        type: Int,
        acquireMicrophone: Boolean = false,
        replaceRecord: Boolean = false,
    ) {
        val notificationId = NotificationStreamForegroundService.foregroundNotificationId(notification)
        val keepsExistingType =
            publishedServiceType == type ||
                keepsAuthorizedMicrophone &&
                type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
        val canUpdatePresentation = keepsExistingType && notificationId == publishedNotificationId
        val requiresRecordUpdate = acquireMicrophone || replaceRecord
        if (foregroundPromoted && !requiresRecordUpdate && canUpdatePresentation) {
            // Keep the authorized record intact while capture continues. Removed ordinary type
            // bits are narrowed after capture closes; never reassert microphone from a wake.
            notifyPresentation(notification)
            showingDeadlinePlaceholder = false
            return
        }
        try {
            NotificationStreamForegroundService.foregroundPublisher(service, notification, type)
            publishedServiceType = type
            publishedNotificationId = notificationId
            foregroundPromoted = true
            showingDeadlinePlaceholder = false
            pendingStartDeadlineId = null
            stoppedStartId = null
        } catch (error: RuntimeException) {
            val addsType = type and publishedServiceType.inv() != 0
            val narrowingExistingRecord = foregroundPromoted && !acquireMicrophone && !addsType
            val foregroundRejection = error is SecurityException || error.isForegroundServiceStartRejection()
            if (!narrowingExistingRecord || !foregroundRejection) throw error
            // Detaching would lose foreground protection and a background restart can fail.
            // Keep the existing record, replace its controls, and retry narrowing on foreground.
            notifyPresentation(notification)
            showingDeadlinePlaceholder = false
        }
    }

    /** A currently authorized microphone record survives ordinary connection type changes. */
    private val keepsAuthorizedMicrophone: Boolean
        get() = foregroundPromoted && publishedServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0

    private fun notifyPresentation(notification: Notification) {
        val presentation =
            if (NotificationStreamForegroundService.foregroundNotificationId(notification) != publishedNotificationId) {
                // A rejected identity switch must clear controls on the still-authorized channel.
                Notification.Builder
                    .recoverBuilder(service, notification)
                    .setChannelId(
                        if (publishedNotificationId == NotificationStreamForegroundService.VOICE_NOTIFICATION_ID) {
                            VoiceNoteForegroundService.CHANNEL_ID
                        } else {
                            ConversationDictationForegroundService.CHANNEL_ID
                        },
                    ).build()
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
        val type = connectionServiceType or microphoneServiceType
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

    fun promoteVoice(notification: Notification) {
        publishForeground(
            notification,
            connectionServiceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            acquireMicrophone = true,
        )
    }

    /** Releases the voice-note lease, restoring connection or stopping cleanly. */
    fun releaseVoice(
        startId: Int = serviceStartId(),
        satisfyStartDeadline: Boolean = false,
    ) {
        if (!isCurrent() || voice.notification != null || dictation.hasForegroundLease) return
        if (connectionServiceType != 0) {
            restoreConnectionIfNeeded()
        } else {
            removeForegroundAndStop(startId, satisfyStartDeadline)
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
            val type = connectionServiceType or microphoneServiceType
            publishForeground(
                notification,
                type,
                replaceRecord = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0,
            )
        }
    }

    /** A rejected ordinary card must never leave completed microphone controls behind. */
    @Suppress("TooGenericExceptionCaught") // API 31+ foreground-start rejection extends RuntimeException.
    fun releaseDictation(
        startId: Int = serviceStartId(),
        satisfyStartDeadline: Boolean = false,
    ) {
        val current = isCurrent()
        conversationDictationDiagnostic(
            "event=foreground_service_stop source=teardown active=$current " +
                "durable=${dictation.hasForegroundLease} pending=${connectionServiceType != 0}",
        )
        if (hasCaptureLease || !current) return
        if (connectionServiceType != 0) {
            restoreConnectionIfNeeded()
        } else {
            removeForegroundAndStop(startId, satisfyStartDeadline)
        }
    }

    /** Restores the connection card only when capture changed the published foreground record. */
    private fun restoreConnectionIfNeeded() {
        val sameConnectionCard =
            publishedServiceType == connectionServiceType &&
                publishedNotificationId == BackgroundConnectionNotification.NOTIFICATION_ID
        val alreadyShowingConnection = foregroundPromoted && !showingDeadlinePlaceholder && sameConnectionCard
        if (!alreadyShowingConnection) {
            publishForeground(connectionNotification, connectionServiceType, replaceRecord = true)
        }
    }

    /** Stops an idle service for a stale non-foreground action without publishing a placeholder. */
    fun stopIfIdleWithoutPlaceholder(startId: Int) {
        if (!isCurrent() || hasActiveLease) return
        removeForegroundAndStop(startId, satisfyStartDeadline = false)
    }

    /** Releasing connection never interrupts a separately authorized microphone lease. */
    fun releaseConnection(expectedStartId: Int = connectionStartId()) {
        if (expectedStartId != connectionStartId() || !isCurrent()) return
        if (connectionServiceType == 0) {
            if (!hasCaptureLease) removeForegroundAndStop(serviceStartId())
            return
        }
        connectionServiceType = 0
        try {
            if (voice.notification != null) {
                publishForeground(foregroundNotification(), microphoneServiceType)
            } else if (dictation.hasForegroundLease) {
                dictation.refreshNotification()
            } else {
                removeForegroundAndStop(serviceStartId())
            }
        } finally {
            onConnectionReleased()
        }
    }

    /** Stops the service for [startId], downgrading to an action-free placeholder if a newer start is queued. */
    private fun removeForegroundAndStop(
        startId: Int,
        satisfyStartDeadline: Boolean = false,
    ) {
        val hasUnmetDeadline = satisfyStartDeadline || pendingStartDeadlineId != null
        if (stoppedStartId == startId && pendingStartDeadlineId == null) return
        if (hasUnmetDeadline && !foregroundPromoted) {
            satisfyStartDeadlineBeforeStop()
        }
        stoppedStartId = startId
        val stopped = service.stopSelfResult(startId)
        if (stopped) {
            if (foregroundPromoted) {
                NotificationStreamForegroundService.foregroundRemover(service)
                foregroundPromoted = false
                publishedServiceType = 0
                publishedNotificationId = 0
                showingDeadlinePlaceholder = false
            }
        } else if (foregroundPromoted && !showingDeadlinePlaceholder) {
            publishSpecialUsePlaceholder(clearControlsOnRejection = true)
        }
        conversationDictationDiagnostic(
            "event=foreground_notification_closed accepted=$stopped foreground=$foregroundPromoted",
        )
    }

    /** Publishes a minimal special-use notification so a stale foreground start can stop without crashing. */
    private fun satisfyStartDeadlineBeforeStop() {
        pendingStartDeadlineId = null
        publishSpecialUsePlaceholder(clearControlsOnRejection = false)
    }

    /** Publishes the action-free special-use placeholder, clearing dead controls if narrowing is rejected. */
    @Suppress("TooGenericExceptionCaught") // API 31+ foreground-start rejection extends RuntimeException.
    private fun publishSpecialUsePlaceholder(clearControlsOnRejection: Boolean) {
        val placeholder = deadlinePlaceholderNotification()
        try {
            NotificationStreamForegroundService.foregroundPublisher(
                service,
                placeholder,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            publishedServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            publishedNotificationId = NotificationStreamForegroundService.foregroundNotificationId(placeholder)
            foregroundPromoted = true
            showingDeadlinePlaceholder = true
        } catch (_: SecurityException) {
            // Android rejected even specialUse; clear dead controls if already foreground, else stop immediately.
            if (clearControlsOnRejection && foregroundPromoted) notifyPresentation(placeholder)
        } catch (error: RuntimeException) {
            if (!error.isForegroundServiceStartRejection()) throw error
            if (clearControlsOnRejection && foregroundPromoted) notifyPresentation(placeholder)
        }
    }

    /** Builds an action-free placeholder on the low-importance service channel for immediate teardown. */
    private fun deadlinePlaceholderNotification(): Notification {
        val manager = service.getSystemService(NotificationManager::class.java)
        if (manager != null && manager.getNotificationChannel(BackgroundConnectionNotification.CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    BackgroundConnectionNotification.CHANNEL_ID,
                    service.getString(R.string.notification_channel_background_connection),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = service.getString(R.string.notification_channel_background_connection_description)
                    setShowBadge(false)
                },
            )
        }
        return Notification
            .Builder(service, BackgroundConnectionNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(service.getString(R.string.app_name))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    fun onDestroy() {
        connectionServiceType = 0
        dictation.onDestroy()
        voice.onDestroy()
        removeForegroundAndStop(serviceStartId())
    }
}
