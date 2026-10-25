package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed-corpus characterization belongs to the focused containment campaign, outside PR smoke. */
@RunWith(AndroidJUnit4::class)
@MediaContainmentAcceptance
class MediaWorkerBaselineDeviceTest {
    @Test
    fun characterizeDirectStillTransportWithFixedSyntheticCorpus() {
        MediaWorkerBaseline.run()
    }
}
