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
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.MediaWorkerReadiness
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * #3496: a quick account switch never waits for a decoder worker to start before it publishes the target. The
 * switch resets every decoder worker, and on GrapheneOS a new isolated worker took 0.4–0.6 s to start, so each
 * switch spent the whole 250 ms first-frame avatar budget and still drew initials. The quick switch now waits only
 * for avatars a running worker can decode; the full snapshot path keeps its existing wait.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AccountSwitchInteractiveLatencyCoverageTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    /** Every decode would need a worker to start first. */
    @Before
    fun installColdWorkers() {
        AvatarImageLoader.clear()
        IsolatedMediaDecoder.installTestTransport(ColdWorkers)
    }

    /** Restores the shared JVM codec fixture and leaves no decoded pixels behind. */
    @After
    fun restoreTransport() {
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        AvatarImageLoader.clear()
    }

    /** Only the quick switcher's policy publishes without waiting for cold decoder workers. */
    @Test
    fun onlyTheQuickSwitchSkipsColdAvatarWorkers() {
        for (policy in AccountSwitchPreloadPolicy.entries) {
            val plan = accountSwitchPreloadPlan(switchingAccounts = true, activationStillWanted = true, policy)
            assertEquals(policy.name, policy != INTERACTIVE_LOCAL_ROWS, plan.awaitColdAvatarWorkers)
        }
    }

    /** A quick switch publishes the target before reading any avatar a cold worker would have to decode. */
    @Test
    fun quickSwitchPublishesWithoutReadingColdWorkerAvatars() {
        val readsBeforePublication = switchAndCountTargetReads(INTERACTIVE_LOCAL_ROWS)
        assertEquals("the quick switch must not wait on a cold decoder worker", 0, readsBeforePublication)
    }

    /** The full snapshot path still decodes the target's first-frame avatars before it publishes. */
    @Test
    fun fullSnapshotSwitchStillReadsFirstFrameAvatars() {
        val readsBeforePublication = switchAndCountTargetReads(FULL_LOCAL_SNAPSHOT)
        assertTrue("the full snapshot keeps its first-frame avatar wait", readsBeforePublication > 0)
    }

    /** Switches A to B under [policy] and returns how many of B's stored avatars were read before publication. */
    private fun switchAndCountTargetReads(policy: AccountSwitchPreloadPolicy): Int =
        runBlocking {
            val targetReads = AtomicInteger()
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A, ACCOUNT_A_ID), account(ACCOUNT_B, ACCOUNT_B_ID)),
                    emitStartupNotification = false,
                    onPresentedRows = { accountRef -> listOf(presentedRow(accountRef)) },
                    onReadAvatarAssets = { accountRef, references ->
                        if (accountRef == ACCOUNT_B) targetReads.incrementAndGet()
                        references.map(::storedBytes)
                    },
                )
            try {
                fixture.bootstrap()
                var readsAtPublication = -1
                val activated =
                    withTimeout(SWITCH_TIMEOUT_MS) {
                        fixture.appState.setActiveAccount(
                            ACCOUNT_B,
                            preloadPolicy = policy,
                            onActivated = { readsAtPublication = targetReads.get() },
                        )
                    }
                assertTrue(activated)
                assertFalse("the switch must publish", readsAtPublication < 0)
                readsAtPublication
            } finally {
                fixture.close()
            }
        }

    /** The JVM codec fixture, reporting that no domain has a running worker. */
    private object ColdWorkers :
        MediaDecodeTransport by TestMediaDecodeTransport,
        MediaWorkerReadiness {
        /** No decode can start without a new worker. */
        override fun servableNow(domains: List<MediaTrustDomain>): Set<MediaTrustDomain> = emptySet()
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
        ) = AccountSummaryFfi(label, id, true, false, false, true, false)

        /** Each account's single direct chat, with a peer-sourced stored avatar. */
        fun presentedRow(accountRef: String): PresentedChatRowFfi =
            PresentedChatRowFfi(
                draftVersion = null,
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
        fun storedBytes(reference: String): AvatarBytesFfi {
            val bytes = png()
            return AvatarBytesFfi(
                reference,
                AvatarAvailabilityFfi.READY,
                1uL,
                1_024uL,
                false,
                bytes,
                "image/png",
                64u,
                64u,
            )
        }

        /** A small opaque PNG. */
        fun png(): ByteArray {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        }
    }
}
