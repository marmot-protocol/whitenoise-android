package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A durable avatar refreshed to a new content revision of the same reference keeps its known pixels until the
 * replacement is ready, while removal, invalidation, another reference or target and another account never inherit
 * them (#3284).
 * The fixture has no native runtime, so an uncached revision's read fails and stands in for a slow or failed refresh.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DurableAvatarRefreshRetentionTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val first = ImageBitmap(4, 4)
    private val second = ImageBitmap(4, 4)

    /** Leaves no decoded pixels behind for other fixtures. */
    @After fun clearLoader() = AvatarImageLoader.clear()

    /** Recomposition after a trim keeps the current surface's pixels and still rejects another account. */
    @Test
    fun memoryTrimPreservesDisplayedPixelsWithoutWeakeningAccountIsolation() {
        val initial = asset(revision = 1uL)
        val shown = mount(initial)
        rule.runOnIdle {
            AvatarImageLoader.trimMemory(dev.ipf.whitenoise.android.media.PresentationMemoryTrim.EMPTY)
            assertNull(AvatarImageLoader.peek(requireNotNull(initial.cacheKey(ACCOUNT_REF))))
            shown.asset = asset(revision = 2uL)
        }
        rule.waitForIdle()
        assertSame(first, shown.image)
        shown.accountRef = OTHER_ACCOUNT_REF
        rule.waitForIdle()
        assertNull(shown.image)
    }

    /** A same-reference revision whose pixels are not ready yet keeps the previous pixels instead of blanking. */
    @Test
    fun sameTargetRevisionKeepsKnownPixelsWhileItsReadIsPending() {
        val shown = mount(asset(revision = 1uL))
        assertSame(first, shown.image)

        shown.asset = asset(revision = 2uL)
        rule.waitForIdle()

        assertSame(first, shown.image)
    }

    /** A new reference is a different stored picture, so it never shows the previous reference's pixels. */
    @Test
    fun newReferenceDoesNotInheritPixels() {
        val shown = mount(asset(revision = 1uL))

        shown.asset = asset(revision = 2uL, reference = "ref-2")
        rule.waitForIdle()

        assertNull(shown.image)
    }

    /** Once the refreshed revision's pixels are available they replace the previous ones in place. */
    @Test
    fun readyReplacementSwapsInPlace() {
        val shown = mount(asset(revision = 1uL))
        val refreshed = asset(revision = 2uL)
        AvatarImageLoader.putCached(requireNotNull(refreshed.cacheKey(ACCOUNT_REF)), second)

        shown.asset = refreshed
        rule.waitForIdle()

        assertSame(second, shown.image)
    }

    /** A different target is a different picture and never shows the previous one. */
    @Test
    fun anotherTargetDoesNotInheritPixels() {
        val shown = mount(asset(revision = 1uL))

        shown.asset = asset(revision = 2uL, target = "target-2")
        rule.waitForIdle()

        assertNull(shown.image)
    }

    /** Invalidated bytes blank the avatar, and a later uncached revision cannot revive the old pixels. */
    @Test
    fun invalidationClearsKnownPixels() {
        val shown = mount(asset(revision = 1uL))

        shown.asset = asset(revision = 2uL, availability = AvatarAvailabilityFfi.INVALIDATED)
        rule.waitForIdle()
        assertNull(shown.image)

        shown.asset = asset(revision = 3uL)
        rule.waitForIdle()
        assertNull(shown.image)
    }

    /** An explicitly removed picture blanks the avatar. */
    @Test
    fun removalClearsKnownPixels() {
        val shown = mount(asset(revision = 1uL))

        shown.asset = null
        rule.waitForIdle()

        assertNull(shown.image)
    }

    /** Another account never inherits this account's pixels, even for the same target. */
    @Test
    fun anotherAccountDoesNotInheritPixels() {
        val shown = mount(asset(revision = 1uL))

        shown.accountRef = OTHER_ACCOUNT_REF
        rule.waitForIdle()

        assertNull(shown.image)
    }

    /** The state a mounted avatar reads from and the pixels it last returned. */
    private class Shown(
        asset: AvatarAssetFfi?,
    ) {
        var asset by mutableStateOf(asset)
        var accountRef by mutableStateOf(ACCOUNT_REF)
        var image: ImageBitmap? = null
    }

    /** Seeds [initial]'s pixels and composes [rememberDurableAvatar] over a mutable asset and account. */
    private fun mount(initial: AvatarAssetFfi): Shown {
        AvatarImageLoader.putCached(requireNotNull(initial.cacheKey(ACCOUNT_REF)), first)
        val shown = Shown(initial)
        val appState = appState()
        rule.setContent { shown.image = rememberDurableAvatar(appState, shown.asset, shown.accountRef) }
        rule.waitForIdle()
        return shown
    }

    /** A durable avatar asset for one target, reference and content revision. */
    private fun asset(
        revision: ULong,
        reference: String = "ref-1",
        target: String = "target-1",
        availability: AvatarAvailabilityFfi = AvatarAvailabilityFfi.READY,
    ) = AvatarAssetFfi(
        target = target,
        reference = reference,
        availability = availability,
        acquisition = AvatarAcquisitionStateFfi.IDLE,
        contentRevision = revision,
        byteCount = 1_024uL,
    )

    /** An app state with one running account and no native runtime. */
    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    /** Draft storage that holds nothing. */
    private object EmptyDraftPersistence : DraftPersistence {
        /** Returns no saved drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards the draft. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        const val OTHER_ACCOUNT_REF = "work"
        val ACCOUNT_ID = "01".repeat(32)
    }
}
