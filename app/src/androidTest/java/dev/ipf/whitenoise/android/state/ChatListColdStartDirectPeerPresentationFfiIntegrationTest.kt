package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatListItemTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * #2149 boundary coverage for the stored title and the membership separation, against the real generated
 * MarmotKit runtime. It does not cover the avatar, and it passes on master as well: it guards the MDK
 * contract this issue depends on rather than proving this PR's avatar change. The class and method names
 * are the ones the issue's acceptance criteria name.
 *
 * The fixture establishes an unnamed direct chat through an in-process loopback relay with two generated
 * identities, closes the native runtime, takes the relay away, and reopens the same MDK state the way a
 * relaunched process does: offline, with network profile refresh and roster hydration blocked. The first
 * published chat-list frame must take its title from MDK's stored presentation alone, with nothing seeded
 * into an Android cache first, and that presentation must not fill any membership field.
 *
 * The stored-avatar half is covered by `FirstFrameDurableAvatarsTest`, `AccountSwitchFirstFrameDurableAvatarTest`
 * and the emulator recordings. MDK only acquires HTTPS profile pictures, which a loopback fixture cannot serve.
 */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ChatListColdStartDirectPeerPresentationFfiIntegrationTest {
    /** Process recreation shows the stored peer title on the first frame and fills no membership field. */
    @Test
    @Suppress("LongMethod") // One auditable sequence: establish, go offline, reopen, assert, dispose.
    fun processRecreation_unnamedDirectChat_rendersDurablePeerPresentationOnFirstFrame() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "cold-start-direct-peer-${UUID.randomUUID()}").apply { mkdirs() }
            val relay = LoopbackNostrRelay()
            val relays = listOf(relay.url)
            val established =
                try {
                    withTimeout(ESTABLISH_TIMEOUT_MS) { establishDirectChat(root, relays) }
                } finally {
                    // From here on no relay answers: the relaunch is offline.
                    relay.close()
                }
            val reopened = Marmot.newWithConfiguration(root.absolutePath, relays, loopbackOptions())
            var receiverJob: Job? = null
            var controller: ChatsController? = null
            try {
                withTimeout(REOPEN_TIMEOUT_MS) {
                    val observed =
                        object : MarmotInterface by reopened {
                            /** Fails any network profile refresh, so only stored presentation can name the row. */
                            override suspend fun refreshProfile(
                                accountIdHex: String,
                                relays: List<String>,
                            ): Unit = throw CancellationException("Profile refresh blocked by fixture")
                        }
                    val app =
                        WhiteNoiseAppState(
                            context = context,
                            draftStore = DraftStore(EmptyDraftPersistence),
                            accountIdHexResolver = { established.owner.accountIdHex },
                            accounts = listOf(established.owner, established.peer),
                            activeAccountRef = established.owner.label,
                            marmotRuntimeFactory = { AppMarmotRuntime(root.absolutePath, observed) },
                            notificationSubscriber = {
                                receiverJob = currentCoroutineContext()[Job]
                                SilentNotifications
                            },
                            preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
                        )
                    app.bootstrap()
                    val snapshot =
                        checkNotNull(app.consumeAccountSwitchLocalSnapshot(established.owner.label)) {
                            "startup must hand MDK's stored rows to the first frame"
                        }
                    checkNotNull(snapshot.presentedRows) { "startup must carry MDK's presented rows" }
                    withContext(Dispatchers.Main.immediate) {
                        val firstFrame =
                            ChatsController(
                                appState = app,
                                initialAccountRef = established.owner.label,
                                // Roster hydration never completes: the frame cannot depend on it.
                                memberSnapshotLoader = { _, _ -> awaitCancellation() },
                                initialLocalSnapshot = snapshot,
                            ).also { controller = it }
                        assertFalse(firstFrame.isLoading)
                        val item = firstFrame.items.single()
                        assertEquals(established.groupIdHex, item.id)
                        assertEquals(PEER_NAME, chatListItemTitle(item, { null }, { UNKNOWN }, COPY))
                        // Display-only presentation never becomes membership authority.
                        assertNull(item.memberSnapshot)
                        assertNull(item.otherMemberAccount)
                        assertEquals(0, item.memberCount)
                        assertEquals(established.peer.accountIdHex, item.presentationOtherMemberAccount)
                    }
                }
            } finally {
                withContext(Dispatchers.Main.immediate) { controller?.onCleared() }
                receiverJob?.cancelAndJoin()
                reopened.shutdownAndClose()
                context.deleteSharedPreferences(root.name)
                root.deleteRecursively()
            }
        }

    /** Creates both identities, gives the peer a named profile, and opens an unnamed direct chat. */
    private suspend fun establishDirectChat(
        root: File,
        relays: List<String>,
    ): EstablishedChat {
        val native = Marmot.newWithConfiguration(root.absolutePath, relays, loopbackOptions())
        try {
            native.start()
            val owner = native.createIdentity(relays, relays)
            val peer = native.createIdentity(relays, relays)
            native.publishUserProfile(peer.label, peerProfile(), relays, relays)
            val groupIdHex = native.createGroup(owner.label, "", listOf(peer.accountIdHex), null)
            val row = awaitStoredPeerTitle(native, owner.label, groupIdHex)
            assertEquals(ChatConversationKindFfi.DIRECT, row.row.conversationKind)
            assertEquals("", row.row.groupName)
            assertEquals(peer.accountIdHex, row.presentation.peerId)
            return EstablishedChat(owner = owner, peer = peer, groupIdHex = groupIdHex)
        } finally {
            native.shutdownAndClose()
        }
    }

    /** Waits until MDK has stored the peer's profile name as the row's selected title. */
    private suspend fun awaitStoredPeerTitle(
        native: Marmot,
        accountRef: String,
        groupIdHex: String,
    ): PresentedChatRowFfi {
        while (true) {
            val row = native.presentedChatListRow(accountRef, groupIdHex)
            if (row?.presentation?.title == PresentationTextFfi.Literal(PEER_NAME)) return row
            delay(POLL_MS)
        }
    }

    /** The peer's public profile name, the title the relaunched first frame must keep. */
    private fun peerProfile() =
        UserProfileMetadataFfi(
            name = PEER_NAME,
            displayName = PEER_NAME,
            about = null,
            picture = null,
            banner = null,
            nip05 = null,
            lud16 = null,
        )

    /** Loopback relays are allowed only in this fixture's native configuration. */
    private fun loopbackOptions() = MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS)

    /** What the first process established and the relaunched process must reproduce offline. */
    private data class EstablishedChat(
        val owner: AccountSummaryFfi,
        val peer: AccountSummaryFfi,
        val groupIdHex: String,
    )

    /** Holds the unrelated notification stream open until this test cancels its listener. */
    private object SilentNotifications : AppNotificationSubscription {
        override suspend fun next(): Nothing = awaitCancellation()

        override fun close() = Unit
    }

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ESTABLISH_TIMEOUT_MS = 60_000L
        const val REOPEN_TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
        const val UNKNOWN = "Unknown"
        const val PEER_NAME = "Durable Peer"
        val COPY =
            GroupTitleCopy(
                inviteFromFormat = "Invite from %1\$s",
                groupOfPeopleFormat = "Group of %1\$d people",
                unknownTitle = UNKNOWN,
            )
    }
}
