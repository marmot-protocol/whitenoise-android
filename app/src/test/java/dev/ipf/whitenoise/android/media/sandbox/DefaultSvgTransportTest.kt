package dev.ipf.whitenoise.android.media.sandbox

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.GroupSvgPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
     * Installs one task-owned image owner so both SVG operations reach the counted refusing context, regardless
     * of a process-owned image owner retained by an earlier application or fixture.
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
            withFreshInstalledImageOwner(context) {
                val source = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"/>""".toByteArray()
                val region = ViewerSvgRegion(0.0, 0.0, 24.0, 24.0)
                withContext(Dispatchers.IO) {
                    assertTrue("Decoder calls run off Main", Looper.myLooper() != Looper.getMainLooper())
                    assertNull(IsolatedMediaDecoder.svgInspect(ByteArray(GroupSvgPolicy.MAX_BYTES + 1)))
                    assertNull(IsolatedMediaDecoder.svgRegion(source, region, 4097))
                    assertNull(IsolatedMediaDecoder.svgRegion(source, region.copy(left = Double.NaN), 256))
                    assertEquals(0, bindings.get())
                    assertNull(IsolatedMediaDecoder.svgInspect(source))
                    assertEquals("Valid metadata reaches the real refused binding", 1, bindings.get())
                    assertNull(IsolatedMediaDecoder.svgRegion(source, ViewerSvgRegion(0.0, 0.0, 12.0, 24.0), 2803))
                    assertEquals("Valid vector pixels reach the same default transport", 2, bindings.get())
                }
            }
        }

    /** Isolates the normal installer from retained facade owners, then restores every field without closing them. */
    private suspend fun withFreshInstalledImageOwner(
        context: Context,
        block: suspend () -> Unit,
    ) {
        val decoder = IsolatedMediaDecoder
        val fields =
            listOf("transport", "mediaTransport", "installedContext", "imageOwner", "lastIsolation")
                .associateWith { name ->
                    decoder::class.java.getDeclaredField(name).apply { isAccessible = true }
                }
        val previous = fields.mapValues { (_, member) -> member.get(decoder) }
        try {
            // Keep prior owners referenced and untouched; this fixture owns only its replacement.
            fields.filterKeys { it != "lastIsolation" }.values.forEach { it.set(decoder, null) }
            decoder.install(context)
            val owner = fields.getValue("imageOwner").get(decoder) as IsolatedMediaTransport
            val ownerContext = owner::class.java.getDeclaredField("context").apply { isAccessible = true }
            assertSame(
                "The normal installer uses this fixture's owner",
                owner,
                fields.getValue("transport").get(decoder),
            )
            assertSame("The worker binds through the counted context", context, ownerContext.get(owner))
            assertSame(context, fields.getValue("installedContext").get(decoder))
            block()
        } finally {
            val owned = fields.getValue("imageOwner").get(decoder) as IsolatedMediaTransport?
            try {
                decoder.clear()
                owned?.close()
            } finally {
                fields.forEach { (name, member) -> member.set(decoder, previous[name]) }
            }
        }
    }
}
