package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.ManualDeviceFixture
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in matched lane timing — select variants with `-e throughput_variants reuse,per_input,...`. */
@RunWith(AndroidJUnit4::class)
@ManualDeviceFixture
class MediaWorkerThroughputDeviceTest {
    /** Drives every throughput scenario for each named variant, in the requested order. */
    @Test
    fun characterizeStillLaneThroughputAcrossVariants() {
        val requested =
            InstrumentationRegistry
                .getArguments()
                .getString("throughput_variants")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
        MediaWorkerThroughput.run(requested ?: listOf("reuse", "per_input", "per_input", "reuse"))
    }
}
