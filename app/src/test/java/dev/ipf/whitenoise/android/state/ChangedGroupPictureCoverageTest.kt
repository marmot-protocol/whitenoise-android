package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.AvatarBytesFfi
import dev.ipf.marmotkit.ChatListAvatarFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.ui.chats.AvatarScreenshotFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

/**
 * After a group picture changes, a chat-list row never draws the replaced picture and draws the new one on its
 * first frame once MDK stores it, instead of generated initials while the stored bytes decode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ChangedGroupPictureCoverageTest {
    /** Starts every test with an empty decoded-pixel cache. */
    @Before
    fun clearPixels() {
        AvatarImageLoader.clear()
    }

    /** Leaves no decoded pixels behind. */
    @After
    fun tearDown() {
        AvatarImageLoader.clear()
    }

    /** A dirty presentation that still selects the replaced encrypted picture is not adopted. */
    @Test
    fun dirtyPresentationOfTheReplacedPictureYieldsToTheRowsCurrentPicture() {
        val presentation = groupPresentation(OLD_HASH, PresentationResolutionFfi.LAST_KNOWN)
        val row = groupRow(NEW_HASH)

        assertTrue(presentation.selectsSupersededGroupPicture(row))
        val item = chatListItemFromProjection(row, presentation, asset("old-reference"))

        assertNull("the replaced picture's stored bytes are never adopted", item.selectedAvatarAsset)
        assertEquals("the row shows its current picture", NEW_HASH, item.group.imageHashHex)
    }

    /** A dirty presentation still naming the row's current picture keeps it, as does a recomputed one. */
    @Test
    fun currentOrRecomputedSelectionsAreStillAdopted() {
        val dirtyCurrent = groupPresentation(NEW_HASH, PresentationResolutionFfi.LAST_KNOWN)
        val cached = groupPresentation(OLD_HASH, PresentationResolutionFfi.CACHED)
        val row = groupRow(NEW_HASH)

        assertFalse(dirtyCurrent.selectsSupersededGroupPicture(row))
        assertFalse("only a dirty presentation can be superseded", cached.selectsSupersededGroupPicture(row))
        assertEquals(asset("new"), chatListItemFromProjection(row, dirtyCurrent, asset("new")).selectedAvatarAsset)
        assertEquals(asset("old"), chatListItemFromProjection(row, cached, asset("old")).selectedAvatarAsset)
    }

    /** A row with no avatar signal proves nothing, and a member's picture is not the group's. */
    @Test
    fun rowsWithoutAvatarSignalsAndPeerPicturesAreNotSuperseded() {
        val dirty = groupPresentation(OLD_HASH, PresentationResolutionFfi.LAST_KNOWN)
        val peer =
            dirty.copy(
                avatar = SelectedAvatarFfi.RemoteImage("https://example.invalid/peer.png", "peer"),
                avatarSource = PresentationSourceFfi.PEER_PROFILE,
            )

        assertFalse(dirty.selectsSupersededGroupPicture(groupRow(null)))
        assertFalse(peer.selectsSupersededGroupPicture(groupRow(NEW_HASH)))
    }

    /** A group picture switched from an encrypted image to a URL, or from one URL to another, is superseded. */
    @Test
    fun switchedOrReplacedUrlPicturesAreSuperseded() {
        val dirtyImage = groupPresentation(OLD_HASH, PresentationResolutionFfi.LAST_KNOWN)
        val dirtyUrl =
            dirtyImage.copy(avatar = SelectedAvatarFfi.RemoteImage("https://example.invalid/old.png", "old-url"))
        val urlRow = groupRow(null).copy(avatarUrl = "https://example.invalid/new.png")

        assertTrue(dirtyImage.selectsSupersededGroupPicture(urlRow))
        assertTrue(dirtyUrl.selectsSupersededGroupPicture(urlRow))
        assertTrue(dirtyUrl.selectsSupersededGroupPicture(groupRow(NEW_HASH)))
        assertFalse(dirtyUrl.selectsSupersededGroupPicture(groupRow(null).copy(avatarUrl = "https://example.invalid/old.png")))
    }

    /** Only shown rows whose renderable picture changed, and whose pixels are not decoded, are prepared. */
    @Test
    fun onlyChangedPicturesOfShownRowsArePrepared() {
        val changed = presented(GROUP, asset("new"))
        val stored = presented(OTHER, asset("stored"))
        val missing = presented(MISSING_GROUP, asset("pending", AvatarAvailabilityFfi.MISSING))
        val entering = presented(ENTERING, asset("entering"))
        val shown =
            mapOf(
                GROUP to asset("old"),
                OTHER to asset("stored"),
                MISSING_GROUP to asset("before"),
            )

        val selections = changedDurableAvatarSelections(listOf(changed, stored, missing, entering), ACCOUNT, shown)

        assertEquals(listOf(asset("new")), selections.map { it.first })
    }

    /** A picture MDK has just stored for a row that showed initials is prepared too. */
    @Test
    fun aPictureStoredForARowShowingInitialsIsPrepared() {
        val ready = presented(GROUP, asset("downloaded"))
        val shown = mapOf(GROUP to asset("downloaded", AvatarAvailabilityFfi.MISSING), OTHER to null)

        assertEquals(
            listOf(asset("downloaded")),
            changedDurableAvatarSelections(listOf(ready), ACCOUNT, shown).map { it.first },
        )
    }

    /** Pixels already decoded need no preparation. */
    @Test
    fun alreadyDecodedPicturesAreNotPreparedAgain() {
        AvatarImageLoader.putCached(
            checkNotNull(asset("new").cacheKey(ACCOUNT)),
            AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED),
        )

        assertTrue(
            changedDurableAvatarSelections(listOf(presented(GROUP, asset("new"))), ACCOUNT, mapOf(GROUP to asset("old")))
                .isEmpty(),
        )
    }

    /** The changed picture is decoded from MDK's local store before publication, and nothing is acquired. */
    @Test
    fun aChangedPictureIsDecodedBeforeTheFramePublishes() =
        runBlocking {
            val fixture = NativeAvatars { listOf(payload("new")) }

            withTimeout(SETTLE_TIMEOUT_MS) {
                fixture.state.prepareChangedChatListAvatars(
                    ACCOUNT,
                    listOf(presented(GROUP, asset("new"))),
                    mapOf(GROUP to asset("old")),
                )
            }

            assertEquals(1, fixture.reads.get())
            assertEquals("preparation never starts acquisition", 0, fixture.requests.get())
            assertNotNull(AvatarImageLoader.cachedImage(checkNotNull(asset("new").cacheKey(ACCOUNT))))
        }

    /**
     * Every chat-list projection screens out a superseded selection, and every published window frame prepares its
     * changed pictures before it publishes.
     */
    @Test
    fun projectionAndPublicationKeepTheChangedPictureInvariant() {
        val projection =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/ChatListItem.kt")
                .functionBody("chatListItemFromProjection")
        assertTrue(
            "a superseded selection must neither choose the display group nor the stored asset",
            "selectsSupersededGroupPicture(row)" in projection &&
                "selectedPresentation.takeUnless { supersededPicture }" in projection &&
                "selectedAvatarAsset.takeUnless { supersededPicture }" in projection,
        )
        val publication =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/ChatsControllerRows.kt")
                .functionBody("applyChatListWindowRows")
        val prepared = publication.indexOf("prepareChangedChatListAvatars(")
        assertTrue("changed pictures must be prepared", prepared >= 0)
        assertTrue(
            "changed pictures must be prepared before the frame publishes",
            prepared < publication.indexOf("publishIfCurrent(frame)"),
        )
    }

    /** Counts native avatar calls; bytes come only from MDK's local store. */
    private class NativeAvatars(
        read: suspend () -> List<AvatarBytesFfi>,
    ) {
        val reads = AtomicInteger()
        val requests = AtomicInteger()
        private val native =
            Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) {
                proxy,
                method,
                arguments,
                ->
                when (method.name.substringBefore('-')) {
                    "readAvatarAssets" -> {
                        reads.incrementAndGet()
                        val operation: suspend () -> List<AvatarBytesFfi> = { read() }
                        @Suppress("UNCHECKED_CAST")
                        operation.startCoroutine(arguments!!.last() as Continuation<List<AvatarBytesFfi>>)
                        COROUTINE_SUSPENDED
                    }
                    "requestAvatarAssets" -> requests.incrementAndGet().let { emptyList<AvatarAssetFfi>() }
                    "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    "toString" -> "NativeAvatars"
                    else -> error("Unexpected native operation: ${method.name}")
                }
            } as MarmotInterface
        val state =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext(),
                draftStore = DraftStore(NoDrafts),
                accountIdHexResolver = { ACCOUNT_HEX },
                accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT_HEX, true, false, false, true, false)),
                activeAccountRef = ACCOUNT,
                initialMarmotRuntime = AppMarmotRuntime("changed-group-picture-test", native),
            )
    }

    /** Drafts are irrelevant to row presentation. */
    private object NoDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT = "owner"
        val ACCOUNT_HEX = "00".repeat(32)
        val GROUP = "d1".repeat(16)
        val OTHER = "d2".repeat(16)
        val MISSING_GROUP = "d3".repeat(16)
        val ENTERING = "d4".repeat(16)
        val OLD_HASH = "0a".repeat(32)
        val NEW_HASH = "0b".repeat(32)
        const val SETTLE_TIMEOUT_MS = 5_000L

        /** A named group row whose current encrypted picture has [hash], or none. */
        fun groupRow(
            hash: String?,
            group: String = GROUP,
        ): ChatListRowFfi =
            notificationChatListRow().copy(
                groupIdHex = group,
                avatar = hash?.let(::image),
                lastMessage = null,
            )

        fun image(hash: String) = ChatListAvatarFfi(hash, "1c".repeat(32), "2d".repeat(12), "3e".repeat(32), "image/png")

        /** MDK's group presentation selecting the encrypted picture [hash]. */
        fun groupPresentation(
            hash: String,
            resolution: PresentationResolutionFfi,
        ) = ConversationPresentationFfi(
            title = PresentationTextFfi.Literal("Timeline group"),
            avatar = SelectedAvatarFfi.EncryptedGroupImage(image(hash), "group-image-$hash"),
            titleSource = PresentationSourceFfi.GROUP,
            avatarSource = PresentationSourceFfi.GROUP,
            peerId = null,
            resolution = resolution,
        )

        /** MDK's stored avatar for the group picture [reference]. */
        fun asset(
            reference: String,
            availability: AvatarAvailabilityFfi = AvatarAvailabilityFfi.READY,
        ) = AvatarAssetFfi("group-target", reference, availability, AvatarAcquisitionStateFfi.IDLE, 1uL, 1_024uL)

        /** A group row with a recomputed presentation of its current picture and stored [asset]. */
        fun presented(
            group: String,
            asset: AvatarAssetFfi,
        ) = PresentedChatRowFfi(
            null,
            emptyChatRowPreview(),
            noChatRowActions(),
            groupRow(NEW_HASH, group),
            groupPresentation(NEW_HASH, PresentationResolutionFfi.CACHED),
            asset,
        )

        /** The stored bytes MDK returns for [reference]. */
        fun payload(reference: String) =
            AvatarBytesFfi(reference, AvatarAvailabilityFfi.READY, 1uL, 1_024uL, false, png(), "image/png", 64u, 64u)

        /** A small opaque PNG. */
        fun png(): ByteArray {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(0x1E, 0x88, 0xE5))
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
