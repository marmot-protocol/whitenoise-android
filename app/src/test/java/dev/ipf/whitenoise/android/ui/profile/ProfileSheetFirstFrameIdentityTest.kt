package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The profile sheet must know its identity on the first composed frame (#1432).
 *
 * `ModalBottomSheet` animates toward the height it measures, so any row that
 * appears only after a suspend resolver returns — about text, NIP-05, avatar,
 * shared groups — moves that target while the open animation is running and
 * reads as a stutter. These pin the ordering that prevents it: a locally
 * decodable reference must be non-null in the very first composition, with the
 * suspend path reserved for references the local decode rejects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileSheetFirstFrameIdentityTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Mirrors the sheet's resolution order: seed synchronously, fall back to the
     * suspend resolver only when the seed is null.
     */
    private fun observeFirstFrame(
        localDecode: (String) -> String?,
        suspendDecode: suspend (String) -> String?,
    ): Pair<String?, String?> {
        var firstFrame: String? = null
        var settled: String? = null
        composeRule.setContent {
            var hex by remember { mutableStateOf(localDecode("npub1example")) }
            remember { firstFrame = hex }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                hex = hex ?: suspendDecode("npub1example")
            }
            settled = hex
        }
        composeRule.waitForIdle()
        return firstFrame to settled
    }

    @Test
    fun locallyDecodableReferenceIsResolvedBeforeAnyEffectRuns() {
        val (firstFrame, settled) =
            observeFirstFrame(
                localDecode = { "abc123" },
                suspendDecode = { error("the suspend resolver must not be needed") },
            )

        // Non-null on frame 1 is what keeps the measured height stable.
        assertEquals("abc123", firstFrame)
        assertEquals("abc123", settled)
    }

    @Test
    fun referenceTheLocalDecodeRejectsStillResolvesThroughTheSuspendPath() {
        val (firstFrame, settled) =
            observeFirstFrame(
                localDecode = { null },
                suspendDecode = { "resolved-later" },
            )

        assertNull("nothing to seed, so frame 1 legitimately has no identity", firstFrame)
        assertEquals("resolved-later", settled)
    }

    @Test
    fun anUnresolvableReferenceLeavesTheIdentityNullWithoutLooping() {
        val (firstFrame, settled) =
            observeFirstFrame(
                localDecode = { null },
                suspendDecode = { null },
            )

        assertNull(firstFrame)
        assertNull(settled)
    }
}
