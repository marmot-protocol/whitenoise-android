package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewerAnimationRequestTest {
    /** Ordinary OPEN cannot smuggle dimensions, and viewer OPEN requires an exact bounded canvas. */
    @Test
    fun explicitOriginalOpenCannotBypassCanvasAdmission() {
        assertNull(readViewerAnimationOutput(Bundle()))
        val valid =
            Bundle().apply {
                putBoolean("viewerOriginal", true)
                putInt("viewerWidth", 4096)
                putInt("viewerHeight", 1024)
            }
        assertEquals(16 * 1024 * 1024, requireNotNull(readViewerAnimationOutput(valid)).capacityBytes)
        for ((width, height) in listOf(0 to 1, 4096 to 4096, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val request =
                Bundle(valid).apply {
                    putInt("viewerWidth", width)
                    putInt("viewerHeight", height)
                }
            assertThrows(IllegalArgumentException::class.java) { readViewerAnimationOutput(request) }
        }
        for (name in listOf("viewerWidth", "viewerHeight")) {
            assertThrows(IllegalArgumentException::class.java) {
                readViewerAnimationOutput(Bundle().apply { putInt(name, 1) })
            }
        }
    }
}
