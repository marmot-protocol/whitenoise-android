package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.AvatarBytesFfi
import dev.ipf.marmotkit.ConversationAnchorKindFfi
import dev.ipf.marmotkit.ConversationAnchorOutcomeFfi
import dev.ipf.marmotkit.ConversationCapabilitiesFfi
import dev.ipf.marmotkit.ConversationHeaderFfi
import dev.ipf.marmotkit.ConversationOpenReadStateFfi
import dev.ipf.marmotkit.ConversationParticipationFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.AvatarLoadRecovery
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationWindowFrame
import dev.ipf.whitenoise.android.state.DestructiveAccountWipeRuntimeState
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.ProfileGroupPickerLoadState
import dev.ipf.whitenoise.android.state.ProfileGroupPickerState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.notificationChatListRow
import dev.ipf.whitenoise.android.state.retainedAvatarBytesReader
import dev.ipf.whitenoise.android.ui.chats.ChatRow
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.common.PreparedGroupAvatarContent
import dev.ipf.whitenoise.android.ui.common.PreparedVisibleGroupAvatarContent
import dev.ipf.whitenoise.android.ui.common.conversationGroupAvatarAsset
import dev.ipf.whitenoise.android.ui.common.rememberConversationGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberDurableAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupAvatarPresentation
import dev.ipf.whitenoise.android.ui.conversation.ConversationTopBar
import dev.ipf.whitenoise.android.ui.group.GroupDetailsScreen
import dev.ipf.whitenoise.android.ui.group.GroupEditScreen
import dev.ipf.whitenoise.android.ui.profile.ProfileAddToGroupsContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

