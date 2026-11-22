package dev.ipf.whitenoise.android.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The platform facts MDK needs before it starts a background backup: Wi-Fi that is not metered, and a charger. */
internal data class HistoryBackupFacts(
    val wifi: Boolean,
    val charging: Boolean,
)

/** Reads the facts from the app's default-network snapshot and the sticky battery broadcast. */
internal fun WhiteNoiseAppState.historyBackupFacts(): HistoryBackupFacts {
    val network = activeNetworkTypes()
    return HistoryBackupFacts(
        wifi = MediaAutoDownloadNetwork.WiFi in network && MediaAutoDownloadNetwork.Metered !in network,
        charging = appContext.isCharging(),
    )
}

/** Charging or full on external power, as WorkManager's charging constraint reads the battery. */
private fun Context.isCharging(): Boolean {
    // The sticky battery broadcast is read without registering a receiver.
    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
}

/**
 * Keeps the current MarmotKit runtime told whether background backups may run (#3655). MDK requires both facts
 * before it admits a background pass, forgets them when a new runtime opens, and settles an admitted pass when
 * either turns false. Each new runtime is told at once; the app's network listener and a power receiver report
 * changes as they happen. Reporting is the whole job here: MDK owns when a backup is due and what it writes.
 */
internal object HistoryBackupConditions {
    private const val TAG = "HistoryBackupConditions"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportMutex = Mutex()

    @Volatile private var runtime: MarmotInterface? = null

    @Volatile private var facts: () -> HistoryBackupFacts = { HistoryBackupFacts(wifi = false, charging = false) }

    @Volatile private var lastReported: Pair<MarmotInterface, HistoryBackupFacts>? = null
    private var listening = false

    /** Makes [marmot] the runtime that receives reports of [readFacts] and reports to it now. */
    fun attach(
        context: Context,
        marmot: MarmotInterface,
        readFacts: () -> HistoryBackupFacts,
    ) {
        facts = readFacts
        runtime = marmot
        runCatchingCancellable { listenForPower(context.applicationContext) }
            .onFailure { Log.w(TAG, "backup_power_listener_failed ${it.javaClass.simpleName}") }
        report()
    }

    /** Stops reporting to [marmot] once its runtime is cleared; a newer attached runtime is left alone. */
    fun detach(marmot: MarmotInterface) {
        if (runtime === marmot) runtime = null
    }

    /** Reports the current facts to the attached runtime in the background. */
    fun report() {
        scope.launch { runtime?.let { reportNow(it) } }
    }

    /**
     * Tells [marmot] the current facts and returns once MDK has them, skipping a repeat of its last report. False
     * when the report failed, so a caller about to run a pass knows MDK may still hold the closed default.
     */
    suspend fun reportNow(marmot: MarmotInterface): Boolean =
        reportMutex.withLock {
            if (runtime !== marmot) return@withLock false
            runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    val current = facts()
                    if (lastReported?.let { it.first === marmot && it.second == current } != true) {
                        // Until the setter succeeds, a later visit must report the facts again.
                        lastReported = null
                        marmot.setRuntimeBackupBackgroundConditions(current.wifi, current.charging)
                        lastReported = marmot to current
                    }
                }
            }.onFailure { Log.w(TAG, "backup_conditions_report_failed ${it.javaClass.simpleName}") }
                .isSuccess
        }

    @Synchronized
    private fun listenForPower(app: Context) {
        if (listening) return
        val power =
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                // Charging can stop and resume while the cable stays in.
                addAction(BatteryManager.ACTION_CHARGING)
                addAction(BatteryManager.ACTION_DISCHARGING)
            }
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = report()
            },
            power,
            Context.RECEIVER_NOT_EXPORTED,
        )
        listening = true
    }
}
