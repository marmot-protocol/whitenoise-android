package dev.ipf.whitenoise.android.media.sandbox

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.GroupSvgPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Normal installation and input guards; only the final Android service binding is refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DefaultSvgTransportTest {
    /**
     * Uses a plain application so the normal installer owns the refusing context, verifying both SVG operations
     * enter scoped admission without inheriting an application-created worker owner.
     */
    @Test
    fun defaultInstallerRoutesBothSvgOperationsThroughScopedAdmission() =
        runBlocking {
            val bindings = AtomicInteger()
            val context =
                object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                    override fun getApplicationContext(): Context = this

                    /**
                     * Counts attempts to enter the installed service transport and refuses binding without
                     * launching a worker.
                     */
                    override fun bindIsolatedService(
                        service: Intent,
                        flags: Int,
                        instanceName: String,
                        executor: Executor,
                        conn: ServiceConnection,
                    ): Boolean {
                        bindings.incrementAndGet()
                        return false
                    }

                    override fun unbindService(conn: ServiceConnection) = Unit
                }
            IsolatedMediaDecoder.install(context)
            try {
                val field = IsolatedMediaDecoder::class.java.getDeclaredField("transport").apply { isAccessible = true }
                assertTrue(
                    "The production installer must retain its scoped isolation policy",
                    field.get(IsolatedMediaDecoder) is IsolatedMediaTransport,
                )
                val source = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"/>""".toByteArray()
                val region = ViewerSvgRegion(0.0, 0.0, 24.0, 24.0)
                withContext(Dispatchers.IO) {
                    assertNull(IsolatedMediaDecoder.svgInspect(ByteArray(GroupSvgPolicy.MAX_BYTES + 1)))
                    assertNull(IsolatedMediaDecoder.svgRegion(source, region, 4097))
                    assertNull(IsolatedMediaDecoder.svgRegion(source, region.copy(left = Double.NaN), 256))
                    assertEquals(0, bindings.get())
                    assertNull(IsolatedMediaDecoder.svgInspect(source))
                    assertEquals("Valid metadata reaches the real refused binding", 1, bindings.get())
                    assertNull(IsolatedMediaDecoder.svgRegion(source, ViewerSvgRegion(0.0, 0.0, 12.0, 24.0), 2803))
                    assertEquals("Valid vector pixels reach the same default transport", 2, bindings.get())
                }
            } finally {
                IsolatedMediaDecoder.clear()
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            }
        }
}