/** Production composition, local-store admission and current-owner pixel binding share one resolver. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GroupAvatarLocalAssetScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val screenshotErrors = ErrorCollector()

    /** Clears process-only pixels and test adapters, never a populated app database. */
    @Before
    @After
    fun clearLoaders() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
        GroupAvatarImageLoader.clear()
    }

    /** Actual row-to-header-to-details-to-editor navigation never reacquires a warm selected picture. */
    @Test
    fun productionGroupSurfacesReuseWarmPublicPixels() = captureProductionSurfaces(encrypted = false)

    /** The same production navigation works for encrypted selections without their legacy download. */
    @Test
    fun productionGroupSurfacesReuseWarmEncryptedPixels() = captureProductionSurfaces(encrypted = true)

    /** A route change retains the current MDK identity while all network adapters are unavailable. */
    private fun captureProductionSurfaces(encrypted: Boolean) {
        val fixture = AvatarLocalFixture(withRuntime = false)
        val surface = productionSurfaceFixture(fixture, encrypted)
        val step = mutableStateOf(0)
        val urlFetches = AtomicInteger()
        AvatarImageLoader.attachProfileImageFetcher { _, _ ->
            urlFetches.incrementAndGet()
            error("network blocked")
        }
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        key(step.value) {
                            ProductionAvatarSurface(step.value, fixture.state, surface.item, surface.controller)
                        }
                    }
                }
            }
            for (index in 0..3) {
                composeRule.runOnIdle { step.value = index }
                composeRule.waitForIdle()
                assertBlueAvatarPixels()
                if (index == 3) {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    composeRule.onNodeWithText(context.getString(R.string.change_photo)).assertExists()
                }
                val kind = if (encrypted) "encrypted" else "public"
                // Collect screenshot failures so every route is exercised, but still fail the test at completion.
                screenshotErrors.checkSucceeds {
                    composeRule.onRoot().captureRoboImage(
                        "src/test/snapshots/group_stored_avatar_${kind}_surface_$index.png",
                    )
                }
            }
            captureStoredPictureViewer(if (encrypted) "encrypted" else "public")
            assertEquals(0, urlFetches.get())
            assertEquals(0, fixture.reads.get())
            assertEquals(0, fixture.requests.get())
            assertEquals(0, fixture.legacyDownloads.get())
        } finally {
            surface.controller.onCleared()
            surface.chats.onCleared()
        }
    }

    /** Tests rendered pixels, rather than inferring the result from a helper's returned bitmap. */
    private fun assertBlueAvatarPixels(node: SemanticsNodeInteraction = composeRule.onRoot()) {
        val bitmap = node.captureToImage().asAndroidBitmap()
        var bluePixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == Color.rgb(0x1E, 0x88, 0xE5)) bluePixels += 1
            }
        }
        assertTrue("Selected group picture must be rendered on the production surface", bluePixels > 300)
    }

    /** Opens the real editor's picture action and records its immediately supplied image, without refetching. */
    private fun captureStoredPictureViewer(kind: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val label = context.getString(R.string.profile_view_picture)
        composeRule
            .onNode(
                SemanticsMatcher("View picture action") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label == label
                },
            ).performClick()
        composeRule.waitForIdle()
        val dialog = composeRule.onNode(isDialog())
        assertBlueAvatarPixels(dialog)
        screenshotErrors.checkSucceeds {
            dialog.captureRoboImage("src/test/snapshots/group_stored_avatar_${kind}_viewer.png")
        }
    }

    /** Header hydration/name lag must retain the current row asset, but an image-identity mismatch cannot. */
    @Test
    fun detailsReuseTheHiddenRowSelectionAcrossRenameWithoutLegacyFallback() {
        val fixture = AvatarLocalFixture()
        val selected = asset()
        val chats =
            ChatsController(
                fixture.state,
                initialAccountRef = ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        chats.setChatListVisible(false)
        val original = group().copy(name = "Old group name", avatarUrl = "https://old.example/picture.png")
        chats.applyChatListRow(
            notificationChatListRow().copy(
                groupIdHex = GROUP_ID,
                groupName = original.name,
                avatarUrl = original.avatarUrl,
            ),
        )
        chats.applyLocalGroupUpdate(original)
        ChatsController::class.java
            .getDeclaredField("selectedAvatarAssetsByGroup")
            .apply { isAccessible = true }
            .set(chats, mapOf(GROUP_ID to selected))
        fixture.state.attachChatsController(chats)
        val conversation = ConversationController(fixture.state, initialGroup = original.copy(name = "Renamed group"))
        assertNull(conversation.window.header)
        assertSame(selected, conversationGroupAvatarAsset(fixture.state, conversation))
        val cached =
            checkNotNull(
                AvatarImageLoader.decodeAndCache(
                    checkNotNull(selected.cacheKey(ACCOUNT_REF)),
                    solidPng(),
                    AvatarImageLoader.currentCacheLifetime(),
                ),
            )
        var observed: ImageBitmap? = null
        try {
            composeRule.setContent {
                val presentation = rememberConversationGroupAvatar(fixture.state, conversation)
                SideEffect { observed = presentation.image }
                Text("details")
            }
            composeRule.waitForIdle()
            assertSame(cached, observed)
            composeRule.runOnIdle {
                conversation.applyGroupStateForTest(
                    original.copy(name = "Renamed group", avatarUrl = "https://new.example/picture.png"),
                )
            }
            composeRule.waitForIdle()
            assertNull(observed)
            assertEquals(0, fixture.legacyDownloads.get())
            assertEquals(0, fixture.reads.get())
        } finally {
            conversation.onCleared()
            chats.onCleared()
        }
    }

    /** Warm public and encrypted selections have pixels in the first populated composition without I/O. */
    @Test
    fun warmSelectedAssetsNeverUseLegacyAcquisition() {
        val fixture = AvatarLocalFixture()
        val selected = asset()
        val cached =
            checkNotNull(
                AvatarImageLoader.decodeAndCache(
                    checkNotNull(selected.cacheKey(ACCOUNT_REF)),
                    solidPng(),
                    AvatarImageLoader.currentCacheLifetime(),
                ),
            )
        val firstFrames = mutableListOf<ImageBitmap?>()
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> error("unexpected URL acquisition") }
        composeRule.setContent {
            WhiteNoiseTheme {
                PreparedGroupAvatarContent(fixture.state, listOf(selected, selected)) {
                    Column {
                        for (record in listOf(
                            group().copy(avatarUrl = "https://image.example/avatar.png"),
                            group().copy(imageHashHex = "encrypted-picture"),
                        )) {
                            val presentation = rememberGroupAvatarPresentation(fixture.state, record, selected)
                            SideEffect { firstFrames += presentation.image }
                            GroupAvatar(fixture.state, record, GROUP_NAME, GROUP_ID, 48.dp, durableAvatar = selected)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue(firstFrames.isNotEmpty())
        firstFrames.forEach { assertSame(cached, it) }
        assertEquals(0, fixture.reads.get())
        assertEquals(0, fixture.requests.get())
        assertEquals(0, fixture.legacyDownloads.get())
    }

    /** Cold local decoding never withholds navigation; warm-cache first frames remain synchronous. */
    @Test
    fun evictedSelectionKeepsNavigationAvailableWhileDecoding() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture =
            AvatarLocalFixture {
                assertFalse(Looper.myLooper() == Looper.getMainLooper())
                started.complete(Unit)
                release.await()
                listOf(payload())
            }
        val selected = asset()
        val firstFrames = mutableListOf<ImageBitmap?>()
        var backCalls = 0
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    PreparedGroupAvatarContent(fixture.state, listOf(selected, selected)) {
                        val image = rememberDurableAvatar(fixture.state, selected)
                        SideEffect { firstFrames += image }
                        Text("populated", Modifier.testTag("populated"))
                        Button(onClick = { backCalls += 1 }) { Text("Back while decoding") }
                    }
                }
            }
            awaitLocal { started.isCompleted }
            composeRule.onNodeWithTag("populated").assertExists()
            composeRule.onNodeWithText("Back while decoding").performClick()
            assertEquals(1, backCalls)
            composeRule.onRoot().captureRoboImage("src/test/snapshots/group_avatar_cold_decode_navigation_light.png")
            composeRule.runOnIdle { release.complete(Unit) }
            awaitLocal { firstFrames.lastOrNull() != null }
            assertEquals(1, fixture.reads.get())
            assertEquals(0, fixture.requests.get())
            assertEquals(0, fixture.legacyDownloads.get())
        } finally {
            release.complete(Unit)
        }
    }

    /** A recovery event that retires a cold local read must let the unchanged visible slot retry. */
    @Test
    fun networkRecoveryRetriesTheVisibleColdStoredPicture() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture =
            AvatarLocalFixture {
                started.complete(Unit)
                release.await()
                listOf(payload())
            }
        val selected = asset()
        var observed: ImageBitmap? = null
        try {
            composeRule.setContent {
                val image = rememberDurableAvatar(fixture.state, selected)
                SideEffect { observed = image }
                Text(if (image == null) "empty" else "loaded")
            }
            awaitLocal { started.isCompleted }
            composeRule.runOnIdle { AvatarLoadRecovery.onNetworkRestored() }
            composeRule.waitForIdle()
            composeRule.runOnIdle { release.complete(Unit) }
            awaitLocal { observed != null }
            assertTrue(fixture.reads.get() >= 2)
            assertEquals(0, fixture.requests.get())
            assertEquals(0, fixture.legacyDownloads.get())
        } finally {
            release.complete(Unit)
        }
    }

    /** A changed reference/revision or explicit invalidation cannot borrow the remembered old bitmap. */
    @Test
    fun replacementAndRemovalDropOldPixelsSynchronously() {
        val fixture = AvatarLocalFixture { emptyList() }
        val current = mutableStateOf(asset())
        val cached =
            checkNotNull(
                AvatarImageLoader.decodeAndCache(
                    checkNotNull(current.value.cacheKey(ACCOUNT_REF)),
                    solidPng(),
                    AvatarImageLoader.currentCacheLifetime(),
                ),
            )
        var observed: ImageBitmap? = null
        composeRule.setContent {
            val image = rememberDurableAvatar(fixture.state, current.value)
            SideEffect { observed = image }
            Text(if (image == null) "empty" else "loaded")
        }
        composeRule.waitForIdle()
        assertSame(cached, observed)
        composeRule.runOnIdle { current.value = asset().copy(reference = "replacement", contentRevision = 2uL) }
        composeRule.waitForIdle()
        assertNull(observed)
        composeRule.onNodeWithText("empty").assertExists()
        composeRule.runOnIdle { current.value = asset().copy(availability = AvatarAvailabilityFfi.INVALIDATED) }
        composeRule.waitForIdle()
        assertNull(observed)
        assertEquals(0, fixture.legacyDownloads.get())
    }

    /** The add-to-group production row consumes the warm selected bitmap instead of its stale URL. */
    @Test
    fun addToGroupsUsesSelectedPixelsOnItsFirstFrame() {
        val fixture = AvatarLocalFixture()
        val selected = asset()
        assertNotNull(
            AvatarImageLoader.decodeAndCache(
                checkNotNull(selected.cacheKey(ACCOUNT_REF)),
                solidPng(),
                AvatarImageLoader.currentCacheLifetime(),
            ),
        )
        val item =
            ChatListItem(
                group = group().copy(avatarUrl = "https://unavailable.example/old.png"),
                latest = null,
                otherMemberAccount = null,
                memberCount = 3,
                memberSnapshot = null,
                selectedAvatarAsset = selected,
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ProfileAddToGroupsContent(
                        fixture.state,
                        "Alice",
                        ProfileGroupPickerState(listOf(item), emptySet(), ProfileGroupPickerLoadState.READY),
                        busy = false,
                        onClose = {},
                        onRetry = {},
                        onAdd = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/profile_add_to_groups_stored_avatar_light.png")
        assertEquals(0, fixture.reads.get())
        assertEquals(0, fixture.legacyDownloads.get())
    }

    /** A known missing asset never waits for a local read or revives an old URL/encrypted image. */
    @Test
    fun missingSelectionPublishesPlaceholderWithoutPreparation() {
        val fixture = AvatarLocalFixture()
        val missing = asset().copy(availability = AvatarAvailabilityFfi.MISSING)
        var observed: ImageBitmap? = null
        composeRule.setContent {
            PreparedGroupAvatarContent(fixture.state, listOf(missing)) {
                val presentation =
                    rememberGroupAvatarPresentation(
                        fixture.state,
                        group().copy(avatarUrl = "https://old.example/avatar.png"),
                        missing,
                    )
                SideEffect { observed = presentation.image }
                Text("available")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("available").assertExists()
        assertNull(observed)
        assertEquals(0, fixture.reads.get())
        assertEquals(0, fixture.legacyDownloads.get())
    }

    /** A renderable projection without a usable reference is still a lazy miss, never a preparation crash. */
    @Test
    fun missingReferenceDoesNotEnterPreparation() {
        val fixture = AvatarLocalFixture()
        val selected = asset().copy(reference = null)
        var observed: ImageBitmap? = null
        composeRule.setContent {
            PreparedGroupAvatarContent(fixture.state, listOf(selected)) {
                val presentation = rememberGroupAvatarPresentation(fixture.state, group(), selected)
                SideEffect { observed = presentation.image }
                Text("reference unavailable")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("reference unavailable").assertExists()
        assertNull(observed)
        assertEquals(0, fixture.reads.get())
        assertEquals(0, fixture.legacyDownloads.get())
    }

    /** A stale asset retains its current pixels while native refresh cannot provide another payload. */
    @Test
    fun staleRefreshMissDoesNotBlankTheCurrentPicture() {
        val fixture = AvatarLocalFixture { emptyList() }
        val selected = asset().copy(availability = AvatarAvailabilityFfi.STALE)
        val cached =
            checkNotNull(
                AvatarImageLoader.decodeAndCache(
                    checkNotNull(selected.cacheKey(ACCOUNT_REF)),
                    solidPng(),
                    AvatarImageLoader.currentCacheLifetime(),
                ),
            )
        val frames = mutableListOf<ImageBitmap?>()
        composeRule.setContent {
            val image = rememberDurableAvatar(fixture.state, selected)
            SideEffect { frames += image }
            Text("refreshing")
        }
        composeRule.waitForIdle()
        awaitLocal { fixture.requests.get() == 1 }
        assertTrue(frames.isNotEmpty())
        frames.forEach { assertSame(cached, it) }
        assertEquals(0, fixture.reads.get())
        assertEquals(1, fixture.requests.get())
    }

    /** Clearing a lifetime resets even a retained same-account/same-reference composition immediately. */
    @Test
    fun sameOwnerAfterLifetimeClearCannotKeepRememberedPixels() {
        val release = CompletableDeferred<Unit>()
        val fixture =
            AvatarLocalFixture {
                release.await()
                emptyList()
            }
        val selected = asset()
        var observed: ImageBitmap? = null
        val cached =
            checkNotNull(
                AvatarImageLoader.decodeAndCache(
                    checkNotNull(selected.cacheKey(ACCOUNT_REF)),
                    solidPng(),
                    AvatarImageLoader.currentCacheLifetime(),
                ),
            )
        try {
            composeRule.setContent {
                val image = rememberDurableAvatar(fixture.state, selected)
                SideEffect { observed = image }
                Text("retained composition")
            }
            composeRule.waitForIdle()
            assertSame(cached, observed)
            composeRule.runOnIdle {
                fixture.state.clearCrossAccountCachesForTest()
            }
            composeRule.waitForIdle()
            assertNull(observed)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun cacheInvalidationPreservesEditableSurfaceState() {
        val fixture = AvatarLocalFixture { emptyList() }
        composeRule.setContent {
            PreparedGroupAvatarContent(fixture.state, emptyList()) {
                var draft by rememberSaveable { mutableStateOf("Original") }
                Button(onClick = { draft = "Edited draft" }) { Text(draft) }
            }
        }
        composeRule.onNodeWithText("Original").performClick()
        composeRule.onNodeWithText("Edited draft").assertExists()
        composeRule.runOnIdle { AvatarImageLoader.clearStoredAvatars() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edited draft").assertExists()
    }

    @Test
    fun peerDisplayPictureKeepsTheSelectedAssetBeforeHeaderHydration() {
        val fixture = AvatarLocalFixture { emptyList() }
        val source = group().copy(name = "", avatarUrl = null)
        val selected = selectedPicture(PresentationSourceFfi.PEER_PROFILE)
        val production = productionSurfaceFixture(fixture, false, source, selected)
        assertEquals(SELECTED_PICTURE_URL, production.item.group.avatarUrl)
        assertNull(production.controller.window.header)
        assertEquals(asset(), conversationGroupAvatarAsset(fixture.state, production.controller))
    }

    @Test
    fun displayPictureDoesNotEraseTheSourceGroupAvatarIdentity() {
        val fixture = AvatarLocalFixture { emptyList() }
        val source = group().copy(avatarUrl = SELECTED_PICTURE_URL, imageHashHex = "group-picture-hash")
        val production = productionSurfaceFixture(fixture, false, source, selectedPicture(PresentationSourceFfi.GROUP))
        assertNull(production.item.group.imageHashHex)
        assertNull(production.controller.window.header)
        assertEquals(asset(), conversationGroupAvatarAsset(fixture.state, production.controller))
    }

    @Test
    fun recreatedAvatarOwnersPreserveSavedEditableSurfaceState() {
        val restoration = StateRestorationTester(composeRule)
        val owners = mutableListOf<WhiteNoiseAppState>()
        restoration.setContent {
            val fixture = remember { AvatarLocalFixture { emptyList() } }
            val surfaceOwner = remember { Any() }
            SideEffect { if (owners.lastOrNull() !== fixture.state) owners.add(fixture.state) }
            PreparedGroupAvatarContent(fixture.state, emptyList(), surfaceIdentity = surfaceOwner) {
                var draft by rememberSaveable { mutableStateOf("Original") }
                Button(onClick = { draft = "Edited draft" }) { Text(draft) }
            }
        }
        composeRule.onNodeWithText("Original").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(2, owners.size)
        composeRule.onNodeWithText("Edited draft").assertExists()
    }

    @Test
    fun sameAccountRuntimeReplacementPreservesEditableSurfaceState() {
        val fixture = AvatarLocalFixture { emptyList() }
        composeRule.setContent {
            PreparedGroupAvatarContent(fixture.state, emptyList()) {
                var draft by rememberSaveable { mutableStateOf("Original") }
                Button(onClick = { draft = "Edited draft" }) { Text(draft) }
            }
        }
        composeRule.onNodeWithText("Original").performClick()
        composeRule.runOnIdle {
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
                .apply { isAccessible = true }
                .invoke(
                    fixture.state,
                    DestructiveAccountWipeRuntimeState(ACCOUNT_REF, null, null, fixture.state.runtimeGeneration + 1),
                )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edited draft").assertExists()
    }

    @Test
    fun scrollingWithinTheSameAvatarWindowDoesNotRecomposeListContent() {
        val fixture = AvatarLocalFixture { emptyList() }
        val listState = LazyListState()
        val rows = (0 until 30).map { "group-$it" }
        val compositions = AtomicInteger()
        composeRule.setContent {
            PreparedVisibleGroupAvatarContent(fixture.state, rows, listState, { it }, { null }) {
                SideEffect { compositions.incrementAndGet() }
                LazyColumn(Modifier.height(200.dp).fillMaxWidth(), state = listState) {
                    items(rows, key = { it }) { row ->
                        Box(Modifier.height(100.dp)) { Text(row) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val initial = compositions.get()
        for (offset in 1..5) {
            composeRule.runOnIdle { runBlocking { listState.scrollToItem(0, offset) } }
            composeRule.waitForIdle()
            assertEquals(offset, listState.firstVisibleItemScrollOffset)
            assertEquals(initial, compositions.get())
        }
    }

    /** A same-account runtime generation replacement cannot reuse private pixels from the old runtime. */
    @Test
    fun productionRuntimeGenerationReplacementRetiresStoredPixels() {
        val fixture = AvatarLocalFixture { emptyList() }
        val selected = asset()
        val imageKey = checkNotNull(selected.cacheKey(ACCOUNT_REF))
        assertNotNull(AvatarImageLoader.decodeAndCache(imageKey, solidPng(), AvatarImageLoader.currentCacheLifetime()))
        var observed: ImageBitmap? = null
        composeRule.setContent {
            val image = rememberDurableAvatar(fixture.state, selected)
            SideEffect { observed = image }
            Text("generation ${fixture.state.runtimeGeneration}")
        }
        composeRule.waitForIdle()
        assertNotNull(observed)
        composeRule.runOnIdle {
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
                .apply { isAccessible = true }
                .invoke(
                    fixture.state,
                    DestructiveAccountWipeRuntimeState(ACCOUNT_REF, null, null, fixture.state.runtimeGeneration + 1),
                )
        }
        composeRule.waitForIdle()
        assertNull(AvatarImageLoader.cachedImage(imageKey))
        assertNull(observed)
    }

    /** Native runtime teardown retires decoded plaintext before a same-reference owner can re-enter. */
    @Test
    fun productionNativeRuntimeClearRetiresStoredPixels() {
        val fixture = AvatarLocalFixture()
        val imageKey = checkNotNull(asset().cacheKey(ACCOUNT_REF))
        val lifetime = AvatarImageLoader.currentCacheLifetime()
        assertNotNull(AvatarImageLoader.decodeAndCache(imageKey, solidPng(), lifetime))
        val runtime = checkNotNull(fixture.state.captureHostPerformanceRuntimeOwner()).runtime
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("clearMarmotRuntime", AppMarmotRuntime::class.java)
            .apply { isAccessible = true }
            .invoke(fixture.state, runtime)
        assertNull(AvatarImageLoader.cachedImage(imageKey))
        assertTrue(AvatarImageLoader.currentCacheLifetime() != lifetime)
    }

    /** Drains the actual main looper for off-main local store callbacks, not the Compose animation clock. */
    private fun awaitLocal(condition: () -> Boolean) {
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            condition()
        }
        composeRule.waitForIdle()
    }
}

/** Header selection comes from the native frame, even before the Android roster hydrates. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ConversationHeaderAvatarSelectionTest {
    @Before
    @After
    fun clearLoader() = AvatarImageLoader.clear()

    @Test
    fun loadedPeerHeaderUsesNativeMemberCountBeforeRosterHydration() {
        val fixture = AvatarLocalFixture { emptyList() }
        val production = productionSurfaceFixture(fixture, false, group().copy(name = "", avatarUrl = null))
        assertEquals(0, production.controller.presentedMemberCount)
        production.controller.window.install(avatarHeaderFrame(selectedPicture(PresentationSourceFfi.PEER_PROFILE)))
        assertEquals(asset(), conversationGroupAvatarAsset(fixture.state, production.controller))
    }

    @Test
    fun loadedGroupHeaderRejectsChangedSourceIdentity() {
        val fixture = AvatarLocalFixture { emptyList() }
        val production = productionSurfaceFixture(fixture, false)
        production.controller.window.install(avatarHeaderFrame(selectedPicture(PresentationSourceFfi.GROUP)))
        assertNull(conversationGroupAvatarAsset(fixture.state, production.controller))
    }
}

private fun avatarHeaderFrame(selected: ConversationPresentationFfi) =
    ConversationWindowFrame(
        revision = ConversationWindowRevisionFfi("avatar-generation", 1uL),
        header =
            ConversationHeaderFfi(
                selected = selected,
                memberCount = 2uL,
                archived = false,
                epoch = 1uL,
                lifecycle = GroupLifecycleStateFfi.STABLE,
                disbanding = false,
                unrecoverable = false,
                avatarAsset = asset(),
                capabilities =
                    ConversationCapabilitiesFfi(
                        participation = ConversationParticipationFfi.ACTIVE,
                        isSelfAdmin = false,
                        isLastAdmin = false,
                        canSend = true,
                        canInvite = false,
                        canEditGroup = false,
                        canLeave = true,
                        requiresSelfDemoteBeforeLeave = false,
                        canEnableDisbanding = false,
                        canDisband = false,
                    ),
            ),
        identities = emptyMap(),
        readState = ConversationOpenReadStateFfi(true, null, null, false, 0uL, 0uL, null),
        draft = SelectedMessageDraftFfi(avatarDraftRevisionStub(), null),
        anchor = ConversationAnchorOutcomeFfi(ConversationAnchorKindFfi.LATEST, 0u),
        references = emptyMap(),
    )

/** The native handle is never invoked; this follows the existing conversation-window JVM fixture. */
private fun avatarDraftRevisionStub(): MessageDraftRevisionFfi {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return unsafeClass
        .getMethod("allocateInstance", Class::class.java)
        .invoke(unsafe, MessageDraftRevisionFfi::class.java) as MessageDraftRevisionFfi
}

/** Original local-image export remains independent of rendered UI and never creates another download. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StoredAvatarExportTest {
    /** Clears only the test fixture's decoded process pixels. */
    @Before
    @After
    fun clearPixels() = AvatarImageLoader.clear()

    /** Saving a locally displayed picture exports original validated bytes and never acquires them again. */
    @Test
    fun storedPictureExportUsesOriginalBytesAndRejectsRetiredReaders() =
        runBlocking {
            val fixture = AvatarLocalFixture()
            val reader = checkNotNull(fixture.state.retainedAvatarBytesReader(asset(), ACCOUNT_REF))
            assertTrue(checkNotNull(reader()).contentEquals(solidPng()))
            assertEquals(1, fixture.reads.get())
            assertEquals(0, fixture.requests.get())
            assertEquals(0, fixture.legacyDownloads.get())
            fixture.state.clearCrossAccountCachesForTest()
            assertNull(reader())
            assertEquals(1, fixture.reads.get())
            assertNull(
                fixture.state.retainedAvatarBytesReader(
                    asset().copy(availability = AvatarAvailabilityFfi.INVALIDATED),
                    ACCOUNT_REF,
                ),
            )
        }

    /** Original plaintext bytes returning after an account clear cannot complete a gallery export. */
    @Test
    fun storedPictureExportRejectsAnAccountChangeDuringRead() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                AvatarLocalFixture {
                    started.complete(Unit)
                    release.await()
                    listOf(payload())
                }
            val reader = checkNotNull(fixture.state.retainedAvatarBytesReader(asset(), ACCOUNT_REF))
            val pending = async(Dispatchers.Default) { reader() }
            try {
                withTimeout(5_000) { started.await() }
                fixture.state.clearCrossAccountCachesForTest()
                release.complete(Unit)
                assertNull(withTimeout(5_000) { pending.await() })
                assertEquals(0, fixture.requests.get())
            } finally {
                release.complete(Unit)
            }
        }
}

/** Counts native calls; the only successful byte path is MDK's existing account-pinned local store. */
private class AvatarLocalFixture(
    withRuntime: Boolean = true,
    read: suspend () -> List<AvatarBytesFfi> = { listOf(payload()) },
) {
    val reads = AtomicInteger()
    val requests = AtomicInteger()
    val legacyDownloads = AtomicInteger()
    private val native =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name.substringBefore('-')) {
                "readAvatarAssets" -> {
                    assertEquals(ACCOUNT_REF, arguments!![0])
                    reads.incrementAndGet()
                    val operation: suspend () -> List<AvatarBytesFfi> = { read() }
                    @Suppress("UNCHECKED_CAST")
                    operation.startCoroutine(arguments.last() as Continuation<List<AvatarBytesFfi>>)
                    COROUTINE_SUSPENDED
                }
                "requestAvatarAssets" -> {
                    requests.incrementAndGet()
                    Unit
                }
                "downloadGroupBlossomImage" -> {
                    legacyDownloads.incrementAndGet()
                    error("unexpected encrypted acquisition")
                }
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "toString" -> "AvatarLocalFixture"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected native operation: ${method.name}")
            }
        } as MarmotInterface
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val state =
        WhiteNoiseAppState(
            context = context,
            draftStore =
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts = listOf(AccountSummaryFfi(ACCOUNT_REF, ACCOUNT_ID, true, false, false, true)),
            activeAccountRef = ACCOUNT_REF,
            initialMarmotRuntime = if (withRuntime) AppMarmotRuntime("local-avatar-test", native) else null,
        )
}

/** Actual current-row projection and pinned controller reused by the four production entry points. */
private data class ProductionSurfaceFixture(
    val chats: ChatsController,
    val item: ChatListItem,
    val controller: ConversationController,
)

/** Seeds only MDK selection plus bounded decoded pixels, never URL or encrypted-loader fallback. */
private fun productionSurfaceFixture(
    fixture: AvatarLocalFixture,
    encrypted: Boolean,
    sourceGroup: AppGroupRecordFfi? = null,
    selectedPresentation: ConversationPresentationFfi? = null,
    members: List<dev.ipf.marmotkit.AppGroupMemberRecordFfi> = emptyList(),
): ProductionSurfaceFixture {
    val selected = asset()
    val record =
        sourceGroup ?: if (encrypted) {
            group().copy(imageHashHex = "encrypted-picture")
        } else {
            group().copy(avatarUrl = "https://old.example/picture.png")
        }
    val chats =
        ChatsController(
            fixture.state,
            initialAccountRef = ACCOUNT_REF,
            memberSnapshotLoader = { _, _ -> emptyList() },
        )
    chats.applyChatListRow(
        notificationChatListRow().copy(
            groupIdHex = GROUP_ID,
            groupName = record.name,
            avatarUrl = record.avatarUrl.takeIf { sourceGroup == null },
        ),
    )
    chats.applyLocalGroupUpdate(record)
    ChatsController::class.java
        .getDeclaredField("selectedAvatarAssetsByGroup")
        .apply { isAccessible = true }
        .set(chats, mapOf(GROUP_ID to selected))
    if (selectedPresentation != null) {
        ChatsController::class.java
            .getDeclaredField("selectedPresentationsByGroup")
            .apply { isAccessible = true }
            .set(chats, mapOf(GROUP_ID to selectedPresentation))
    }
    fixture.state.attachChatsController(chats)
    val item = chats.projectChatRow(checkNotNull(chats.chatRows.firstOrNull()))
    val controller =
        ConversationController(
            fixture.state,
            initialGroup = record,
            initialMemberSnapshot = GroupMemberSnapshot(members),
        )
    assertNotNull(
        AvatarImageLoader.decodeAndCache(
            checkNotNull(selected.cacheKey(ACCOUNT_REF)),
            solidPng(),
            AvatarImageLoader.currentCacheLifetime(),
        ),
    )
    return ProductionSurfaceFixture(chats, item, controller)
}

private fun selectedPicture(source: PresentationSourceFfi) =
    ConversationPresentationFfi(
        title = PresentationTextFfi.UnnamedGroup(2uL),
        avatar = SelectedAvatarFfi.RemoteImage(SELECTED_PICTURE_URL, "selected-picture-key"),
        titleSource = PresentationSourceFfi.GROUP,
        avatarSource = source,
        peerId = "peer",
        resolution = PresentationResolutionFfi.CACHED,
    )

private const val SELECTED_PICTURE_URL = "https://example.invalid/selected-picture.png"

/** Production entry points under test, with inert navigation and no backend worker started. */
@Composable
@Suppress("FunctionNaming")
private fun ProductionAvatarSurface(
    step: Int,
    state: WhiteNoiseAppState,
    item: ChatListItem,
    controller: ConversationController,
) {
    when (step) {
        0 -> ChatRow(item, state, onClick = {}, onOpenProfile = {}, interactionsEnabled = false)
        1 ->
            ConversationTopBar(
                selectionMode = false,
                selectedCount = 0,
                onCloseSelection = {},
                searchOpen = false,
                searchQuery = "",
                onSearchQueryChange = {},
                onClearSearch = {},
                onCloseSearch = {},
                onSearchAction = {},
                searchFocusRequester = remember { FocusRequester() },
                appState = state,
                controller = controller,
                groupTitleCopy = GroupTitleCopy.Default,
                openedAsDmHint = false,
                openDetailsDescription = "Group details",
                onOpenDetails = {},
                onBack = {},
            )
        2 -> GroupDetailsScreen(state, controller, onBack = {}, onLeft = {})
        3 -> GroupEditScreen(state, controller, onBack = {})
    }
}

/** Immutable MDK selection for local-store and first-frame tests. */
private fun asset() =
    AvatarAssetFfi(
        "group-target",
        "stored-avatar",
        AvatarAvailabilityFfi.READY,
        AvatarAcquisitionStateFfi.IDLE,
        1uL,
        1_024uL,
    )

/** The payload retains the exact selected reference and revision, independent of legacy URLs. */
private fun payload() =
    AvatarBytesFfi(
        "stored-avatar",
        AvatarAvailabilityFfi.READY,
        1uL,
        1_024uL,
        false,
        solidPng(),
        "image/png",
        64u,
        64u,
    )

/** Tiny solid pixels make the committed production-picker screenshot deterministic. */
private fun solidPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(0x1E, 0x88, 0xE5))
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

private fun group() =
    AppGroupRecordFfi(
        groupIdHex = GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = GROUP_NAME,
        description = "",
        admins = listOf(ACCOUNT_ID),
        relays = emptyList(),
        nostrGroupIdHex = "03".repeat(32),
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia =
            AppGroupEncryptedMediaComponentFfi(
                componentId = 0x8008u,
                component = "marmot.group.encrypted-media.v1",
                required = true,
                version = EncryptedMediaVersionFfi.V1,
                mediaFormat = "encrypted-media-v1",
                allowedLocatorKinds = listOf("blossom-v1"),
                defaultBlobEndpoints =
                    listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.example")),
            ),
        disappearingMessageSecs = 0uL,
        archived = false,
        pendingConfirmation = false,
        unrecoverable = false,
        selfMembership = SelfMembershipFfi.MEMBER,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        disbanding = false,
        disbandRequest = null,
        disbanded = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

private const val ACCOUNT_REF = "personal"
private const val GROUP_NAME = "Stored avatar room"
private val ACCOUNT_ID = "01" + "00".repeat(31)
private val GROUP_ID = "04" + "00".repeat(31)

/** Private account images compose with existing MDK public selection without changing its ownership. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class PrivateContactGroupAvatarTest {
    @get:Rule val composeRule = createComposeRule()

    @Before
    @After
    fun clearPixels() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
        GroupAvatarImageLoader.clear()
    }

    /** A native peer selection must not turn a named group into a contact avatar in details or the actual header. */
    @Test fun namedGroupKeepsItsIdentityWhenNativeSelectionUsesPeerPixels() {
        val fixture = AvatarLocalFixture { emptyList() }
        val production =
            productionSurfaceFixture(
                fixture,
                false,
                group(),
                selectedPicture(PresentationSourceFfi.PEER_PROFILE),
            )
        fixture.state.contactPictureStore.save(
            ACCOUNT_REF,
            "peer",
            "",
            "",
            dev.ipf.whitenoise.android.state.ContactPictureChange.Replace(
                dev.ipf.whitenoise.android.state
                    .contactPicturePng(Color.RED),
            ),
        ) { true }
        val source = checkNotNull(fixture.state.contactAvatarSource("peer"))
        val privateImage = kotlinx.coroutines.runBlocking { AvatarImageLoader.load(source) }
        var detail: ImageBitmap? = null
        try {
            composeRule.setContent {
                val presentation = rememberConversationGroupAvatar(fixture.state, production.controller)
                SideEffect { detail = presentation.image }
                dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme {
                    ProductionAvatarSurface(1, fixture.state, production.item, production.controller)
                }
            }
            composeRule.waitForIdle()
            assertNotNull(privateImage)
            org.junit.Assert.assertNotSame(privateImage, detail)
            val pixels =
                composeRule
                    .onNodeWithTag("conversation.header.avatar", useUnmergedTree = true)
                    .captureToImage()
                    .asAndroidBitmap()
            org.junit.Assert.assertNotEquals(Color.RED, pixels.getPixel(pixels.width / 2, pixels.height / 2))
        } finally {
            production.controller.onCleared()
            production.chats.onCleared()
        }
    }

    /** Rejecting a changed group asset must not reclassify the pair as a private peer picture. */
    @Test fun unavailableGroupOwnedHeaderNeverUsesPrivatePeerFallback() {
        val fixture = AvatarLocalFixture { emptyList() }
        val production =
            productionSurfaceFixture(
                fixture,
                false,
                group().copy(name = "", avatarUrl = "https://example.invalid/old-group.png"),
                selectedPicture(PresentationSourceFfi.PEER_PROFILE),
                members =
                    listOf(
                        dev.ipf.marmotkit.AppGroupMemberRecordFfi(ACCOUNT_ID, ACCOUNT_REF, true),
                        dev.ipf.marmotkit.AppGroupMemberRecordFfi("peer", null, false),
                    ),
            )
        fixture.state.contactPictureStore.save(
            ACCOUNT_REF,
            "peer",
            "",
            "",
            dev.ipf.whitenoise.android.state.ContactPictureChange.Replace(
                dev.ipf.whitenoise.android.state
                    .contactPicturePng(Color.RED),
            ),
        ) { true }
        runBlocking { AvatarImageLoader.load(checkNotNull(fixture.state.contactAvatarSource("peer"))) }
        production.controller.window.install(avatarHeaderFrame(selectedPicture(PresentationSourceFfi.GROUP)))
        assertNull(conversationGroupAvatarAsset(fixture.state, production.controller))
        try {
            composeRule.setContent {
                WhiteNoiseTheme { ProductionAvatarSurface(1, fixture.state, production.item, production.controller) }
            }
            composeRule.waitForIdle()
            val pixels =
                composeRule
                    .onNodeWithTag("conversation.header.avatar", useUnmergedTree = true)
                    .captureToImage()
                    .asAndroidBitmap()
            org.junit.Assert.assertNotEquals(Color.RED, pixels.getPixel(pixels.width / 2, pixels.height / 2))
        } finally {
            production.controller.onCleared()
            production.chats.onCleared()
        }
    }

    /** Private pixels override a native peer selection in both real row and conversation resolvers. */
    @Test
    @Suppress("LongMethod") // One real avatar traverses the row, actual header and group-owned transition.
    fun privateContactPictureOverridesPeerRowsAndHeadersButKeepsGroupOwnedPixels() {
        val fixture = AvatarLocalFixture { emptyList() }
        val source = group().copy(name = "", avatarUrl = null)
        val production =
            productionSurfaceFixture(
                fixture,
                false,
                source,
                selectedPicture(PresentationSourceFfi.PEER_PROFILE),
            )
        val store = fixture.state.contactPictureStore
        store.save(
            ACCOUNT_REF,
            "peer",
            "",
            "",
            dev.ipf.whitenoise.android.state.ContactPictureChange.Replace(
                dev.ipf.whitenoise.android.state
                    .contactPicturePng(Color.RED),
            ),
        ) { true }
        val privateSource = checkNotNull(fixture.state.contactAvatarSource("peer"))
        val privateImage = kotlinx.coroutines.runBlocking { AvatarImageLoader.load(privateSource) }
        var row: ImageBitmap? = null
        var header: ImageBitmap? = null
        var ownGroup: ImageBitmap? = null
        try {
            composeRule.setContent {
                val rowImage =
                    dev.ipf.whitenoise.android.ui.common
                        .rememberChatListGroupAvatar(fixture.state, production.item)
                val headerImage = rememberConversationGroupAvatar(fixture.state, production.controller)
                val groupImage =
                    dev.ipf.whitenoise.android.ui.common.rememberGroupAvatarPresentation(
                        fixture.state,
                        group(),
                        asset(),
                        ACCOUNT_REF,
                        privateSource,
                    )
                SideEffect {
                    row = rowImage.image
                    header = headerImage.image
                    ownGroup = groupImage.image
                }
                dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme {
                    ProductionAvatarSurface(1, fixture.state, production.item, production.controller)
                }
            }
            composeRule.waitForIdle()
            assertNotNull(privateImage)
            assertSame(privateImage, row)
            assertSame(privateImage, header)
            val pixels =
                composeRule
                    .onNodeWithTag("conversation.header.avatar", useUnmergedTree = true)
                    .captureToImage()
                    .asAndroidBitmap()
            assertEquals(Color.RED, pixels.getPixel(pixels.width / 2, pixels.height / 2))
            org.junit.Assert.assertNotSame(privateImage, ownGroup)
            assertNotNull(ownGroup)
            assertEquals(0, fixture.legacyDownloads.get())
        } finally {
            production.controller.onCleared()
            production.chats.onCleared()
        }
    }
}
