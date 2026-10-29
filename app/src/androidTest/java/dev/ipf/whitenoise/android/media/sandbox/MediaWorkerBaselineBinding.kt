package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * Read-only Binder lifetime witnesses for this test's transport, not a replacement admission policy.
 * Each generation records its kernel Binder death and when the parent sent it DECODE or RETIRE.
 */
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
        val observer = observe(conn, number, started, instanceName)
        synchronized(monitor) { bindings[conn] = observer }
        return super.bindIsolatedService(service, flags, instanceName, executor, observer)
    }

    private fun observe(
        target: ServiceConnection,
        generation: Int,
        started: Long,
        instanceName: String,
    ): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                val witness = Witness(generation, instanceName, service, elapsed)
                synchronized(monitor) { witnesses += witness }
                runCatching { service.linkToDeath(witness.death, 0) }
                    .onFailure { synchronized(monitor) { witness.registrationFailed = true } }
                target.onServiceConnected(name, RecordingBinder(service, witness::record))
            }

            override fun onServiceDisconnected(name: ComponentName) = target.onServiceDisconnected(name)

            override fun onBindingDied(name: ComponentName) = target.onBindingDied(name)

            override fun onNullBinding(name: ComponentName) = target.onNullBinding(name)
        }

    override fun unbindService(conn: ServiceConnection) {
        val observer = synchronized(monitor) { bindings.remove(conn) }
        super.unbindService(checkNotNull(observer))
    }

    /** Whether the exact Binder of the [generation]th binding attempt is still alive. */
    fun isEndpointAlive(generation: Int): Boolean =
        synchronized(monitor) {
            val endpoint = witnesses.single { it.generation == generation }
            endpoint.binder.isBinderAlive
        }

    /** Every endpoint's lifetime record, in connection order. */
    fun snapshot(): JSONObject =
        synchronized(monitor) {
            JSONObject()
                .put("binding_attempts", attempts)
                .put("endpoints", JSONArray(witnesses.map { it.snapshot() }))
                .put("process_count_inferred", false)
        }

    /** Endpoints that received at least one input, in the order their first input was dispatched. */
    fun inputEndpoints(): List<JSONObject> {
        val endpoints = snapshot().getJSONArray("endpoints")
        return (0 until endpoints.length())
            .map { endpoints.getJSONObject(it) }
            .filter { it.getInt("decodes") > 0 }
            .sortedBy { it.getLong("decode_elapsed_ns") }
    }

    /** Unlinks every witness death recipient once the transport under test is closed. */
    fun closeObservers() {
        synchronized(monitor) {
            witnesses.forEach { runCatching { it.binder.unlinkToDeath(it.death, 0) } }
        }
    }

    private inner class Witness(
        val generation: Int,
        val instanceName: String,
        val binder: IBinder,
        val connectNanos: Long,
    ) {
        var registrationFailed = false
        private var deathNanos: Long? = null
        private var decodeNanos: Long? = null
        private var retireNanos: Long? = null
        private var decodes = 0
        val death = IBinder.DeathRecipient { synchronized(monitor) { deathNanos = SystemClock.elapsedRealtimeNanos() } }

        /** Timestamps one parent message to this endpoint, before it is sent. */
        fun record(what: Int) {
            val now = SystemClock.elapsedRealtimeNanos()
            synchronized(monitor) {
                when (what) {
                    MediaDecodePolicy.DECODE -> {
                        decodes++
                        if (decodeNanos == null) decodeNanos = now
                    }
                    MediaDecodePolicy.RETIRE -> if (retireNanos == null) retireNanos = now
                }
            }
        }

        /** This endpoint's lifetime and message record. */
        fun snapshot(): JSONObject =
            JSONObject()
                .put("generation", generation)
                .put("instance", instanceName)
                .put("bind_to_callback_ns", connectNanos)
                .put("death_observed_elapsed_ns", deathNanos ?: JSONObject.NULL)
                .put("death_registration_failed", registrationFailed)
                .put("decodes", decodes)
                .put("decode_elapsed_ns", decodeNanos ?: JSONObject.NULL)
                .put("retire_elapsed_ns", retireNanos ?: JSONObject.NULL)
    }
}

/** Forwards every call to the worker's real Binder and reports each Messenger message's `what`. */
private class RecordingBinder(
    private val delegate: IBinder,
    private val record: (Int) -> Unit,
) : IBinder by delegate {
    override fun transact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code == IBinder.FIRST_CALL_TRANSACTION) {
            val position = data.dataPosition()
            runCatching {
                data.setDataPosition(0)
                data.enforceInterface("android.os.IMessenger")
                // A non-null Message starts with its `what` field.
                if (data.readInt() != 0) record(data.readInt())
            }
            data.setDataPosition(position)
        }
        return delegate.transact(code, data, reply, flags)
    }
}
