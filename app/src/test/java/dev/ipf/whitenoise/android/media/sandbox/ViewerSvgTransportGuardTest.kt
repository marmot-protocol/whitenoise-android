package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.GroupSvgPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** Exercises real transport admission with only the final platform binding refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ViewerSvgTransportGuardTest {
    @Test
    fun invalidVectorRequestsNeverReachBindingButValidSurroundingInputsDo() {
        var bindings = 0
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun bindIsolatedService(
                    service: Intent,
                    flags: Int,
                    instanceName: String,
                    executor: Executor,
                    conn: ServiceConnection,
                ): Boolean {
                    bindings++
                    return false
                }

                override fun unbindService(conn: ServiceConnection) = Unit
            }
        val transport = IsolatedMediaTransport(context)
        val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"/>""".toByteArray()
        val region = ViewerSvgRegion(0.0, 0.0, 24.0, 24.0)
        try {
            val oversized = ByteArray(GroupSvgPolicy.MAX_BYTES + 1)
            assertNull(transport.svgInspect(oversized))
            assertNull(transport.svgRegion(oversized, region, 256))
            assertNull(transport.svgRegion(bytes, region, 2049))
            assertNull(transport.svgRegion(bytes, region.copy(left = Double.NaN), 256))
            assertNull(transport.svgRegion(bytes, region.copy(right = 8193.0), 256))
            assertEquals(0, bindings)
            assertNull(transport.svgInspect(bytes))
            assertTrue(
                "Valid input must reach the refused platform boundary, not an earlier generic failure",
                bindings > 0,
            )
        } finally {
            transport.close()
        }
    }
}
