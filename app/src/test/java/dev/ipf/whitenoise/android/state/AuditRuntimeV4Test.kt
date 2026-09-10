package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.AuditLogTrackerConfigV4Ffi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.BuildConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.lang.reflect.Proxy

/** Exercises the actual host configuration at the versioned native audit boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditRuntimeV4Test {
    /** The system model, platform, and app version are the only host-supplied source metadata. */
    @Test
    fun auditSourceUsesSystemHardwareModel() =
        runTest {
            ShadowBuild.setModel("  Synthetic Model  ")
            val config = captureConfiguration()
            assertEquals("Synthetic Model", config.source.hardwareModel)
            assertEquals("android", config.source.platform)
            assertEquals(BuildConfig.VERSION_NAME, config.source.appVersion)
        }

    /** Missing model information stays absent rather than falling back to a device name or serial. */
    @Test
    fun blankSystemHardwareModelIsOmitted() =
        runTest {
            ShadowBuild.setModel("   ")
            assertNull(captureConfiguration().source.hardwareModel)
        }

    /** Captures the real setter argument and permits only the existing recorder-enable sequence. */
    private suspend fun captureConfiguration(): AuditLogTrackerConfigV4Ffi {
        var config: AuditLogTrackerConfigV4Ffi? = null
        val type = MarmotInterface::class.java
        val marmot =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
                when (method.name) {
                    "setAuditLogTrackerConfig" -> {
                        config = arguments!![0] as AuditLogTrackerConfigV4Ffi
                        config
                    }
                    "auditLogSettings" -> AuditLogSettingsFfi(enabled = false)
                    "setAuditLogSettings" -> arguments!!.first()
                    else -> error("Unexpected native call: ${method.name}")
                }
            } as MarmotInterface
        marmot.configureAuditRuntime()
        return requireNotNull(config)
    }
}
