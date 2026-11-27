package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
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
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.ChatListAvatarSource
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DestructiveAccountWipeRuntimeState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.ui.account.RetainedAccountAvatarPixels
import dev.ipf.whitenoise.android.ui.account.rememberRetainedAccountAvatarPixels
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
    private var destination by mutableStateOf<AvatarAssetFfi?>(null)
    private lateinit var owner: RetainedGroupAvatarPreparation

    /** Removes only synthetic process pixels after each fixture. */
    @After fun clearPixels() = AvatarImageLoader.clear()

    /** A pending profile read may borrow its own pixels, but deletion and URL replacement discard them. */
    @Test
    fun retainedAccountPixelsRespectProfileResolutionAndPictureIdentity() {
        val retained = RetainedAccountAvatarPixels()
        retained.record(ACCOUNT, "old-url", unresolved = false, image = image)
        assertSame(image, retained.imageFor(ACCOUNT, null, unresolved = true))
        assertNull(retained.imageFor("another-account", "old-url", unresolved = false))
        assertNull(retained.imageFor(ACCOUNT, "new-url", unresolved = false))
        retained.record(ACCOUNT, null, unresolved = false, image = null)
        assertNull(retained.imageFor(ACCOUNT, null, unresolved = true))
        retained.record(ACCOUNT, "new-url", unresolved = false, image = image)
        assertSame(image, retained.imageFor(ACCOUNT, "new-url", unresolved = false))
        assertNull(retained.imageFor(ACCOUNT, "old-url", unresolved = false))
    }

    /** The actual shell owner retires pixels after sign-out, cache clear, and a same-account runtime replacement. */
    @Test
    fun topBarOwnerRetiresLastDrawnPixels() {
        lateinit var retained: RetainedAccountAvatarPixels
        val accounts = state.accounts
        val retiredAccount = accounts.single()
        rule.setContent { retained = rememberRetainedAccountAvatarPixels(state) }
        rule.waitForIdle()

        fun assertRetired(change: () -> Unit) {
            rule.runOnIdle {
                retained.record(retiredAccount.accountIdHex, "url", unresolved = false, image = image)
                change()
            }
            rule.waitForIdle()
            assertNull(retained.imageFor(retiredAccount.accountIdHex, "url", unresolved = false))
        }

        assertRetired {
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("notifyProfilesChanged")
                .apply { isAccessible = true }
                .invoke(state)
        }
        assertRetired { AvatarImageLoader.clear() }
        assertRetired { replaceRuntime() }
        assertRetired {
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setAccounts", List::class.java)
                .apply { isAccessible = true }
                .invoke(state, listOf(retiredAccount.copy(signedOut = true)))
        }
    }

    /** A compatibility image is scoped to its measured row and exact source. */
    @Test
    fun compatibilityWindowDropsReplacedRowsAndRejectsAnotherSource() {
        val retained = RetainedGroupCompatibilityPixels()
        val source = ChatListAvatarSource.ENCRYPTED_GROUP
        retained.select(listOf(CompatibilityAvatarSelection("visible", mapOf(source to "image-hash"))))
        retained.record("visible", source, "image-hash", image)
        retained.record("offscreen", source, "image-hash", image)
        assertSame(image, retained.imageFor("visible", source, "image-hash"))
        assertNull(retained.imageFor("visible", source, "replacement-hash"))
        assertNull(retained.imageFor("visible", ChatListAvatarSource.LEGACY_URL, "image-hash"))
        assertNull(retained.imageFor("offscreen", source, "image-hash"))
        retained.select(listOf(CompatibilityAvatarSelection("visible", mapOf(source to "replacement-hash"))))
        retained.record("visible", source, "image-hash", image)
        retained.select(listOf(CompatibilityAvatarSelection("visible", mapOf(source to "image-hash"))))
        assertNull(retained.imageFor("visible", source, "image-hash"))
    }

    /** Measured-window and late decode changes update their child content without restarting the shell owner. */
    @Test
    fun windowAndLatePixelUpdatesDoNotRecomposeTheOwner() {
        var ownerCommits = 0
        var shown: ImageBitmap? = null
        rule.setContent {
            owner = rememberRetainedGroupAvatarPreparation(state, account, surface)
            SideEffect { ownerCommits++ }
            ObserveRetainedAvatarPixels(owner) { pixels ->
                val image =
                    assets
                        .take(2)
                        .mapNotNull { asset ->
                            pixels.imageFor(asset, checkNotNull(asset.cacheKey(ACCOUNT)))
                        }.singleOrNull()
                SideEffect { shown = image }
            }
        }
        rule.waitForIdle()
        val initialCommits = ownerCommits
        seed(assets.take(1))
        select(assets.take(1))
        assertSame(image, shown)
        assertEquals("selecting a measured window must not restart the shell", initialCommits, ownerCommits)

        select(assets.subList(1, 2), rows = assets.subList(1, 2))
        assertNull(shown)
        rule.runOnIdle { checkNotNull(owner.pixels.recordLoaded)(assets[1], image) }
        rule.waitForIdle()
        assertSame("late pixels still reach the consuming content", image, shown)
        assertEquals("late decoding must not restart the shell", initialCommits, ownerCommits)
    }

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
        val expected =
            avatarViewport(assets.size, listOf(20, 21))
                .nearby
                .map { checkNotNull(assets[it].cacheKey(ACCOUNT)) }
                .toSet()
        assertEquals(expected, retainedKeys(assets))
        rule.runOnIdle { mounted = false }
        rule.waitForIdle()
        evictDecodedPixels()
        assertNull(AvatarImageLoader.cachedImage(checkNotNull(assets[20].cacheKey(ACCOUNT))))
        commits.clear()

        rule.runOnIdle { mounted = true }
        rule.waitForIdle()

        assertTrue("the returning content must have committed", commits.isNotEmpty())
        commits.forEach { assertSame("first and subsequent commits retain the actual image", image, it) }
        assertEquals(
            "empty layout must not replace the scrolled window with top rows",
            expected,
            retainedKeys(assets),
        )
    }

    /** Bounded recency preserves both directions and eventually releases the oldest known pins. */
    @Test
    fun recentRowsSurviveWindowChangesWithinTheRetentionBudget() {
        val rows = (0 until 72).map(::asset)
        mountOwner()
        seed(rows)
        select(rows.take(16), rows = rows)
        select(rows.subList(16, 32), rows = rows)
        select(rows.subList(40, 56), rows = rows)

        assertEquals(
            (rows.subList(16, 32) + rows.subList(40, 56)).map { it.cacheKey(ACCOUNT) }.toSet(),
            retainedKeys(rows),
        )

        select(rows.subList(56, 72), rows = rows)
        assertEquals(
            rows.subList(40, 72).map { it.cacheKey(ACCOUNT) }.toSet(),
            retainedKeys(rows),
        )
    }

    /** Offscreen selections are checked against the current projection before they can return. */
    @Test
    fun changedOffscreenSelectionRetiresItsPreviousPixels() {
        mountOwner()
        seed(assets.take(1))
        select(assets.take(1), rows = assets)
        evictDecodedPixels()
        val replacement = assets.first().copy(reference = "replacement")
        select(assets.drop(20), rows = listOf(replacement) + assets.drop(1))
        select(assets.take(1), rows = assets)
        assertNull(owner.pixels.retainedFor(assets.first()))
    }

    /** A → B → A, sign-out, and a new list owner cannot revive a selection through an outgoing callback. */
    @Test
    fun accountAndSurfaceChangesRejectRetiredSelections() {
        mountOwner()
        seed(assets.take(1))
        select(assets.take(1))
        val retired = owner
        val retiredPixels = checkNotNull(owner.pixels.recordLoaded)

        listOf("other", null, ACCOUNT).forEach { next ->
            rule.runOnIdle { account = next }
            rule.waitForIdle()
            rule.runOnIdle {
                updateViewport(retired, assets.take(1), assets)
                retiredPixels(assets.first(), image)
            }
            rule.waitForIdle()
            assertTrue("a retired account callback cannot select pixels for $next", retainedKeys(assets).isEmpty())
        }
        select(assets.take(1))
        rule.runOnIdle { surface++ }
        rule.waitForIdle()
        assertTrue("a replacement list starts without the old list's pins", retainedKeys(assets).isEmpty())
    }

    /** Runtime teardown and explicit cache clear retire pins even when the account label remains the same. */
    @Test
    fun runtimeReplacementAndCacheClearRetireSelections() {
        mountOwner()
        seed(assets.take(1))
        select(assets.take(1))
        val retired = owner

        rule.runOnIdle {
            replaceRuntime()
        }
        rule.waitForIdle()
        rule.runOnIdle { updateViewport(retired, assets.take(1), assets) }
        rule.waitForIdle()
        assertTrue(retainedKeys(assets).isEmpty())
        seed(assets.take(1))
        select(assets.take(1))
        assertSame(
            image,
            owner.pixels.imageFor(assets.first(), checkNotNull(assets.first().cacheKey(ACCOUNT))),
        )

        rule.runOnIdle { AvatarImageLoader.clearStoredAvatars() }
        rule.waitForIdle()

        assertTrue(retainedKeys(assets).isEmpty())
    }

    /** Changes the runtime owner without also changing the account label. */
    private fun replaceRuntime() {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
            .apply { isAccessible = true }
            .invoke(state, DestructiveAccountWipeRuntimeState(ACCOUNT, null, null, state.runtimeGeneration + 1))
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

    /**
     * A conversation, its details or its edit screen opened from the list draws the list's retained picture on its
     * own first commit, after the shared decoded cache has evicted it and before any local read could finish.
     */
    @Test
    fun forwardDestinationDrawsTheListsPictureOnItsFirstCommitAfterEviction() {
        seed(assets.take(1))
        val commits = mountDestination()
        select(assets.take(1))
        evictDecodedPixels()
        assertNull(AvatarImageLoader.cachedImage(checkNotNull(assets.first().cacheKey(ACCOUNT))))

        rule.runOnIdle { destination = assets.first() }
        rule.waitForIdle()

        assertTrue("the destination must have committed", commits.isNotEmpty())
        assertSame("the destination's first commit draws the list's picture", image, commits.first())
    }

    /** A destination showing another revision of the picture never starts from the list's older pixels. */
    @Test
    fun forwardDestinationNeverBorrowsAnotherRevision() {
        seed(assets.take(1))
        val commits = mountDestination()
        select(assets.take(1))
        evictDecodedPixels()

        rule.runOnIdle { destination = assets.first().copy(contentRevision = 2uL) }
        rule.waitForIdle()

        assertTrue("the destination must have committed", commits.isNotEmpty())
        assertNull("another revision's first commit has no borrowed pixels", commits.first())
    }

    /** The shell hands its retained window to the conversation route that hosts details and editing. */
    @Test
    fun shellProvidesItsRetainedWindowToTheConversationRoute() {
        val shell = appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt")
        assertTrue(
            "the conversation route must receive the list's retained pixels",
            "LocalRetainedGroupAvatarPixels provides retainedAvatarPreparation," in shell,
        )
    }

    /**
     * Mounts the shell owner and, once [destination] is set, a destination preparing that one picture under the
     * shell's retained window, recording every avatar it commits.
     */
    private fun mountDestination(): List<ImageBitmap?> {
        val commits = mutableListOf<ImageBitmap?>()
        rule.setContent {
            owner = rememberRetainedGroupAvatarPreparation(state, account, surface)
            destination?.let { shownAsset ->
                CompositionLocalProvider(LocalRetainedGroupAvatarPixels provides owner) {
                    PreparedGroupAvatarContent(state, listOf(shownAsset), account) {
                        val shown = rememberDurableAvatar(state, shownAsset, account)
                        SideEffect { commits += shown }
                    }
                }
            }
        }
        rule.waitForIdle()
        return commits
    }

    /** Mounts the shell-owned preparation without a list, as while a conversation is open. */
    private fun mountOwner() {
        rule.setContent { owner = rememberRetainedGroupAvatarPreparation(state, account, surface) }
        rule.waitForIdle()
    }

    /** Delivers a measured viewport and waits for its cached pixels to be committed. */
    private fun select(
        selected: List<AvatarAssetFfi>,
        rows: List<AvatarAssetFfi> = assets,
    ) {
        rule.runOnIdle { updateViewport(owner, selected, rows) }
        rule.waitForIdle()
    }

    private fun updateViewport(
        owner: RetainedGroupAvatarPreparation,
        selected: List<AvatarAssetFfi>,
        rows: List<AvatarAssetFfi>,
    ) {
        val indexes = selected.take(VISIBLE_GROUP_AVATAR_LIMIT).map(rows::indexOf)
        owner.updateVisible(
            AvatarViewport(visible = indexes, nearby = indexes),
            keyAt = { rows[it].target },
            indexFor = { key -> rows.indexOfFirst { it.target == key }.takeIf { it >= 0 } },
            assetAt = rows::get,
        )
    }

    private fun retainedKeys(rows: List<AvatarAssetFfi>): Set<String> =
        rows
            .mapNotNull { asset ->
                val key = checkNotNull(asset.cacheKey(ACCOUNT))
                key.takeIf { owner.pixels.imageFor(asset, key) != null }
            }.toSet()

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
        AvatarAssetFfi(
            "row-$index",
            "ref-$index",
            AvatarAvailabilityFfi.READY,
            AvatarAcquisitionStateFfi.IDLE,
            1uL,
            64uL,
        )

    private companion object {
        const val ACCOUNT = ConversationTimelineTestIds.ACCOUNT_REF
    }
}

/** Observes retained pixels in a separate restart scope, matching the production list content. */
@Composable
@Suppress("FunctionNaming")
private fun ObserveRetainedAvatarPixels(
    owner: RetainedGroupAvatarPreparation,
    content: @Composable (PreparedGroupAvatarPixels) -> Unit,
) {
    content(owner.pixels)
}
