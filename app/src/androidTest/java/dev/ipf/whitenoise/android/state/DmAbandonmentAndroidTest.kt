package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
 * accepted, an admin handover that MDK refuses, the confirmed abandonment through Android's
 * journaled forget boundary, delivery of old-group events from the peer, and a process restart.
 *
 * A list-row disappearance alone is not the assertion. The test reads the native row and messages for the old
 * group after the forget, after the peer accepts and sends into the old group, and again after reopening the same
 * MDK state, and it checks that the abandonment itself published nothing to the relay. The old-group delivery must
 * really happen: the peer reads its own message and the relay records it, so an empty owner state is never the
 * result of a delivery that was silently skipped. Swapping the forget for a local delete resurrects the chat here.
 *
 * Both identities live in one runtime behind an in-process loopback relay, so no external relay or account is
 * needed, and drafts stay in memory.
 */
@RunWith(AndroidJUnit4::class)
class DmAbandonmentAndroidTest {
    /** Abandonment succeeds after a refused handover and keeps the old DM forgotten through delivery and restart. */
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

                // The reported dead end: handing admin to the peer who has not accepted. Should MDK allow that
                // handover, granting admin to an account outside the chat is refused instead, so the abandonment
                // below always follows a refused handover.
                val handover =
                    runCatching { native.promoteAdminDetailed(owner.label, groupIdHex, peer.accountIdHex) }
                        .takeIf { it.isFailure }
                        ?: runCatching { native.promoteAdminDetailed(owner.label, groupIdHex, NON_MEMBER_ID) }
                assertTrue("the handover is refused before abandonment", handover.isFailure)
                Log.i(TAG, "handover refused type=${handover.exceptionOrNull()?.javaClass?.simpleName}")

                val app = appState(context, root, native, owner, peer)
                val publishedBefore = relay.publicationAttempts(MLS_GROUP_EVENT_KIND).size
                // The confirmation, as the dialog submits it.
                val reset = whenWorkerIdle { app.forgetGroupLocalWithClientCleanup(owner.label, groupIdHex) { true } }
                assertTrue("the first confirmation resets the native group", reset)
                assertEquals(
                    "abandonment sends no leave, admin change or other group event",
                    publishedBefore,
                    relay.publicationAttempts(MLS_GROUP_EVENT_KIND).size,
                )
                assertForgotten(native, owner, groupIdHex, "after the forget")
                assertFalse("committed cleanup is not pending", app.localGroupDeleteCleanupJournal.hasPending())

                // Old-group delivery: the peer joins the old group and writes into it. Each step must really happen,
                // or the owner's empty state below would prove nothing.
                val publishedAfterForget = relay.publicationAttempts(MLS_GROUP_EVENT_KIND).size
                whenWorkerIdle { native.acceptGroupInvite(peer.label, groupIdHex) }
                whenWorkerIdle { native.sendText(peer.label, groupIdHex, OLD_GROUP_TEXT) }
                assertTrue("the peer itself reads the message it sent", peerReads(native, peer, groupIdHex))
                assertTrue(
                    "the peer's message reached the relay as an old-group event",
                    awaitPublication(relay, publishedAfterForget),
                )
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

    /**
     * Runs [call] once the account's native worker accepts it. A worker still finishing earlier work answers
     * [MarmotKitException.AccountWorkerBusy] before anything commits, which the app shows as a retryable failure. Only
     * that answer is retried, within [ESTABLISH_TIMEOUT_MS], so every other failure still fails the test.
     */
    private suspend fun <T> whenWorkerIdle(call: suspend () -> T): T =
        withTimeout(ESTABLISH_TIMEOUT_MS) {
            var result: Result<T>
            while (true) {
                result = runCatching { call() }
                val busy = result.exceptionOrNull() as? MarmotKitException.AccountWorkerBusy ?: break
                Log.i(TAG, "worker busy, retrying type=${busy.javaClass.simpleName}")
                delay(POLL_MS)
            }
            result.getOrThrow()
        }

    /** Whether [peer]'s own history holds the old-group message, so its delivery was real rather than skipped. */
    private suspend fun peerReads(
        native: Marmot,
        peer: AccountSummaryFfi,
        groupIdHex: String,
    ): Boolean =
        withTimeout(ESTABLISH_TIMEOUT_MS) {
            val history = { native.messages(peer.label, groupIdHex, HISTORY_LIMIT, null) }
            while (history().none { it.plaintext == OLD_GROUP_TEXT }) delay(POLL_MS)
            true
        }

    /** Whether the relay records a group event beyond [before] attempts, since sends publish in the background. */
    private suspend fun awaitPublication(
        relay: LoopbackNostrRelay,
        before: Int,
    ): Boolean =
        withTimeoutOrNull(ESTABLISH_TIMEOUT_MS) {
            while (relay.publicationAttempts(MLS_GROUP_EVENT_KIND).size <= before) delay(POLL_MS)
            true
        } ?: false.also {
            val attempts = relay.publicationAttempts(MLS_GROUP_EVENT_KIND).size
            Log.i(TAG, "no old-group publication attempts=$attempts")
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
        draftStore = DraftStore(NoDrafts),
        accountIdHexResolver = { owner.accountIdHex },
        accounts = listOf(owner, peer),
        activeAccountRef = owner.label,
        initialMarmotRuntime = AppMarmotRuntime(root.absolutePath, native),
        preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
    )

    /** Keeps the fixture's draft cleanup in memory instead of the installed app's draft store. */
    private object NoDrafts : DraftPersistence {
        /** Starts every fixture app state with no drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards draft writes, so cleanup never reaches the installed app's drafts. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    /** Loopback relays are allowed only in this fixture's native configuration. */
    private fun loopbackOptions() =
        MarmotOptions(
            relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
            deployment = marmotDeploymentWhiteNoise(),
        )

    private companion object {
        const val TAG = "DmAbandonmentTest"
        const val ESTABLISH_TIMEOUT_MS = 60_000L
        const val DELIVERY_SETTLE_MS = 5_000L
        const val POLL_MS = 100L
        const val MLS_GROUP_EVENT_KIND = 445
        const val HISTORY_LIMIT = 50u
        const val OLD_GROUP_TEXT = "into the abandoned chat"

        /** A well-formed account id that is not in the chat, so granting it admin must be refused. */
        val NON_MEMBER_ID = "0f".repeat(32)
    }
}
