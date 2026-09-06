package dev.ipf.whitenoise.android.state

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.core.HostSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in platform DNS coverage; no account is opened and no relay event is published or deleted. */
@RunWith(AndroidJUnit4::class)
class KeyPackageDeletionDnsDeviceTest {
    /** Exercises the production adapter and its real platform callbacks within the deletion deadline. */
    @Test
    fun publicSourceResolvesThroughProductionAdapter() =
        runBlocking {
            val host = InstrumentationRegistry.getArguments().getString("keyPackageDeletionDnsHost")
            assumeTrue("Provide a public DNS fixture hostname to run the network probe", !host.isNullOrBlank())
            val resolved =
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(KEY_PACKAGE_DELETION_DNS_HOST_TIMEOUT_MS) {
                        resolveKeyPackageDeletionHost(requireNotNull(host))
                    }
                }

            assertNotNull("Production DNS adapter did not return an answer within the deletion deadline", resolved)
            assertFalse("Production DNS adapter returned no addresses", resolved.isNullOrEmpty())
            assertFalse(
                "DNS fixture must resolve only to public addresses",
                resolved!!.any(HostSafety::isPrivateOrLoopbackAddress),
            )
        }
}
