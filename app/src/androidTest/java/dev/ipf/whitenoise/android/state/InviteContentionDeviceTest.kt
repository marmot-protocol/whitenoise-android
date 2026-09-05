package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket

/** Opt-in real-MDK reproduction; requires the isolated PR package and the loopback relay fixture. */
@RunWith(AndroidJUnit4::class)
class InviteContentionDeviceTest {
    /** Runs the same persisted invitation through old-master and patched controller retry policies. */
    @Test
    @Suppress("LongMethod") // One ordered before/after journey retains the exact native fixture between APK updates.
    fun realCatchUpContentionRetainsTheInviteUntilAcceptanceCanCommit() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val expectedArgument = InstrumentationRegistry.getArguments().getString("inviteExpectedAccepted")
            assumeTrue("Requires the explicit invite fixture argument", expectedArgument != null)
            check(context.packageName == "dev.ipf.whitenoise.android.preview.pr2489") {
                "This opt-in test must run in the isolated PR 2489 package"
            }
            val expectedAccepted = requireNotNull(expectedArgument).toBooleanStrict()
            val fixtureName = InstrumentationRegistry.getArguments().getString("inviteFixtureId") ?: "default"
            require(fixtureName.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
            MarmotAndroid.initialize(context)
            val root = File(context.filesDir, "invite-contention-fixture/$fixtureName").apply { mkdirs() }
            val sender = Marmot(File(root, "sender").absolutePath, RELAYS)
            var receiver = Marmot(File(root, "receiver").absolutePath, RELAYS)
            var controller: ConversationController? = null
            try {
                withTimeout(SETUP_TIMEOUT_MS) {
                    control("release")
                    sender.start()
                    receiver.start()
                    val fixture = prepareFixture(root, sender, receiver)
                    val account = receiver.listAccounts().single { it.label == fixture.getString("receiver") }
                    val groupId = fixture.getString("group")
                    val pending = awaitInvite(receiver, account.label, groupId)
                    assertTrue(
                        "The same invitation must still be pending before each comparison",
                        pending.pendingConfirmation,
                    )
                    receiver.catchUpAccounts()
                    delay(SETTLE_MILLIS)
                    val appState =
                        WhiteNoiseAppState(
                            context = context,
                            draftStore = DraftStore.forContext(context),
                            accountIdHexResolver = { receiver.accountIdHex(it) },
                            accounts = listOf(account),
                            activeAccountRef = account.label,
                            marmotRuntimeFactory = { AppMarmotRuntime(File(root, "receiver").absolutePath, receiver) },
                        )
                    var attempts = 0
                    var busy = 0
                    val started = SystemClock.elapsedRealtime()
                    controller =
                        withContext(Dispatchers.Main) {
                            ConversationController(
                                appState = appState,
                                initialGroup = pending,
                                inviteAcceptor = { accountRef, group ->
                                    attempts += 1
                                    try {
                                        receiver.acceptGroupInvite(accountRef, group).also {
                                            log("native_accept_success", started, attempts)
                                        }
                                    } catch (failure: MarmotKitException) {
                                        if (failure is MarmotKitException.AccountWorkerBusy) busy += 1
                                        val subtype =
                                            if (failure is MarmotKitException.AccountWorkerBusy) {
                                                "ACCOUNT_WORKER_BUSY"
                                            } else {
                                                "OTHER"
                                            }
                                        log(
                                            "native_failure=$subtype code=${DiagnosticFormatter.errorCode(failure)}",
                                            started,
                                            attempts,
                                        )
                                        throw failure
                                    }
                                },
                            )
                        }
                    control("hold", HOLD_MILLIS)
                    val catchUp =
                        async(Dispatchers.IO) {
                            log("catch_up_start", started, attempts)
                            runCatching { receiver.catchUpAccounts() }.also {
                                log("catch_up_end success=${it.isSuccess}", started, attempts)
                            }
                        }
                    withTimeout(HOLD_MILLIS) {
                        while (control("status").getInt("blocked") == 0) delay(POLL_MILLIS)
                    }
                    log("join_start", started, attempts)
                    val accepted = withContext(Dispatchers.Main) { controller!!.acceptInvite(notify = false) }
                    log("join_end accepted=$accepted busy=$busy", started, attempts)
                    appState.toast?.diagnosticReport?.let { Log.i(TAG, it.replace('\n', ' ')) }
                    assertTrue("Expected real native AccountWorkerBusy, not injected failure", busy > 0)
                    assertEquals("Observed acceptance must match the build under test", expectedAccepted, accepted)
                    assertEquals(!expectedAccepted, controller!!.group.pendingConfirmation)
                    if (expectedAccepted) assertEquals(null, appState.toast)
                    if (!expectedAccepted) {
                        assertEquals(3, attempts)
                        assertTrue(appState.toast?.diagnosticReport?.contains("error=RESOURCE_BUSY") == true)
                    }
                    catchUp.await()
                    control("release")
                    delay(SETTLE_MILLIS)
                    receiver.shutdownAndClose()
                    receiver = Marmot(File(root, "receiver").absolutePath, RELAYS)
                    receiver.start()
                    val persisted = awaitInvite(receiver, account.label, groupId)
                    assertEquals(
                        "Native restart must preserve confirmation state",
                        !expectedAccepted,
                        persisted.pendingConfirmation,
                    )
                    log("native_restart_verified pending=${persisted.pendingConfirmation}", started, attempts)
                    if (expectedAccepted) {
                        assertFalse(persisted.pendingConfirmation)
                        val sent = receiver.sendText(account.label, groupId, "Invite contention fixture accepted")
                        assertTrue(sent.messageIds.isNotEmpty())
                        log("post_accept_send_verified", started, attempts)
                        withTimeout(SETUP_TIMEOUT_MS) {
                            while (
                                sender
                                    .messages(fixture.getString("sender"), groupId, 100u, null)
                                    .none { it.messageIdHex in sent.messageIds }
                            ) {
                                delay(POLL_MILLIS)
                            }
                        }
                        log("peer_received_message_verified", started, attempts)
                    }
                }
            } finally {
                control("release")
                runCatching { sender.shutdownAndClose() }
                runCatching { receiver.shutdownAndClose() }
            }
        }

