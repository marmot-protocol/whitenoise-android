package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.ManualDeviceFixture
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in matched lane timing — select variants with `-e throughput_variants reuse,domain,domain,reuse`, any of
 * [ThroughputVariant]'s keys in any order. Without the argument it runs [ThroughputVariant.DEFAULT_ORDER].
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceFixture
class MediaWorkerThroughputDeviceTest {
    /** Drives every throughput scenario for each named variant, in the requested order. */
    @Test
    fun characterizeStillLaneThroughputAcrossVariants() {
        val requested = InstrumentationRegistry.getArguments().getString("throughput_variants")
        MediaWorkerThroughput.run(ThroughputVariant.parse(requested))
    }
}
