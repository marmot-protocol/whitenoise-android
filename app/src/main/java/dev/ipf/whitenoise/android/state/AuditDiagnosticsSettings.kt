package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AuditDeliveryStatusV5Ffi
import dev.ipf.marmotkit.AuditLogRetentionFfi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A device-wide view of MDK's delivery records, with no profile identifiers exposed to Settings. */
internal data class AuditDeliverySummary(
    val state: AuditDeliveryState,
    val httpStatus: Int? = null,
    val lastSuccessAtMs: Long? = null,
    val waitingBytes: Long = 0,
)

internal enum class AuditDeliveryState {
    OFF,
    UNCONFIGURED,
    ACTIVE,
    RETRYING,
    PAUSED,
}

/** Any configured profile's pause takes precedence over retries or successful checks. */
internal fun summarizeAuditDelivery(statuses: List<AuditDeliveryStatusV5Ffi>): AuditDeliverySummary {
    val configured = statuses.filter { it.destinationConfigured }
    if (configured.isEmpty()) return AuditDeliverySummary(AuditDeliveryState.UNCONFIGURED)
    val paused = configured.filter { it.paused }
    val state =
        when {
            paused.isNotEmpty() -> AuditDeliveryState.PAUSED
            configured.any { it.consecutiveFailures > 0uL } -> AuditDeliveryState.RETRYING
            else -> AuditDeliveryState.ACTIVE
        }
    return AuditDeliverySummary(
        state = state,
        httpStatus = paused.mapNotNull { it.lastFailure?.httpStatus?.toInt() }.distinct().singleOrNull(),
        lastSuccessAtMs = configured.mapNotNull { it.lastSuccessAtMs }.maxOrNull()?.coerceAtMost(Long.MAX_VALUE.toULong())?.toLong(),
        waitingBytes =
            configured.fold(0L) { total, status ->
                val bytes = status.backlog?.undeliveredBytes ?: 0uL
                total + bytes.coerceAtMost((Long.MAX_VALUE - total).toULong()).toLong()
            },
    )
}

private val auditRetentionAgePresets = setOf(1u, 3u, 7u, 14u, 30u)

/** The Apple clients' age presets, including a valid custom value already stored by MDK. */
internal fun auditRetentionChoices(current: UInt): List<UInt> =
    (auditRetentionAgePresets + current.coerceIn(auditRetentionAgePresets.first(), auditRetentionAgePresets.last())).sorted()

/** Screen state for one runtime generation. Reads serialize with writes for each independent setting. */
internal class AuditDiagnosticsSettings(
    private val loadRetention: suspend () -> AuditLogRetentionFfi,
    private val saveRetention: suspend (UInt, Boolean) -> AuditLogRetentionFfi,
    private val loadDelivery: suspend () -> AuditDeliverySummary,
    private val retryDelivery: suspend () -> Unit,
) {
    var retention by mutableStateOf<Result<AuditLogRetentionFfi>?>(null)
        private set
    var delivery by mutableStateOf<Result<AuditDeliverySummary>?>(null)
        private set
    var savingRetention by mutableStateOf(false)
        private set
    var resumingDelivery by mutableStateOf(false)
        private set
    var retentionSaveFailed by mutableStateOf(false)
        private set
    var deliveryResumeFailed by mutableStateOf(false)
        private set

    private val retentionLock = Mutex()
    private val deliveryLock = Mutex()

    /** Refreshes the local policy without modifying it. */
    suspend fun refreshRetention() =
        retentionLock.withLock {
            retention = runCatchingCancellable { loadRetention() }
        }

    /** Updates the displayed value only after MDK returns the stored policy. */
    suspend fun chooseRetention(
        days: UInt,
        confirmedShortening: Boolean,
    ) {
        if (savingRetention) return
        savingRetention = true
        retentionSaveFailed = false
        try {
            retentionLock.withLock {
                runCatchingCancellable { saveRetention(days, confirmedShortening) }
                    .onSuccess { retention = Result.success(it) }
                    .onFailure {
                        retentionSaveFailed = true
                        retention = runCatchingCancellable { loadRetention() }
                    }
            }
        } finally {
            savingRetention = false
        }
    }

    /** Reads only local delivery records. */
    suspend fun refreshDelivery() =
        deliveryLock.withLock {
            deliveryResumeFailed = false
            delivery = runCatchingCancellable { loadDelivery() }
        }

    /** Invokes MDK's explicit retry once and rereads the authoritative delivery state. */
    suspend fun resumeDelivery() {
        if (resumingDelivery) return
        resumingDelivery = true
        deliveryResumeFailed = false
        try {
            deliveryLock.withLock {
                if (delivery?.getOrNull()?.state == AuditDeliveryState.PAUSED) {
                    deliveryResumeFailed = runCatchingCancellable { retryDelivery() }.isFailure
                    delivery = runCatchingCancellable { loadDelivery() }
                }
            }
        } finally {
            resumingDelivery = false
        }
    }
}
