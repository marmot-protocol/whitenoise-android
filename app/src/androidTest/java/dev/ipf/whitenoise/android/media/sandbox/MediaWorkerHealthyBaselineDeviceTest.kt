package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.ManualDeviceFixture
import org.junit.Test
import org.junit.runner.RunWith

/** Healthy timing is opt-in and separate from debug-only fault characterization. */
@RunWith(AndroidJUnit4::class)
@ManualDeviceFixture
class MediaWorkerHealthyBaselineDeviceTest {
    @Test
    fun characterizeHealthyDirectStillTransportWithFixedSyntheticCorpus() {
        MediaWorkerBaseline.runHealthy()
    }
}
