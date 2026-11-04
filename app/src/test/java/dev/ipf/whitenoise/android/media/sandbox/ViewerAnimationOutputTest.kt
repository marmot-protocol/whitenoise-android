package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.media.AnimationSourceAdmission
import dev.ipf.whitenoise.android.media.AnimationSourceKind
import dev.ipf.whitenoise.android.media.AnimationSourceRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ViewerAnimationOutputTest {
    /** The largest admitted rectangle needs 16 MiB, never a 4096-square 64 MiB frame. */
    @Test
    fun originalCanvasCapacityIsExactAndOverflowSafe() {
        val output = requireNotNull(ViewerAnimationOutput.fromDimensions(4096, 1024))
        assertEquals(16 * 1024 * 1024, output.capacityBytes)
        assertEquals(64, requireNotNull(ViewerAnimationOutput.fromDimensions(8, 2)).capacityBytes)
        for ((width, height) in listOf(0 to 1, 1 to -1, 4097 to 1, 4096 to 4096, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertNull(ViewerAnimationOutput.fromDimensions(width, height))
        }
        assertThrows(IllegalArgumentException::class.java) { output.copy(width = Int.MAX_VALUE) }
    }

    /** Only content-admitted animation canvases can negotiate original-size output. */
    @Test
    fun refusalAndStillSourcesCannotNegotiateViewerAnimation() {
        assertNull(ViewerAnimationOutput.fromAdmission(AnimationSourceAdmission.NotAnimation))
        val refused = AnimationSourceAdmission.Refused(AnimationSourceRefusal.CanvasLimit)
        assertNull(ViewerAnimationOutput.fromAdmission(refused))
        val source = AnimationSourceAdmission.Admitted(AnimationSourceKind.Webp, 4096, 1024, 2)
        assertEquals(ViewerAnimationOutput.fromDimensions(4096, 1024), ViewerAnimationOutput.fromAdmission(source))
    }

    /** Frame capacity and exact negotiated dimensions are checked before host pixel allocation. */
    @Test
    fun sessionFrameAdmissionRejectsDimensionAndCapacitySubstitution() {
        val output = requireNotNull(ViewerAnimationOutput.fromDimensions(4096, 1024))
        assertEquals(output.capacityBytes, animationFrameByteCount(4096, 1024, 4096, output.capacityBytes, output))
        assertNull(animationFrameByteCount(1024, 4096, 4096, output.capacityBytes, output))
        assertNull(animationFrameByteCount(4096, 1024, 4096, output.capacityBytes - 1, output))
        assertNull(animationFrameByteCount(Int.MAX_VALUE, 1, 4096, output.capacityBytes, output))
        assertNull(animationFrameByteCount(4096, 4096, 4096, output.capacityBytes, output))
    }

    /** The globally parseable viewer envelope never enlarges an ordinary preview session. */
    @Test
    fun ordinaryPreviewSessionsKeepTheirExisting1080Limit() {
        val capacity = requireNotNull(MediaDecodePolicy.outputCapacity(AnimationFrameProtocol.MAX_EDGE))
        assertEquals(4 * 1080 * 1080, animationFrameByteCount(1080, 1080, 1080, capacity, null))
        assertNull(animationFrameByteCount(1081, 1, 1080, capacity, null))
        assertNull(animationFrameByteCount(4096, 1024, 4096, 16 * 1024 * 1024, null))
    }
}
