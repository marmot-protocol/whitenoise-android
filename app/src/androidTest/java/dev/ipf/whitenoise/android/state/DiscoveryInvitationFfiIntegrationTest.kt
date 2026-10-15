package dev.ipf.whitenoise.android.state

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.MdkAdoptionDeviceAcceptance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the published Kotlin/native pair through actual signed invitation packages on isolated relays. */
@RunWith(AndroidJUnit4::class)
@MdkAdoptionDeviceAcceptance
class DiscoveryInvitationFfiIntegrationTest {
    /** Recipient-side capabilities must come from the Welcome, without a later promotion. */
    @Test
    @Suppress("LongMethod") // Keep both native stores, one invitation and recipient acceptance together.
    fun initialAdminRolesSurviveWelcomeForBothRecipients() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "initial-admin-welcome-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                LoopbackNostrRelay().use { relay ->
                    val relays = listOf(relay.url)
                    val options = MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS)
                    val sender = Marmot.newWithConfiguration(File(root, "sender").path, relays, options)
                    val peer = Marmot.newWithConfiguration(File(root, "peer").path, relays, options)
                    try {
                        withTimeout(120_000L) {
                            sender.start()
                            peer.start()
                            val owner = sender.createIdentity(relays, relays)
                            val admin = peer.createIdentity(relays, relays)
                            val member = peer.createIdentity(relays, relays)
                            val group = sender.createGroup(owner.label, "Initial roles", emptyList(), null)
                            sender.inviteMembersDetailedWithInitialAdmins(
                                owner.label,
                                group,
                                listOf(admin.accountIdHex, member.accountIdHex),
                                listOf(admin.accountIdHex),
                            )
                            awaitInvite(peer, admin.label, group)
                            awaitInvite(peer, member.label, group)
                            val adminView = peer.groupConversationSnapshot(admin.label, group)
                            val memberView = peer.groupConversationSnapshot(member.label, group)
                            assertEquals(group, adminView.details.group.groupIdHex)
                            assertEquals(group, memberView.details.group.groupIdHex)
                            assertEquals(admin.accountIdHex, adminView.managementState.myAccountIdHex)
                            assertEquals(member.accountIdHex, memberView.managementState.myAccountIdHex)
                            assertTrue(adminView.managementState.isSelfAdmin)
                            assertTrue(adminView.managementState.canInvite)
                            assertFalse(memberView.managementState.isSelfAdmin)
                            assertFalse(memberView.managementState.canInvite)
                            val expectedMembers =
                                setOf(
                                    requireNotNull(sender.npub(owner.accountIdHex)),
                                    requireNotNull(peer.npub(admin.accountIdHex)),
                                    requireNotNull(peer.npub(member.accountIdHex)),
                                )
                            val adminMembers =
                                adminView.details.members
                                    .map { it.npub }
                                    .toSet()
                            val memberMembers =
                                memberView.details.members
                                    .map { it.npub }
                                    .toSet()
                            assertEquals(expectedMembers, adminMembers)
                            assertEquals(expectedMembers, memberMembers)
                        }
                    } finally {
                        try {
                            sender.shutdownAndClose()
                        } finally {
                            peer.shutdownAndClose()
                        }
                    }
                }
            } finally {
                check(root.deleteRecursively())
            }
        }

    /** Both Android group commands recover a package unavailable from the advertised outbox. */
    @Test fun createAndAddMemberRecoverDiscoveryOnlyPackages() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "discovery-invite-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                LoopbackNostrRelay().use { discovery ->
                    LoopbackNostrRelay().use { advertised ->
                        advertised.hiddenKinds = setOf(KEY_PACKAGE)
                        val relays = listOf(discovery.url)
                        val options = MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS)
                        val sender = Marmot.newWithConfiguration(File(root, "sender").path, relays, options)
                        val peer = Marmot.newWithConfiguration(File(root, "peer").path, relays, options)
                        try {
                            withTimeout(120_000L) {
                                sender.start()
                                peer.start()
                                val alice = sender.createIdentity(relays, relays)
                                val bob = peer.createIdentity(relays, relays)
                                val carol = peer.createIdentity(relays, relays)
                                // The later signed NIP-65 event advertises a route with no visible package.
                                val publishedSecond = System.currentTimeMillis() / 1000L
                                while (System.currentTimeMillis() / 1000L <= publishedSecond) delay(25L)
                                for (account in listOf(bob, carol)) {
                                    peer.publishRelayLists(account.label, listOf(advertised.url), relays, relays)
                                }
                                assertTrue(discovery.recordedEvents(KEY_PACKAGE).isNotEmpty())
                                val before = discovery.kindReads[KEY_PACKAGE]?.get() ?: 0
                                val group =
                                    sender.createGroup(
                                        alice.label,
                                        "Discovery recovery",
                                        listOf(bob.accountIdHex),
                                        null,
                                    )
                                awaitInvite(peer, bob.label, group)
                                sender.inviteMembersDetailed(alice.label, group, listOf(carol.accountIdHex))
                                awaitInvite(peer, carol.label, group)
                                assertEquals(3, sender.groupMembers(alice.label, group).size)
                                assertTrue((advertised.kindReads[KEY_PACKAGE]?.get() ?: 0) > 0)
                                assertTrue((discovery.kindReads[KEY_PACKAGE]?.get() ?: 0) > before)
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

    /** Waits for the real Welcome to arrive; a sender-only roster update is not recipient acceptance. */
    private suspend fun awaitInvite(
        native: Marmot,
        account: String,
        group: String,
    ) {
        var lastError: Exception? = null
        val accepted =
            withTimeoutOrNull(INVITE_TIMEOUT_MS) {
                var pending = true
                while (pending) {
                    try {
                        native.acceptGroupInvite(account, group)
                        pending = false
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        lastError = error
                        delay(100L)
                    }
                }
                true
            }
        check(accepted == true) { "Invite for account $account in group $group was not accepted: $lastError" }
        assertTrue(native.chatList(account, true).any { it.groupIdHex == group })
    }

    private companion object {
        const val INVITE_TIMEOUT_MS = 30_000L
        const val KEY_PACKAGE = 30443
    }
}
