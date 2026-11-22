package dev.ipf.whitenoise.android.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The platform facts MDK needs before it starts a background backup: an unmetered network and a charger. */
internal data class HistoryBackupFacts(
    val wifi: Boolean,
    val charging: Boolean,
)

/** Reads the current facts. Unmetered stands in for Wi-Fi, matching WorkManager's `UNMETERED`. */
internal fun Context.currentHistoryBackupFacts(): HistoryBackupFacts {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
    return HistoryBackupFacts(
        wifi = capabilities.isUnmeteredInternet(),
        charging = isCharging(),
    )
}

private fun NetworkCapabilities?.isUnmeteredInternet(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

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
 * either turns false. Each new runtime is told at once; changes in network or power are reported as they happen.
 * Reporting is the whole job here: MDK owns when a backup is due and what it writes.
 */
internal object HistoryBackupConditions {
    private const val TAG = "HistoryBackupConditions"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportMutex = Mutex()

    @Volatile private var runtime: MarmotInterface? = null

    @Volatile private var lastReported: Pair<MarmotInterface, HistoryBackupFacts>? = null
    private var listening = false

    /** Makes [marmot] the runtime that receives reports and reports the current facts to it. */
    fun attach(
        context: Context,
        marmot: MarmotInterface,
    ) {
        val app = context.applicationContext
        runtime = marmot
        startListening(app)
        report(app)
    }

    /** Reports the current facts to the attached runtime in the background. */
    fun report(context: Context) {
        val app = context.applicationContext
        scope.launch { runtime?.let { reportNow(app, it) } }
    }

    /**
     * Tells [marmot] the current facts and returns once MDK has them, skipping a repeat of its last report. False
     * when the report failed, so a caller about to run a pass knows MDK may still hold the closed default.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun reportNow(
        context: Context,
        marmot: MarmotInterface,
    ): Boolean =
        reportMutex.withLock {
            val facts = withContext(Dispatchers.IO) { context.applicationContext.currentHistoryBackupFacts() }
            if (lastReported?.let { it.first === marmot && it.second == facts } == true) return@withLock true
            try {
                // Unknown until the setter returns: a cancelled report must not let a later one be skipped.
                lastReported = null
                withContext(Dispatchers.IO) { marmot.setRuntimeBackupBackgroundConditions(facts.wifi, facts.charging) }
                lastReported = marmot to facts
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A closed runtime is replaced by a new one, which is told on attach.
                Log.w(TAG, "backup_conditions_report_failed ${error.javaClass.simpleName}")
                false
            }
        }

    @Synchronized
    private fun startListening(app: Context) {
        if (listening) return
        listening = true
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = report(app)

                override fun onLost(network: Network) = report(app)
            },
        )
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
                ) = report(app)
            },
            power,
            Context.RECEIVER_NOT_EXPORTED,
        )
    }
}
