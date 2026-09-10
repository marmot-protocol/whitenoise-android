package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.DiagnosticsExporterStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductAnalyticsActivityFfi
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Observable projection of MDK's receipt; there is no Android-owned persistent consent flag. */
internal class UsageDiagnosticsController {
    var snapshot by mutableStateOf<UsageDiagnosticsSnapshot?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    val observations = ProductObservationGate()
    private val mutex = Mutex()
    private var runtime: MarmotInterface? = null
    private var revision = 0L
    private var foreground = false
    private var pendingChoice by mutableStateOf<Boolean?>(null)

    val requiresChoice: Boolean
        get() = snapshot?.settings?.decision == UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED
    val granted: Boolean
        get() = !failed && pendingChoice == null && snapshot?.settings?.decision == UsageDiagnosticsDecisionFfi.GRANTED

    /** Optimistic switch presentation never grants collection before native persistence succeeds. */
    val selected: Boolean
        get() = !failed && (pendingChoice ?: granted)

    /** Records the immediate Activity edge before asynchronous MDK work or runtime construction. */
    fun setForeground(value: Boolean) {
        foreground = value
        observations.reset(value && granted && snapshot?.status?.productAnalytics == DiagnosticsExporterStatusFfi.READY)
    }

    /** Resets stale UI/observation state before a replacement runtime can start. */
    fun bind(engine: MarmotInterface) {
        if (runtime === engine) return
        revision += 1
        runtime = engine
        snapshot = null
        pendingChoice = null
        failed = false
        observations.reset()
    }

    /** Refreshes exporter status without allowing a stale read to overwrite a newer user decision. */
    suspend fun refresh(engine: MarmotInterface) {
        bind(engine)
        if (failed && pendingChoice != null) return
        update(engine, null)
    }

    /** Explicit grant/revocation is serialized with reads; failed persistence never leaves an active host sink. */
    suspend fun choose(
        engine: MarmotInterface,
        enabled: Boolean,
    ): Boolean {
        bind(engine)
        revision += 1
        pendingChoice = enabled
        observations.reset()
        return update(engine, enabled)
    }

    /** Keeps a failed toggle's intended value until its retry succeeds or the runtime is replaced. */
    suspend fun retry(engine: MarmotInterface) {
        val choice = pendingChoice
        if (choice == null) refresh(engine) else choose(engine, choice)
    }

    /** Serializes native receipt access and publishes only the current runtime's completed result. */
    private suspend fun update(
        engine: MarmotInterface,
        choice: Boolean?,
    ): Boolean {
        val capturedRevision = revision
        return mutex.withLock {
            if (runtime !== engine || revision != capturedRevision) return@withLock false
            busy = true
            try {
                val result =
                    runCatchingCancellable {
                        withContext(Dispatchers.IO) {
                            if (choice == null) {
                                engine.usageDiagnosticsSnapshot()
                            } else {
                                engine.updateTelemetryConsent(choice)
                            }
                        }
                    }
                if (runtime !== engine || revision != capturedRevision) return@withLock false
                snapshot = result.getOrNull()
                failed = result.isFailure
                if (result.isSuccess) pendingChoice = null
                val ready = granted && snapshot?.status?.productAnalytics == DiagnosticsExporterStatusFfi.READY
                if (choice != null || observations.ticket() == null || !ready) observations.reset(ready && foreground)
                if (ready) {
                    runCatchingCancellable {
                        withContext(Dispatchers.IO) {
                            engine.setProductAnalyticsActivity(
                                foregroundActivity(),
                            )
                        }
                    }
                }
                result.isSuccess
            } finally {
                busy = false
            }
        }
    }

    /** Sends lifecycle edges off-main; MDK bounds its background flush and no Android queue is created. */
    suspend fun activity(
        engine: MarmotInterface,
        activity: ProductAnalyticsActivityFfi,
    ) {
        mutex.withLock {
            if (runtime !== engine || !granted) return
            if (snapshot?.status?.productAnalytics != DiagnosticsExporterStatusFfi.READY) return
            val currentActivity =
                if (activity == ProductAnalyticsActivityFfi.ACCOUNT_CHANGED) {
                    activity
                } else if (foreground) {
                    ProductAnalyticsActivityFfi.FOREGROUND
                } else {
                    ProductAnalyticsActivityFfi.BACKGROUND
                }
            runCatchingCancellable {
                withContext(Dispatchers.IO) { engine.setProductAnalyticsActivity(currentActivity) }
            }
        }
    }

    /** Resolves the latest Activity state, avoiding delayed foreground work after backgrounding. */
    private fun foregroundActivity(): ProductAnalyticsActivityFfi =
        if (foreground) ProductAnalyticsActivityFfi.FOREGROUND else ProductAnalyticsActivityFfi.BACKGROUND
}
