package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Navigation metadata belongs to the exact validated pixels, including after their LRU entry leaves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreparedAvatarClassificationTest {
    @Test
    fun exactPinsPreserveStaticAnimatedAndUnknownClassification() {
        listOf(false, true, null).forEach { classification ->
            assertEquals(classification, pins(classification).animationClassificationFor(asset))
        }
    }

    @Test
    fun fallbackRevisionsOtherSelectionsAndRemovedAssetsCannotBorrowStaticClassification() {
        val pins = pins(false)
        assertNull(pins.animationClassificationFor(asset.copy(contentRevision = 2uL)))
        assertNull(pins.animationClassificationFor(asset.copy(reference = "replacement")))
        assertNull(pins.animationClassificationFor(asset.copy(target = "another")))
        assertNull(pins.animationClassificationFor(asset.copy(availability = AvatarAvailabilityFfi.MISSING)))
        assertNull(pins.animationClassificationFor(null))
        assertNull(pins.copy(retained = emptyList()).animationClassificationFor(asset))
    }

    private fun pins(classification: Boolean?) =
        PreparedGroupAvatarPixels(
            "account",
            1,
            1L,
            emptyMap(),
            listOf(PreparedAvatarImage(asset, ImageBitmap(4, 4), classification)),
        )

    private val asset =
        AvatarAssetFfi(
            "target",
            "reference",
            AvatarAvailabilityFfi.READY,
            AvatarAcquisitionStateFfi.IDLE,
            1uL,
            100uL,
        )
}