    /** Creates two local-relay identities once, leaving the pending invite intact for the fixed APK. */
    private suspend fun prepareFixture(
        root: File,
        sender: Marmot,
        receiver: Marmot,
    ): JSONObject {
        val metadata = File(root, "fixture.json")
        if (metadata.exists()) return JSONObject(metadata.readText())
        Log.i(TAG, "stage=create_sender")
        val alice = sender.createIdentity(RELAYS, RELAYS)
        Log.i(TAG, "stage=create_receiver")
        val bob = receiver.createIdentity(RELAYS, RELAYS)
        sender.catchUpAccounts()
        receiver.catchUpAccounts()
        Log.i(TAG, "stage=create_invitation")
        val group = sender.createGroup(alice.label, "Invite contention fixture", listOf(bob.accountIdHex), null)
        return JSONObject()
            .put("sender", alice.label)
            .put("receiver", bob.label)
            .put("group", group)
            .also { metadata.writeText(it.toString()) }
    }

    /** Waits for the real encrypted welcome to become an authoritative local group record. */
    private suspend fun awaitInvite(
        marmot: Marmot,
        account: String,
        group: String,
    ): AppGroupRecordFfi =
        withTimeout(SETUP_TIMEOUT_MS) {
            var record: AppGroupRecordFfi? = null
            while (record == null) {
                record = runCatching { marmot.groupDetails(account, group).group }.getOrNull()
                if (record == null) delay(POLL_MILLIS)
            }
            record
        }

    /** Controls only the loopback test relay through a USB-forwarded line protocol. */
    private suspend fun control(
        command: String,
        millis: Long = 0,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            Socket("127.0.0.1", CONTROL_PORT).use { socket ->
                socket.soTimeout = CONTROL_TIMEOUT_MS
                val request = JSONObject().put("command", command).put("millis", millis).toString() + "\n"
                socket.getOutputStream().write(request.toByteArray())
                JSONObject(socket.getInputStream().bufferedReader().readLine())
            }
        }

    /** Emits only stage, elapsed time, attempt count, and static result categories. */
    private fun log(
        stage: String,
        started: Long,
        attempts: Int,
    ) {
        Log.i(TAG, "stage=$stage elapsed_ms=${SystemClock.elapsedRealtime() - started} attempts=$attempts")
    }

    private companion object {
        const val TAG = "InviteBusyProbe"
        const val CONTROL_PORT = 19489
        const val CONTROL_TIMEOUT_MS = 5_000
        const val HOLD_MILLIS = 10_000L
        const val SETTLE_MILLIS = 2_000L
        const val POLL_MILLIS = 100L
        const val SETUP_TIMEOUT_MS = 180_000L
        val RELAYS = listOf("ws://127.0.0.1:19488")
    }
}
