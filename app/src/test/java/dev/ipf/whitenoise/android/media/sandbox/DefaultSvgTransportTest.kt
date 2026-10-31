package dev.ipf.whitenoise.android.media.sandbox

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

/** Normal installation, real reusable guards; only the final Android binding is refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DefaultSvgTransportTest {
    @Test
    fun defaultInstallerRoutesBothSvgOperationsThroughReusableAdmission() =
        runBlocking {
            val bindings = AtomicInteger()
            val context =
                object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                    override fun getApplicationContext(): Context = this

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
                    "The production installer must retain its reusable policy",
                    field.get(IsolatedMediaDecoder) is ReusableMediaTransport,
                )
                val source = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"/>""".toByteArray()
                val region = ViewerSvgRegion(0.0, 0.0, 24.0, 24.0)
                withContext(Dispatchers.IO) {
                    assertNull(IsolatedMediaDecoder.svgInspect(ByteArray(GroupSvgPolicy.MAX_BYTES + 1)))
                    assertNull(IsolatedMediaDecoder.svgRegion(source, region, 2049))
                    assertNull(IsolatedMediaDecoder.svgRegion(source, region.copy(left = Double.NaN), 256))
                    assertEquals(0, bindings.get())
                    assertNull(IsolatedMediaDecoder.svgInspect(source))
                    assertEquals("Valid metadata reaches the real refused binding", 1, bindings.get())
                    assertNull(IsolatedMediaDecoder.svgRegion(source, region, 256))
                    assertEquals("Valid vector pixels reach the same default transport", 2, bindings.get())
                }
            } finally {
                IsolatedMediaDecoder.clear()
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            }
        }
}
