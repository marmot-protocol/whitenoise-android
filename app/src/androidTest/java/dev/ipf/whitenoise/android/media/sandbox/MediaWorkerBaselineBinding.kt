package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/** Read-only Binder lifetime witnesses for this test's transport, not a replacement admission policy. */
internal class MediaWorkerBaselineBinding(
    base: Context,
) : ContextWrapper(base) {
    private val monitor = Any()
    private val bindings = mutableMapOf<ServiceConnection, ServiceConnection>()
    private val witnesses = mutableListOf<Witness>()
    private var attempts = 0

    override fun bindIsolatedService(
        service: Intent,
        flags: Int,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection,
    ): Boolean {
        val number = synchronized(monitor) { ++attempts }
        val started = SystemClock.elapsedRealtimeNanos()
        val observer = observe(conn, number, started)
        synchronized(monitor) { bindings[conn] = observer }
        return super.bindIsolatedService(service, flags, instanceName, executor, observer)
    }

    private fun observe(
        target: ServiceConnection,
        generation: Int,
        started: Long,
    ): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                val witness = Witness(generation, service, SystemClock.elapsedRealtimeNanos() - started)
                synchronized(monitor) { witnesses += witness }
                runCatching { service.linkToDeath(witness.death, 0) }
                    .onFailure { synchronized(monitor) { witness.registrationFailed = true } }
                target.onServiceConnected(name, service)
            }

            override fun onServiceDisconnected(name: ComponentName) = target.onServiceDisconnected(name)

            override fun onBindingDied(name: ComponentName) = target.onBindingDied(name)

            override fun onNullBinding(name: ComponentName) = target.onNullBinding(name)
        }

    override fun unbindService(conn: ServiceConnection) {
        val observer = synchronized(monitor) { bindings.remove(conn) }
        super.unbindService(checkNotNull(observer))
    }

    fun snapshot(): JSONObject =
        synchronized(monitor) {
            JSONObject()
                .put("binding_attempts", attempts)
                .put("endpoints", JSONArray(witnesses.map { it.snapshot() }))
                .put("process_count_inferred", false)
        }

    fun closeObservers() {
        synchronized(monitor) {
            witnesses.forEach { runCatching { it.binder.unlinkToDeath(it.death, 0) } }
        }
    }

    private inner class Witness(
        val generation: Int,
        val binder: IBinder,
        val connectNanos: Long,
    ) {
        var registrationFailed = false
        private var deathNanos: Long? = null
        val death = IBinder.DeathRecipient { synchronized(monitor) { deathNanos = SystemClock.elapsedRealtimeNanos() } }

        fun snapshot(): JSONObject =
            JSONObject()
                .put("generation", generation)
                .put("bind_to_callback_ns", connectNanos)
                .put("death_observed_elapsed_ns", deathNanos ?: JSONObject.NULL)
                .put("death_registration_failed", registrationFailed)
    }
}
