package dev.ipf.whitenoise.android.state

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the published Kotlin/native pair through actual signed invitation packages on isolated relays. */
@RunWith(AndroidJUnit4::class)
class DiscoveryInvitationFfiIntegrationTest {
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
