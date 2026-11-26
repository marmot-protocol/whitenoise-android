package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns Firebase auto-init on only while [admitted] still holds, checked together with the SDK write under
 * [NotificationDeliveryController.fcmAutoInitAdmission], which a delivery-mode change also takes as it opens. So a
 * newer Local choice either opens first and this writes nothing, or opens after the on write and its own cutover
 * turns auto-init off. Returns null when [admitted] no longer held and nothing was written, otherwise whether the
 * write settled: it succeeded, or its durable retry was stored.
 */
internal suspend fun NotificationDeliveryController.enableFcmAutoInitIfAdmitted(admitted: () -> Boolean): Boolean? {
    val applied =
        withContext(Dispatchers.IO) {
            synchronized(fcmAutoInitAdmission) {
                if (admitted()) runCatching { nativePushFallbackPlatform.setFcmAutoInitEnabled(true) } else null
            }
        }
    applied?.let { fcmAutoInitWriteFailed.set(it.isFailure) }
    val failure = applied?.exceptionOrNull()
    return when {
        applied == null -> null
        failure == null -> true.also { fcmAutoInitRetryUnstored.set(false) }
        else -> {
            appStateDebug(failure) { "FCM auto-init update failed enabled=true" }
            recordPendingRegistrationSync().also { stored -> fcmAutoInitRetryUnstored.set(!stored) }
        }
    }
}

/**
 * Decides auto-init for a registration sync of accounts with native push demand. While an enable can still roll
 * back or a delivery-mode change is open, that owner settles auto-init, so the sync defers and returns true.
 * Otherwise it turns auto-init on through [enableFcmAutoInitIfAdmitted], which checks the same counts again with
 * the SDK call, so a newer change opening first makes it write nothing and the sync defers like any other.
 */
@Suppress("MaxLineLength")
internal suspend fun NotificationDeliveryController.deferOrEnableFcmAutoInitForSync(operationIsCurrent: () -> Boolean): Boolean {
    val quiet = { uncommittedNativePushEnables.get() == 0 && activeDeliveryModeTransactions.get() == 0 }
    return !quiet() || enableFcmAutoInitIfAdmitted { quiet() && operationIsCurrent() } == null
}
