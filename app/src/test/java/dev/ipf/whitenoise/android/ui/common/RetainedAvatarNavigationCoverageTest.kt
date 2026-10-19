package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DestructiveAccountWipeRuntimeState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.ui.navigation.ReturnFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Retained navigation pixels are bounded, available at first commit, and owned by one live session. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RetainedAvatarNavigationCoverageTest {
    @get:Rule val rule = createComposeRule()
    private val state = ReturnFixture().appState
    private val image = ImageBitmap(4, 4)
    private val assets = (0 until 40).map(::asset)
    private var account by mutableStateOf<String?>(ACCOUNT)
    private var surface by mutableStateOf(0)
    private var mounted by mutableStateOf(true)
    private lateinit var owner: RetainedGroupAvatarPreparation

    /** Removes only synthetic process pixels after each fixture. */
    @After fun clearPixels() = AvatarImageLoader.clear()

    /** A scrolled list remount sees its previous bounded window before the new LazyColumn has a layout. */
    @Test
    fun scrolledViewportHasKnownPixelsOnEveryReturningCommitAfterEviction() {
        seed(assets)
        val commits = mutableListOf<ImageBitmap?>()
        rule.setContent {
            owner = rememberRetainedGroupAvatarPreparation(state, account, surface)
            if (mounted) {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = 20)
                PreparedVisibleGroupAvatarContent(
                    appState = state,
                    rows = assets,
                    listState = listState,
                    rowKey = { it.target },
                    assetForRow = { it },
                    accountRef = account,
                    retainedPreparation = owner,
                ) {
                    val shown = rememberDurableAvatar(state, assets[20], account)
                    SideEffect { commits += shown }
                    LazyColumn(state = listState, modifier = Modifier.height(80.dp)) {
                        items(assets, key = { it.target }) { Box(Modifier.height(40.dp)) }
                    }
                }
            }
        }
        rule.waitForIdle()
        val expected = assets.subList(20, 36).map { checkNotNull(it.cacheKey(ACCOUNT)) }.toSet()
        assertEquals(expected, owner.pixels.images.keys)
        rule.runOnIdle { mounted = false }
        rule.waitForIdle()
        evictDecodedPixels()
        assertNull(AvatarImageLoader.cachedImage(checkNotNull(assets[20].cacheKey(ACCOUNT))))
        commits.clear()

        rule.runOnIdle { mounted = true }
        rule.waitForIdle()

        assertTrue("the returning content must have committed", commits.isNotEmpty())
        commits.forEach { assertSame("first and subsequent commits retain the actual image", image, it) }
        assertEquals("empty layout must not replace the scrolled window with top rows", expected, owner.pixels.images.keys)
    }

    /** Window replacement releases older pins rather than accumulating every previously visited row. */
    @Test
    fun replacingTheViewportKeepsOnlySixteenPins() {
        mountOwner()
        seed(assets)
        select(assets)
        assertEquals(assets.take(16).map { it.cacheKey(ACCOUNT) }.toSet(), owner.pixels.images.keys)

        select(assets.drop(20))

        assertEquals(assets.subList(20, 36).map { it.cacheKey(ACCOUNT) }.toSet(), owner.pixels.images.keys)
    }

    /** A → B → A, sign-out, and a new list owner cannot revive a selection through an outgoing callback. */
    @Test
    fun accountAndSurfaceChangesRejectRetiredSelections() {
        mountOwner()
        seed(assets.take(1))
        select(assets.take(1))
        val retired = owner.updateVisible

        listOf("other", null, ACCOUNT).forEach { next ->
            rule.runOnIdle { account = next }
            rule.waitForIdle()
            rule.runOnIdle { retired(assets.take(1)) }
            rule.waitForIdle()
            assertTrue("a retired account callback cannot select pixels for $next", owner.pixels.images.isEmpty())
        }
        select(assets.take(1))
        rule.runOnIdle { surface++ }
        rule.waitForIdle()
        assertTrue("a replacement list starts without the old list's pins", owner.pixels.images.isEmpty())
    }

    /** Runtime teardown and explicit cache clear retire pins even when the account label remains the same. */
    @Test
    fun runtimeReplacementAndCacheClearRetireSelections() {
        mountOwner()
        seed(assets.take(1))
        select(assets.take(1))
        val retired = owner.updateVisible

        rule.runOnIdle {
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
                .apply { isAccessible = true }
                .invoke(state, DestructiveAccountWipeRuntimeState(ACCOUNT, null, null, state.runtimeGeneration + 1))
        }
        rule.waitForIdle()
        rule.runOnIdle { retired(assets.take(1)) }
        rule.waitForIdle()
        assertTrue(owner.pixels.images.isEmpty())
        seed(assets.take(1))
        select(assets.take(1))
        assertSame(image, owner.pixels.images.values.single())

        rule.runOnIdle { AvatarImageLoader.clearStoredAvatars() }
        rule.waitForIdle()

        assertTrue(owner.pixels.images.isEmpty())
    }

    /** Retained pins cannot override a newly removed, invalidated or different selected asset. */
    @Test
    fun invalidSelectionCannotDisplayRetainedPixels() {
        seed(assets.take(1))
        var selected by mutableStateOf<AvatarAssetFfi?>(assets.first())
        var shown: ImageBitmap? = null
        rule.setContent {
            owner = rememberRetainedGroupAvatarPreparation(state, account, surface)
            CompositionLocalProvider(LocalPreparedGroupAvatarPixels provides owner.pixels) {
                val pixels = rememberDurableAvatar(state, selected, account)
                SideEffect { shown = pixels }
            }
        }
        select(assets.take(1))
        assertSame(image, shown)
        evictDecodedPixels()
        val replacements =
            listOf(
                null,
                assets.first().copy(availability = AvatarAvailabilityFfi.INVALIDATED),
                assets.first().copy(reference = "another-reference"),
            )
        replacements.forEach { replacement ->
            rule.runOnIdle { selected = replacement }
            rule.waitForIdle()
            assertNull("the current selection is authoritative over retained pins", shown)
        }
    }

    /** Mounts the shell-owned preparation without a list, as while a conversation is open. */
    private fun mountOwner() {
        rule.setContent { owner = rememberRetainedGroupAvatarPreparation(state, account, surface) }
        rule.waitForIdle()
    }

    /** Delivers a measured viewport and waits for its cached pixels to be committed. */
    private fun select(selected: List<AvatarAssetFfi>) {
        rule.runOnIdle { owner.updateVisible(selected) }
        rule.waitForIdle()
    }

    /** Warms validated synthetic assets without native reads or network acquisition. */
    private fun seed(selected: List<AvatarAssetFfi>) {
        selected.forEach { AvatarImageLoader.putCached(checkNotNull(it.cacheKey(ACCOUNT)), image) }
    }

    /** Causes real LRU capacity eviction while retaining the existing privacy/invalidation lifetime. */
    private fun evictDecodedPixels() {
        repeat(20) { AvatarImageLoader.putCached("marmot-avatar:churn:$it@1", ImageBitmap(512, 512)) }
    }

    /** Gives each row a stable, independently selected MDK reference. */
    private fun asset(index: Int) =
        AvatarAssetFfi("row-$index", "ref-$index", AvatarAvailabilityFfi.READY, AvatarAcquisitionStateFfi.IDLE, 1uL, 64uL)

    private companion object {
        const val ACCOUNT = ConversationTimelineTestIds.ACCOUNT_REF
    }
}
