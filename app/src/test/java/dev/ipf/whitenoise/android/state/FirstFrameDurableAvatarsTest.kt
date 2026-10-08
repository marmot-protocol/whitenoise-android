package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.AvatarBytesFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * #2149: a cold start's first chat-list frame draws the peer's stored avatar, not generated initials. The
 * restored snapshot decodes MDK's stored bytes for the first visible rows before it publishes, so the
 * controller's first-frame seed finds pixels; a slow read gives up within the budget instead of holding
 * the frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FirstFrameDurableAvatarsTest {
    private val controllers = mutableListOf<ChatsController>()

    /** Starts every test with an empty decoded-pixel cache. */
    @Before
    fun clearPixels() {
        AvatarImageLoader.clear()
    }

    /** Releases controllers and decoded pixels. */
    @After
    fun tearDown() {
        controllers.forEach(ChatsController::onCleared)
        AvatarImageLoader.clear()
    }

    /** Only renderable assets of visible rows are decoded, pinned first, then by most recent activity. */
    @Test
    fun firstFrameAssetsFollowVisibleOrderAndSkipArchivedOrUnrenderableRows() {
        val rows =
            listOf(
                presented(row("old", activity = 1uL), asset("old")),
                presented(row("archived", activity = 9uL).copy(archived = true), asset("archived")),
                presented(row("missing", activity = 8uL), asset("missing", AvatarAvailabilityFfi.MISSING)),
                presented(row("recent", activity = 5uL), asset("recent")),
                presented(row("pinned", activity = 0uL).copy(pinned = true), asset("pinned")),
                presented(row("none", activity = 7uL), null),
            )

        assertEquals(
            listOf("pinned", "recent", "old"),
            firstFrameDurableAvatarAssets(rows).map(AvatarAssetFfi::target),
        )
    }

    /**
     * Past the 24-row window, a pending invitation and the chat at manual pin position 0 sort first in the
     * visible list even though 25 other pinned chats have newer activity, so both must be decoded.
     */
    @Test
    fun pendingInvitationAndFirstManualPinAreDecodedPastTheWindow() {
        val pinnedByActivity =
            (1..FIRST_FRAME_DURABLE_AVATAR_ROWS + 1).map { position ->
                pinnedRow("pinned-$position", position.toUInt(), activity = 1_000uL + position.toULong())
            }
        val firstPin = pinnedRow(FIRST_PIN, 0u, activity = 1uL)
        val pending = row(PENDING, activity = 2uL).copy(pendingConfirmation = true)
        val rows = (pinnedByActivity + firstPin + pending).map { presented(it, asset(it.groupIdHex)) }

        val selected = firstFrameDurableAvatarAssets(rows).map(AvatarAssetFfi::target)

        assertEquals(FIRST_FRAME_DURABLE_AVATAR_ROWS, selected.size)
        assertEquals(listOf(PENDING, FIRST_PIN), selected.take(2))
    }

    /** Past the 24-row window, a chat raised by a newer draft sorts first and must be decoded. */
    @Test
    fun draftRaisedChatIsDecodedPastTheWindow() {
        val newer =
            (1..FIRST_FRAME_DURABLE_AVATAR_ROWS + 6).map { index ->
                row("recent-$index", activity = 1_000uL + index.toULong())
            }
        val drafted = row(DRAFTED, activity = 1uL)
        val rows = (newer + drafted).map { presented(it, asset(it.groupIdHex)) }

        val withoutDraft = firstFrameDurableAvatarAssets(rows).map(AvatarAssetFfi::target)
        val withDraft =
            firstFrameDurableAvatarAssets(rows) { id -> 10_000uL.takeIf { id == DRAFTED } }.map(AvatarAssetFfi::target)

        assertTrue("an old chat without a draft stays outside the window", DRAFTED !in withoutDraft)
        assertEquals(DRAFTED, withDraft.first())
        assertEquals(FIRST_FRAME_DURABLE_AVATAR_ROWS, withDraft.size)
    }

    /** The window is bounded to the rows the first frame seeds. */
    @Test
    fun firstFrameAssetsAreBoundedToTheSeededRows() {
        val rows =
            (0 until FIRST_FRAME_DURABLE_AVATAR_ROWS + 5).map { presented(row("g$it", it.toULong()), asset("g$it")) }

        assertEquals(FIRST_FRAME_DURABLE_AVATAR_ROWS, firstFrameDurableAvatarAssets(rows).size)
    }

    /** Pre-existing stored bytes are decoded before publication, so the first frame seeds the avatar. */
    @Test
    fun restoredDirectRowSeedsTheStoredAvatarOnItsFirstFrame() {
        val storedBytes = payload()
        val fixture = NativeAvatars { listOf(storedBytes) }
        val stored = presented(row(GROUP), asset(GROUP), peerPresentation())

        runBlocking { fixture.state.prewarmFirstFrameDurableAvatars(ACCOUNT, listOf(stored)) }

        assertEquals(1, fixture.reads.get())
        assertEquals("a restored frame never starts acquisition", 0, fixture.requests.get())
        assertNotNull(AvatarImageLoader.cachedImage(checkNotNull(asset(GROUP).cacheKey(ACCOUNT))))
        val item = firstFrame(fixture.state, stored).items.single()
        assertEquals(asset(GROUP), item.selectedAvatarAsset)
        assertEquals(ChatListAvatarSource.DURABLE, item.firstFrameAvatar?.source)
        assertNull(item.memberSnapshot)
    }

    /** A read that never answers cannot hold the restored frame past the budget. */
    @Test
    fun slowStoredReadGivesUpWithinTheBudget() {
        val fixture = NativeAvatars { awaitCancellation() }
        val stored = presented(row(GROUP), asset(GROUP), peerPresentation())

        val started = System.nanoTime()
        runBlocking { fixture.state.prewarmFirstFrameDurableAvatars(ACCOUNT, listOf(stored)) }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS

        assertTrue("prewarm took ${elapsedMs}ms", elapsedMs < FIRST_FRAME_DURABLE_AVATAR_BUDGET_MS * 4)
        assertNull(AvatarImageLoader.cachedImage(checkNotNull(asset(GROUP).cacheKey(ACCOUNT))))
        assertNull(firstFrame(fixture.state, stored).items.single().firstFrameAvatar)
    }

    /** Builds a controller whose first frame is the restored MDK snapshot. */
    private fun firstFrame(
        state: WhiteNoiseAppState,
        vararg rows: PresentedChatRowFfi,
    ): ChatsController =
        ChatsController(
            appState = state,
            initialAccountRef = ACCOUNT,
            memberSnapshotLoader = { _, _ -> awaitCancellation() },
            initialLocalSnapshot =
                AccountSwitchLocalSnapshot(
                    accountRef = ACCOUNT,
                    activeAccountIdHex = ACCOUNT_HEX,
                    rows = rows.map(PresentedChatRowFfi::row),
                    groups = emptyList(),
                    memberIds = emptyList(),
                    profiles = emptyList(),
                    presentedRows = rows.toList(),
                ),
        ).also(controllers::add)

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
                accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT_HEX, true, false, false, true)),
                activeAccountRef = ACCOUNT,
                initialMarmotRuntime = AppMarmotRuntime("first-frame-avatar-test", native),
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
        val PEER = "aa".repeat(32)
        const val NANOS_PER_MS = 1_000_000L
        const val FIRST_PIN = "first-pin"
        const val PENDING = "pending-invitation"
        const val DRAFTED = "drafted"

        /** An unnamed direct row as MDK stores it. */
        fun row(
            group: String,
            activity: ULong = 2uL,
        ): ChatListRowFfi =
            notificationChatListRow().copy(
                groupIdHex = group,
                title = "",
                groupName = "",
                conversationKind = ChatConversationKindFfi.DIRECT,
                activitySortAt = activity,
                lastMessage = null,
            )

        /** A pinned unnamed direct row at engine-normalized manual [position]. */
        fun pinnedRow(
            group: String,
            position: UInt,
            activity: ULong,
        ): ChatListRowFfi = row(group, activity).copy(pinned = true, pinnedPosition = position)

        /** MDK's stored avatar selection for [target]. */
        fun asset(
            target: String,
            availability: AvatarAvailabilityFfi = AvatarAvailabilityFfi.READY,
        ) = AvatarAssetFfi(target, "stored-$target", availability, AvatarAcquisitionStateFfi.IDLE, 1uL, 1_024uL)

        /** The stored bytes MDK returns for the [GROUP] asset. */
        fun payload() =
            AvatarBytesFfi(
                "stored-$GROUP",
                AvatarAvailabilityFfi.READY,
                1uL,
                1_024uL,
                false,
                png(),
                "image/png",
                64u,
                64u,
            )

        /** MDK's cached direct-peer presentation with a peer-sourced picture. */
        fun peerPresentation() =
            ConversationPresentationFfi(
                title = PresentationTextFfi.Literal("Durable Peer"),
                avatar = SelectedAvatarFfi.RemoteImage("https://example.invalid/peer.png", "peer-picture"),
                titleSource = PresentationSourceFfi.PEER_PROFILE,
                avatarSource = PresentationSourceFfi.PEER_PROFILE,
                peerId = PEER,
                resolution = PresentationResolutionFfi.CACHED,
            )

        /** Pairs a row with its presentation and stored avatar. */
        fun presented(
            row: ChatListRowFfi,
            asset: AvatarAssetFfi?,
            presentation: ConversationPresentationFfi = peerPresentation(),
        ) = PresentedChatRowFfi(null, emptyChatRowPreview(), noChatRowActions(), row, presentation, asset)

        /** A small opaque PNG. */
        fun png(): ByteArray {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(0x1E, 0x88, 0xE5))
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
