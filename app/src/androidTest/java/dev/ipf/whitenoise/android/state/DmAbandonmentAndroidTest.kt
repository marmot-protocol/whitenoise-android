package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * #3310 production flow against the real generated MarmotKit runtime: a newly started DM whose peer has not
 * accepted, an admin handover attempt whose outcome must not matter, the confirmed abandonment through Android's
 * journaled forget boundary, delivery of old-group events from the peer, and a process restart.
 *
 * A list-row disappearance alone is not the assertion. The test reads the native row and messages for the old
 * group after the forget, after the peer accepts and sends into the old group, and again after reopening the same
 * MDK state, and it checks that the abandonment itself published nothing to the relay.
 *
 * Both identities live in one runtime behind an in-process loopback relay, so no external relay or account is
 * needed. It stays a manual fixture until its first green device run is recorded for this MarmotKit pin.
 */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class DmAbandonmentAndroidTest {
    /** Abandonment succeeds after the handover attempt and keeps the old DM forgotten through delivery and restart. */
    @Test
    @Suppress("LongMethod") // One auditable sequence: start the DM, abandon it, deliver, restart, dispose.
    fun newlyStartedDm_abandonedAfterHandoverAttempt_staysForgottenThroughDeliveryAndRestart() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "dm-abandonment-${UUID.randomUUID()}").apply { mkdirs() }
            val relay = LoopbackNostrRelay()
            val relays = listOf(relay.url)
            var native = Marmot.newWithConfiguration(root.absolutePath, relays, loopbackOptions())
            try {
                native.start()
                val owner = native.createIdentity(relays, relays)
                val peer = native.createIdentity(relays, relays)
                val groupIdHex = withTimeout(ESTABLISH_TIMEOUT_MS) { startDirectChat(native, owner, peer) }

                // The peer has not accepted yet — whether MDK refuses the handover must not affect abandonment.
                val handover = runCatching { native.promoteAdminDetailed(owner.label, groupIdHex, peer.accountIdHex) }
                Log.i(TAG, "handover before acceptance succeeded=${handover.isSuccess}")

                val app = appState(context, root, native, owner, peer)
                val publishedBefore = relay.publicationAttempts(MLS_GROUP_EVENT_KIND).size
                val reset = app.forgetGroupLocalWithClientCleanup(owner.label, groupIdHex) { true }
                assertTrue("the first confirmation resets the native group", reset)
                assertEquals(
                    "abandonment sends no leave, admin change or other group event",
                    publishedBefore,
                    relay.publicationAttempts(MLS_GROUP_EVENT_KIND).size,
                )
                assertForgotten(native, owner, groupIdHex, "after the forget")
                assertFalse("committed cleanup is not pending", app.localGroupDeleteCleanupJournal.hasPending())

                // Old-group delivery: the peer joins the old group and writes into it.
                runCatching { native.acceptGroupInvite(peer.label, groupIdHex) }
                    .onFailure { Log.i(TAG, "peer acceptance failed type=${it.javaClass.simpleName}") }
                runCatching { native.sendText(peer.label, groupIdHex, "into the abandoned chat") }
                    .onFailure { Log.i(TAG, "peer send failed type=${it.javaClass.simpleName}") }
                native.catchUpAccounts()
                delay(DELIVERY_SETTLE_MS)
                assertForgotten(native, owner, groupIdHex, "after old-group delivery")

                // Restart: reopen the same MDK state and replay delivery from the relay.
                native.shutdownAndClose()
                native = Marmot.newWithConfiguration(root.absolutePath, relays, loopbackOptions())
                native.start()
                native.catchUpAccounts()
                delay(DELIVERY_SETTLE_MS)
                assertForgotten(native, owner, groupIdHex, "after restart and replay")
                assertTrue(
                    "a repeated confirmation reports that MDK is already waiting for a fresh Welcome",
                    !appState(context, root, native, owner, peer)
                        .forgetGroupLocalWithClientCleanup(owner.label, groupIdHex) { true },
                )
            } finally {
                runCatching { native.shutdownAndClose() }
                relay.close()
                context.deleteSharedPreferences(root.name)
                root.deleteRecursively()
            }
        }

    /** Creates the unnamed direct chat the New chat flow creates, and waits for its stored row. */
    private suspend fun startDirectChat(
        native: Marmot,
        owner: AccountSummaryFfi,
        peer: AccountSummaryFfi,
    ): String {
        val groupIdHex = native.createGroup(owner.label, "", listOf(peer.accountIdHex), null)
        while (native.chatListRow(owner.label, groupIdHex)?.conversationKind != ChatConversationKindFfi.DIRECT) {
            delay(POLL_MS)
        }
        return groupIdHex
    }

    /** Native, not list-row, evidence: no row, no listed chat and no readable history for the owner. */
    private fun assertForgotten(
        native: Marmot,
        owner: AccountSummaryFfi,
        groupIdHex: String,
        stage: String,
    ) {
        assertNull("no native row $stage", native.chatListRow(owner.label, groupIdHex))
        assertTrue(
            "the chat list does not resurrect the chat $stage",
            native.chatList(owner.label, true).none { it.groupIdHex.equals(groupIdHex, ignoreCase = true) },
        )
        val history = runCatching { native.messages(owner.label, groupIdHex, HISTORY_LIMIT, null) }.getOrNull()
        assertTrue("no old-group history is readable $stage", history.isNullOrEmpty())
    }

    /** The production app state over this runtime, owning the forget boundary and its cleanup journal. */
    private fun appState(
        context: Context,
        root: File,
        native: Marmot,
        owner: AccountSummaryFfi,
        peer: AccountSummaryFfi,
    ) = WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore.forContext(context),
        accountIdHexResolver = { owner.accountIdHex },
        accounts = listOf(owner, peer),
        activeAccountRef = owner.label,
        initialMarmotRuntime = AppMarmotRuntime(root.absolutePath, native),
        preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
    )

    /** Loopback relays are allowed only in this fixture's native configuration. */
    private fun loopbackOptions() = MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS)

    private companion object {
        const val TAG = "DmAbandonmentTest"
        const val ESTABLISH_TIMEOUT_MS = 60_000L
        const val DELIVERY_SETTLE_MS = 5_000L
        const val POLL_MS = 100L
        const val MLS_GROUP_EVENT_KIND = 445
        const val HISTORY_LIMIT = 50u
    }
}
