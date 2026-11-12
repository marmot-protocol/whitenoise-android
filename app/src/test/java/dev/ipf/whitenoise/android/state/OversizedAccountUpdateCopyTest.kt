package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EventSizeClassFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.OversizedArtifactFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.blockOutcomeMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class OversizedAccountUpdateCopyTest {
    private val accountUpdate =
        MarmotKitException.EventTooLarge(OversizedArtifactFfi.OTHER, EventSizeClassFfi.OVER4_MIB)
    private val message =
        MarmotKitException.EventTooLarge(OversizedArtifactFfi.APPLICATION_MESSAGE, EventSizeClassFfi.OVER4_MIB)

    /** A profile, relay list or KeyPackage refused for size says so instead of offering a retry. */
    @Test
    fun oversizedAccountUpdateReplacesTheGenericRetryDetail() {
        assertEquals(
            AppText.Resource(R.string.error_update_too_large),
            failureDetail(accountUpdate, AppText.Resource(R.string.error_try_again)),
        )
    }

    /** A caller's specific detail and other oversized events keep their own copy. */
    @Test
    fun specificDetailsAndOtherArtifactsAreKept() {
        val specific = AppText.Resource(R.string.error_storage_full)
        assertEquals(specific, failureDetail(accountUpdate, specific))
        assertEquals(
            AppText.Resource(R.string.error_try_again),
            failureDetail(message, AppText.Resource(R.string.error_try_again)),
        )
    }

    /** A block list refused for size gets its own copy rather than the generic block failure. */
    @Test
    fun oversizedBlockListSaysTheUpdateWasTooLarge() {
        assertEquals(R.string.error_update_too_large, blockOutcomeMessage(BlockOutcome.TooLarge))
        assertEquals(R.string.block_change_failed, blockOutcomeMessage(BlockOutcome.Failed))
    }
}
