package dev.ipf.whitenoise.android.state

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Checks failed recipient preflight against real signed events and the shipped native library. */
@RunWith(AndroidJUnit4::class)
class DiscoveryInvitationRejectionFfiTest {
    /** A missing inbox is a recipient-specific error and cannot partly add that recipient. */
    @Test fun missingInboxLeavesMembershipAndPublicationsUntouched() =
        withFixture { fixture ->
            fixture.discovery.hiddenAuthorsByKind = mapOf(INBOX to setOf(fixture.bob))
            fixture.advertised.hiddenAuthorsByKind = mapOf(INBOX to setOf(fixture.bob))
            val error = fixture.rejectWithoutMutation()
            assertTrue("Expected missing inbox, got $error", error is MarmotKitException.MissingMemberInboxRoute)
            assertEquals(fixture.bob, (error as MarmotKitException.MissingMemberInboxRoute).account)
        }

    /** An observed signed deletion cannot be bypassed by falling back to the retained package event. */
    @Test fun deletedDiscoveryPackageIsRejectedWithoutPublication() =
        withFixture { fixture ->
            val packages =
                fixture.discovery.recordedEvents(KEY_PACKAGE).filter { it.getString("pubkey") == fixture.bob }
            assertTrue(packages.isNotEmpty())
            for (event in packages) {
                fixture.peer.deleteAccountKeyPackage(fixture.bob, event.getString("id"), listOf(fixture.discovery.url))
            }
            assertTrue(fixture.discovery.recordedEvents(DELETION).any { it.getString("pubkey") == fixture.bob })
            fixture.rejectWithoutMutation()
        }

    /** Authentication-required discovery is incomplete, and a later explicit retry can recover. */
    @Test fun authRequiredDiscoveryRejectsThenExplicitRetrySucceeds() =
        withFixture { fixture ->
            fixture.discovery.authRequiredKinds = setOf(KEY_PACKAGE, DELETION)
            val before = fixture.discovery.recordedRequests().size
            fixture.rejectWithoutMutation()
            assertTrue(
                fixture.discovery
                    .recordedRequests()
                    .drop(before)
                    .any { it.requests(KEY_PACKAGE, fixture.bob) },
            )
            fixture.discovery.authRequiredKinds = emptySet()
            fixture.sender.inviteMembersDetailed(fixture.alice, fixture.group, listOf(fixture.bob))
            assertEquals(2, fixture.sender.groupMembers(fixture.alice, fixture.group).size)
        }

    /** A usable advertised package never starts a second recovery lookup on discovery for that recipient. */
    @Test fun advertisedPackageSuccessDoesNotUseDiscoveryRecovery() =
        withFixture { fixture ->
            fixture.advertised.hiddenKinds = emptySet()
            fixture.peer.publishNewKeyPackage(fixture.bob)
            val before = fixture.discovery.recordedRequests().size
            fixture.sender.inviteMembersDetailed(fixture.alice, fixture.group, listOf(fixture.bob))
            assertEquals(2, fixture.sender.groupMembers(fixture.alice, fixture.group).size)
            val requests = fixture.discovery.recordedRequests().drop(before)
            // Joining a member also refreshes its profile/relay metadata in a multi-kind query.
            // Recovery makes a dedicated package lookup and a deletion-proof lookup instead.
            assertTrue(
                "Advertised success must not recover via discovery: $requests",
                requests.none {
                    it.requests(DELETION, fixture.bob) ||
                        (it.requests(KEY_PACKAGE, fixture.bob) && it.getJSONArray("kinds").length() == 1)
                },
            )
        }

    /** Each case owns independent native stores and relays; no user's account or relay is contacted. */
    private fun withFixture(block: suspend (Fixture) -> Unit) =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "discovery-rejection-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                LoopbackNostrRelay().use { discovery ->
                    LoopbackNostrRelay().use { advertised ->
                        advertised.hiddenKinds = setOf(KEY_PACKAGE)
                        val options = MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS)
                        val relays = listOf(discovery.url)
                        val sender = Marmot.newWithConfiguration(File(root, "sender").path, relays, options)
                        val peer = Marmot.newWithConfiguration(File(root, "peer").path, relays, options)
                        try {
                            withTimeout(120_000L) {
                                sender.start()
                                peer.start()
                                val alice = sender.createIdentity(relays, relays).accountIdHex
                                val bob = peer.createIdentity(relays, relays).accountIdHex
                                val second = System.currentTimeMillis() / 1000L
                                while (System.currentTimeMillis() / 1000L <= second) delay(25L)
                                peer.publishRelayLists(bob, listOf(advertised.url), relays, relays)
                                val group = sender.createGroup(alice, "Rejected invite fixture", emptyList(), null)
                                block(Fixture(sender, peer, discovery, advertised, alice, bob, group))
                            }
                        } finally {
                            try {
                                sender.shutdownAndClose()
                            } finally {
                                peer.shutdownAndClose()
                            }
                        }
                    }
                }
            } finally {
                check(root.deleteRecursively())
            }
        }

    /** Holds isolated native actors and checks the before/after boundary of one failed invite. */
    private class Fixture(
        val sender: Marmot,
        val peer: Marmot,
        val discovery: LoopbackNostrRelay,
        val advertised: LoopbackNostrRelay,
        val alice: String,
        val bob: String,
        val group: String,
    ) {
        /** A native rejection must preserve the epoch, membership and every attempted Welcome publication. */
        suspend fun rejectWithoutMutation(): Exception {
            val members = sender.groupMembers(alice, group)
            val epoch = sender.groupMlsState(alice, group).epoch
            val before = discovery.publicationAttempts(GIFT_WRAP).size + advertised.publicationAttempts(GIFT_WRAP).size
            val chats = sender.chatList(alice, true).map { it.groupIdHex }.toSet()
            val error = rejected { sender.inviteMembersDetailed(alice, group, listOf(bob)) }
            rejected { sender.createGroup(alice, "Rejected create fixture", listOf(bob), null) }
            assertEquals(chats, sender.chatList(alice, true).map { it.groupIdHex }.toSet())
            assertEquals(members, sender.groupMembers(alice, group))
            assertEquals(epoch, sender.groupMlsState(alice, group).epoch)
            assertEquals(
                before,
                discovery.publicationAttempts(GIFT_WRAP).size + advertised.publicationAttempts(GIFT_WRAP).size,
            )
            return error
        }

        /** Treats cancellation as cancellation, and requires an actual native preflight rejection. */
        private suspend fun rejected(block: suspend () -> Unit): Exception {
            val failure =
                try {
                    block()
                    null
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    error
                }
            return requireNotNull(failure) { "An unsafe recipient must fail preflight" }
        }
    }

    private companion object {
        const val KEY_PACKAGE = 30443
        const val INBOX = 10050
        const val DELETION = 5
        const val GIFT_WRAP = 1059

        /** Distinguishes the selected recipient's package lookup from unrelated background subscriptions. */
        fun JSONObject.requests(
            kind: Int,
            author: String,
        ): Boolean {
            val kinds = optJSONArray("kinds")
            val authors = optJSONArray("authors")
            if (kinds == null || authors == null) return false
            return (0 until kinds.length()).any { kinds.getInt(it) == kind } &&
                (0 until authors.length()).any { authors.getString(it) == author }
        }
    }
}
