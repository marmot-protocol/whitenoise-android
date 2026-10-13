package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executor

/** Inject framework lifecycle callbacks, while keeping the production binding and request logic intact. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaBindingRecoveryTest {
    @Test
    fun startupDeathRebindsAndRetiredCallbacksCannotReplaceTheNewRemote() {
        val context = BindingFixture(ApplicationProvider.getApplicationContext())
        val transport = IsolatedMediaTransport(context)
        try {
            assertNull(transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(1, context.bindings.size)
            assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(2, context.bindings.size)
            assertEquals(1, context.current.requests)
            val retired = ReplyingBinder()
            context.bindings.first().onServiceConnected(context.component, retired)
            assertNull(transport.decode(byteArrayOf(3), 1, false, MediaDecodePolicy.DECODE))
            assertEquals(2, context.bindings.size)
            assertEquals(2, context.current.requests)
            assertEquals(0, retired.requests)
        } finally {
            transport.close()
        }
    }

    @Test
    fun deathBeforeOrAfterRetirementNeverPermanentlyPoisonsAdmission() {
        for (deathFirst in listOf(true, false)) {
            val context = BindingFixture(ApplicationProvider.getApplicationContext())
            val transport = IsolatedMediaTransport(context)
            try {
                assertNull(transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DECODE))
                assertNull(transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DECODE))
                val connection = context.bindings.last()
                if (deathFirst) connection.onServiceDisconnected(context.component)
                val retire = IsolatedMediaTransport::class.java.getDeclaredMethod("retireCurrentWorker")
                retire.isAccessible = true
                retire.invoke(transport)
                if (!deathFirst) connection.onServiceDisconnected(context.component)
                assertNull(transport.decode(byteArrayOf(3), 1, false, MediaDecodePolicy.DECODE))
                assertEquals(3, context.bindings.size)
                assertEquals(2, context.current.requests)
            } finally {
                transport.close()
            }
        }
    }

    private class BindingFixture(
        base: Context,
    ) : ContextWrapper(base) {
        val bindings = mutableListOf<ServiceConnection>()
        val component = ComponentName(base, MediaDecodeService::class.java)
        val current = ReplyingBinder()

        override fun bindIsolatedService(
            service: Intent,
            flags: Int,
            instanceName: String,
            executor: Executor,
            conn: ServiceConnection,
        ): Boolean {
            bindings += conn
            if (bindings.size == 1) conn.onBindingDied(component) else conn.onServiceConnected(component, current)
            return true
        }

        override fun unbindService(conn: ServiceConnection) = Unit
    }

    /** Dispatch/reply capability fixture. Same-UID replies deliberately cannot attest an isolated worker. */
    private class ReplyingBinder : Binder() {
        var requests = 0

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface("android.os.IMessenger")
            if (data.readInt() != 0) {
                val message = Message.CREATOR.createFromParcel(data)
                if (message.what == MediaDecodePolicy.DECODE) {
                    requests += 1
                    sendMediaDecodeReply(message.replyTo.binder, message.arg1, Bundle())
                }
            }
            return true
        }
    }
}
