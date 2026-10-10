package dev.ipf.whitenoise.android.ui.qr

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrAnalysisResolutionTest {
    @Test
    fun analysisFiltersOversizedFramesWhilePreservingCameraOrder() {
        val direct = Executor { it.run() }
        val analysis = createQrAnalysis(direct, direct, AtomicBoolean(false)) { }
        val sizes =
            listOf(
                Size(4096, 2160),
                Size(2048, 2048),
                Size(1920, 1080),
                Size(640, 480),
                Size(Int.MAX_VALUE, 2),
            )
        val expected = sizes.subList(1, 4)
        val filter = requireNotNull(requireNotNull(analysis.resolutionSelector).resolutionFilter)
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals(expected, filter.filter(sizes, rotation))
        }
        assertEquals(emptyList<Size>(), filter.filter(listOf(sizes.first()), 0))
        analysis.clearAnalyzer()
    }
}
