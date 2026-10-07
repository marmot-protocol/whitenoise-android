package dev.ipf.whitenoise.android.state

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.AvatarBytesFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * #2149 on an interactive account switch. The switch decodes the destination account's stored avatars
 * before it clears the previous account's stored pixels, so the decoded pixels must be put back when the
 * destination snapshot is staged. The destination's first published snapshot must then find its avatar in
 * the cache, and the previous account's stored pixels must be gone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AccountSwitchFirstFrameDurableAvatarTest {
    private val context: Application = RuntimeEnvironment.getApplication()

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

    /** The full-snapshot switch path keeps the destination's decoded avatars for its first frame. */
    @Test
    fun fullSnapshotSwitchPublishesDestinationAvatarsInTheCache() = assertSwitchKeeps(FULL_LOCAL_SNAPSHOT)

    /** The quick account switcher's path keeps the destination's decoded avatars for its first frame. */
    @Test
    fun interactiveSwitchPublishesDestinationAvatarsInTheCache() = assertSwitchKeeps(INTERACTIVE_LOCAL_ROWS)

    /** Switches A to B under [policy] and inspects the cache at the moment B's snapshot is published. */
    @Suppress("LongMethod") // One ordered lifecycle: cold start A, switch to B, inspect B's first frame.
    private fun assertSwitchKeeps(policy: AccountSwitchPreloadPolicy) =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A, ACCOUNT_A_ID), account(ACCOUNT_B, ACCOUNT_B_ID)),
                    emitStartupNotification = false,
                    onPresentedRows = { accountRef -> listOf(presentedRow(accountRef)) },
                    onReadAvatarAssets = { _, references -> references.map(::storedBytes) },
                )
            try {
                fixture.bootstrap()
                // This regression owns switch publication, not the best-effort 250ms cold-decode budget.
                for (accountRef in listOf(ACCOUNT_A, ACCOUNT_B)) {
                    assertNotNull(fixture.appState.durableAvatar(asset(accountRef), accountRef, acquireMissing = false))
                }
                val keyA = checkNotNull(asset(ACCOUNT_A).cacheKey(ACCOUNT_A))
                val keyB = checkNotNull(asset(ACCOUNT_B).cacheKey(ACCOUNT_B))
                assertNotNull(
                    "the source account must hold its stored avatar before switching",
                    AvatarImageLoader.cachedImage(keyA),
                )

                var snapshot: AccountSwitchLocalSnapshot? = null
                var destinationCached = false
                var previousCached = true
                val activated =
                    withTimeout(SWITCH_TIMEOUT_MS) {
                        fixture.appState.setActiveAccount(
                            ACCOUNT_B,
                            preloadPolicy = policy,
                            onActivated = {
                                destinationCached = AvatarImageLoader.cachedImage(keyB) != null
                                previousCached = AvatarImageLoader.cachedImage(keyA) != null
                                snapshot = fixture.appState.consumeAccountSwitchLocalSnapshot(ACCOUNT_B)
                            },
                        )
                    }

                assertTrue(activated)
                assertTrue("B's stored avatar must survive the switch's cache clear", destinationCached)
                assertTrue("A's stored pixels must not leak into B's session", !previousCached)
                val staged = checkNotNull(snapshot) { "the switch must stage B's snapshot" }
                assertEquals(listOf(keyB), staged.firstFrameAvatars.map(FirstFrameDurableAvatar::key))
                val controller =
                    ChatsController(
                        appState = fixture.appState,
                        initialAccountRef = ACCOUNT_B,
                        memberSnapshotLoader = { _, _ -> awaitCancellation() },
                        initialLocalSnapshot = staged,
                    )
                try {
                    val item = controller.items.single()
                    assertEquals(ChatListAvatarSource.DURABLE, item.firstFrameAvatar?.source)
                    assertEquals(keyB, item.firstFrameAvatar?.key)
                    assertNull(item.memberSnapshot)
                } finally {
                    controller.onCleared()
                }
            } finally {
                fixture.close()
            }
        }

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val SWITCH_TIMEOUT_MS = 5_000L
        val ACCOUNT_A_ID = "0a".repeat(32)
        val ACCOUNT_B_ID = "0b".repeat(32)

        /** A signed-in local-signing account. */
        fun account(
            label: String,
            id: String,
        ) = AccountSummaryFfi(label, id, true, false, false, true)

        /** Each account's single unnamed direct chat, with a peer-sourced stored avatar. */
        fun presentedRow(accountRef: String): PresentedChatRowFfi =
            PresentedChatRowFfi(
                preview = emptyChatRowPreview(),
                actions = noChatRowActions(),
                row =
                    notificationChatListRow().copy(
                        groupIdHex = (if (accountRef == ACCOUNT_A) "da" else "db").repeat(32),
                        title = "",
                        groupName = "",
                        conversationKind = ChatConversationKindFfi.DIRECT,
                        lastMessage = null,
                    ),
                presentation =
                    ConversationPresentationFfi(
                        title = PresentationTextFfi.Literal("Peer of $accountRef"),
                        avatar = SelectedAvatarFfi.RemoteImage("https://example.invalid/$accountRef.png", accountRef),
                        titleSource = PresentationSourceFfi.PEER_PROFILE,
                        avatarSource = PresentationSourceFfi.PEER_PROFILE,
                        peerId = "peer-$accountRef",
                        resolution = PresentationResolutionFfi.CACHED,
                    ),
                avatarAsset = asset(accountRef),
            )

        /** The stored avatar MDK selects for [accountRef]'s peer. */
        fun asset(accountRef: String) =
            AvatarAssetFfi(
                "peer-$accountRef",
                "stored-$accountRef",
                AvatarAvailabilityFfi.READY,
                AvatarAcquisitionStateFfi.IDLE,
                1uL,
                1_024uL,
            )

        /** The bytes MDK returns for a stored reference. */
        fun storedBytes(reference: String) =
            AvatarBytesFfi(
                reference,
                AvatarAvailabilityFfi.READY,
                1uL,
                1_024uL,
                false,
                png(if (reference.endsWith(ACCOUNT_B)) Color.RED else Color.GREEN),
                "image/png",
                64u,
                64u,
            )

        /** A small opaque PNG of one [color]. */
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
