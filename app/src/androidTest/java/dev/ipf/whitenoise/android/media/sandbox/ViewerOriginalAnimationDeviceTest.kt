package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.media.AnimationSourceAdmission
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.admitAnimationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Source-sized, genuinely changing frames over the actual isolated viewer stream. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
class ViewerOriginalAnimationDeviceTest {
    @Test
    fun gifAndWebpRetainFineSourcePixelsBeyondPreviewResolution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        IsolatedMediaDecoder.install(instrumentation.targetContext)
        for ((format, bytes) in listOf(
            "GIF" to ViewerAnimationDetailFixtures.gif(),
            "WebP" to ViewerAnimationDetailFixtures.webp(),
        )) {
            assertTrue(admitAnimationSource(bytes) is AnimationSourceAdmission.Admitted)
            val drawable =
                requireNotNull(
                    MediaPipeline.decodeViewerAnimatedDrawable(bytes),
                ) as IsolatedAnimationDrawable
            val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            try {
                assertEquals(ViewerAnimationDetailFixtures.WIDTH, drawable.intrinsicWidth)
                assertEquals(ViewerAnimationDetailFixtures.HEIGHT, drawable.intrinsicHeight)
                assertTrue(bitmap.allocationByteCount <= 16 * 1024 * 1024)
                val worker = requireNotNull(IsolatedMediaDecoder.lastIsolation)
                assertTrue(worker.uid != Process.myUid() && worker.isolated)
                assertFalse(worker.internetGranted)
                assertFalse(worker.runtimeInitialized)
                instrumentation.runOnMainSync {
                    drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                    drawable.start()
                }
                val colors = mutableSetOf<Int>()
                val deadline = SystemClock.elapsedRealtime() + 2500
                while (colors.size < 2 && SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync {
                        drawable.draw(Canvas(bitmap))
                        colors += bitmap.getPixel(ViewerAnimationDetailFixtures.X, ViewerAnimationDetailFixtures.Y)
                    }
                    SystemClock.sleep(20)
                }
                assertTrue(
                    "$format original source pixel must change between red and blue: $colors",
                    colors.any { Color.red(it) > 240 } && colors.any { Color.blue(it) > 240 },
                )
                var pausedSequence = 0L
                instrumentation.runOnMainSync {
                    drawable.stop()
                    pausedSequence = drawable.presentedFrame
                }
                SystemClock.sleep(200)
                instrumentation.runOnMainSync { assertEquals(pausedSequence, drawable.presentedFrame) }
                val pss = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
                println(
                    "VIEWER_ANIMATION_DETAIL format=$format width=${bitmap.width} height=${bitmap.height} " +
                        "allocationBytes=${bitmap.allocationByteCount} sampledPssKiB=$pss",
                )
            } finally {
                instrumentation.runOnMainSync { drawable.close() }
                bitmap.recycle()
            }
        }
    }
}
