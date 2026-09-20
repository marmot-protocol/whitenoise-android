package dev.ipf.whitenoise.android.notifications

import android.os.Build
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Received priority is policy; original priority is diagnostic evidence only. */
internal enum class PushWakePriority { High, Normal, Unknown }

/** Terminal dispatch acknowledgement; failures never masquerade as durable handoffs. */
internal enum class PushWakeDispatch { Owner, Service, Scheduled, PersistenceFailed, ScheduleFailed, Exhausted }

/** Shared callback boundary, with injectable ownership and platform dispatch seams. */
internal class PushWakeRecoveryCoordinator(
    private val store: PushTokenStore,
    private val acceptOwner: suspend () -> Boolean,
    private val startService: () -> Boolean,
    private val schedule: (Boolean) -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Records first, then transfers responsibility to an explicit owner or confirmed durable work. */
    suspend fun receive(
        priority: PushWakePriority,
        originalPriority: PushWakePriority = priority,
        deleted: Boolean = false,
    ): PushWakeDispatch {
        PushWakeDiagnostics.received(priority, originalPriority, deleted)
        val persisted = store.recordPendingPushWakeCatchUp() && store.admitPushWakeEpisode(nowMs())
        val ready = store.pushWakeRetryDelay(nowMs()) == 0L
        val highPriority = !deleted && priority == PushWakePriority.High
        return when {
            !persisted -> {
                PushWakeDiagnostics.event(PushWakeEvent.PersistenceFailed)
                PushWakeDispatch.PersistenceFailed
            }
            store.pushWakeAttempts() >= PUSH_WAKE_MAX_ATTEMPTS -> PushWakeDispatch.Exhausted
            ready && acceptOwner() -> {
                PushWakeDiagnostics.event(PushWakeEvent.OwnerAccepted)
                PushWakeDispatch.Owner
            }
            ready && highPriority && runCatching(startService).getOrDefault(false) -> {
                PushWakeDiagnostics.event(PushWakeEvent.ServiceAccepted)
                PushWakeDispatch.Service
            }
            schedule(highPriority) -> PushWakeDispatch.Scheduled
            else -> PushWakeDispatch.ScheduleFailed
        }
    }
}

/** Uses regular work on API 30 so recovery never needs another foreground-service exemption. */
internal fun mayExpeditePushWake(
    requested: Boolean,
    sdk: Int,
): Boolean = requested && sdk >= Build.VERSION_CODES.S

/** Resolves process-owned seams without constructing a second native runtime. */
internal fun WhiteNoiseApplication.pushWakeCoordinator(): PushWakeRecoveryCoordinator =
    PushWakeRecoveryCoordinator(
        store = PushTokenStore.create(this),
        acceptOwner = {
            withContext(Dispatchers.Main.immediate) { initializedAppState()?.acceptPushWakeRecovery() == true }
        },
        startService = { NotificationStreamForegroundService.start(this, ForegroundStartTrigger.PushWake) },
        schedule = { PushWakeRecoveryScheduler.schedule(this, it) },
    )
