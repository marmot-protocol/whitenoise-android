package dev.ipf.whitenoise.android.qualification

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Populates through MDK on the baseline, then reads that same root after an APK update and process death. */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class MarmotUpgradeFixtureTest {
    /** Each invocation is a separate instrumentation process; neither read phase may create replacement data. */
    @Test
    fun retainedAccountHistoryAndMedia() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "dev.ipf.whitenoise.android.mdkqualification")
            val arguments = InstrumentationRegistry.getArguments()
            val phase = requireNotNull(arguments.getString("qualificationPhase"))
            require(phase in setOf("seed", "upgrade", "cold"))
            val receipt = File(context.filesDir, "upgrade-fixture.json")
            val root = File(context.filesDir, "Marmot")
            if (phase == "seed") {
                check(!root.exists() && !receipt.exists())
            } else {
                check(root.isDirectory && receipt.isFile)
            }
            val relay = "ws://127.0.0.1:${requireNotNull(arguments.getString("relayPort"))}"
            val runtime = upgradeFixtureRuntime(context, root, relay)
            try {
                withTimeout(180_000L) {
                    verifyUpgradeStorage(runtime, phase, beforeStart = true)
                    runtime.start()
                    if (phase == "seed") {
                        seed(runtime, relay, requireNotNull(arguments.getString("blobPort")), receipt)
                    } else {
                        verify(runtime, receipt, phase)
                        verifyUpgradeStorage(runtime, phase, beforeStart = false)
                        verify(runtime, receipt, phase)
                        verifyPendingUpload(runtime, receipt, phase)
                    }
                }
            } finally {
                runtime.shutdownAndClose()
            }
        }

    /** Native create/send/download calls produce both local upload bodies and a received retained attachment. */
    @Suppress("LongMethod") // One baseline transaction sequence owns the generated account and both upload states.
    private suspend fun seed(
        runtime: Marmot,
        relay: String,
        blobPort: String,
        receipt: File,
    ) {
        val sender = runtime.createIdentity(listOf(relay), listOf(relay))
        val receiver = runtime.createIdentity(listOf(relay), listOf(relay))
        val group = runtime.createGroup(sender.label, GROUP, listOf(receiver.accountIdHex), null)
        while (runCatching { runtime.acceptGroupInvite(receiver.label, group) }.isFailure) delay(100L)
        repeat(3) { runtime.sendText(sender.label, group, "$TEXT $it") }
        val upload =
            runtime.uploadMedia(
                sender.label,
                group,
                MediaUploadRequestFfi(
                    listOf(MediaUploadAttachmentRequestFfi("qualification.txt", "text/plain", payload(), null, null)),
                    null,
                    true,
                    "http://127.0.0.1:$blobPort",
                ),
            )
        check(requireNotNull(upload.sent).messageIds.size == 1)
        val target = awaitTarget(runtime, receiver.label, group)
        runtime.requestExplicitAttachment(receiver.label, group, target)
        while (runtime.attachmentLocalAssets(receiver.label, group, listOf(target)).single().reference == null) {
            delay(100L)
        }
        // Keep an uploaded-but-unsent outgoing record across 0113 as well as the published attachment.
        val pending =
            runtime.uploadMedia(
                sender.label,
                group,
                MediaUploadRequestFfi(
                    listOf(MediaUploadAttachmentRequestFfi("pending.txt", "text/plain", payload(1), null, null)),
                    null,
                    false,
                    "http://127.0.0.1:$blobPort",
                ),
            )
        val reference = pending.attachments.single().reference
        val value =
            JSONObject()
                .put("accounts", JSONArray(runtime.listAccounts().map { it.accountIdHex }.sorted()))
                .put("sender", sender.label)
                .put("receiver", receiver.label)
                .put("group", group)
                .put("seedPid", Process.myPid())
                .put("originalMessage", requireNotNull(upload.sent).messageIds.single())
                .put("pendingReference", reference.upgradeReceipt())
        receipt.writeText(value.toString())
        verifyHistory(runtime, value)
        verifyRetained(runtime, value)
    }

    /** Retained reads never acquire from the network; both the account set and plaintext must survive unchanged. */
    private suspend fun verify(
        runtime: Marmot,
        receipt: File,
        phase: String,
    ) {
        val value = JSONObject(receipt.readText())
        assertNotEquals(value.getInt("seedPid"), Process.myPid())
        if (phase == "cold") assertNotEquals(value.getInt("upgradePid"), Process.myPid())
        verifyHistory(runtime, value)
        verifyRetained(runtime, value)
        value.put("${phase}Pid", Process.myPid())
        receipt.writeText(value.toString())
    }

    /** Waits for the receiver's native history instead of constructing a target from sender metadata. */
    private suspend fun awaitTarget(
        runtime: Marmot,
        account: String,
        group: String,
    ): AttachmentLocalTargetFfi {
        while (true) {
            val result = runtime.attachmentHistoryPage(account, group, 100u, null)
            val page = (result as AttachmentPageReadFfi.Page).page
            try {
                page.entries.singleOrNull()?.let {
                    return AttachmentLocalTargetFfi(it.messageIdHex, it.sourceMessageIdHex, 0u)
                }
            } finally {
                page.nextCursor?.close()
                page.version.close()
            }
            delay(100L)
        }
    }

    /** Baseline and candidate must both read identical native-retained bytes without a download fallback. */
    private suspend fun verifyRetained(
        runtime: Marmot,
        value: JSONObject,
    ) {
        for (account in listOf(value.getString("sender"), value.getString("receiver"))) {
            val result = runtime.attachmentHistoryPage(account, value.getString("group"), 100u, null)
            val page = (result as AttachmentPageReadFfi.Page).page
            try {
                val entry = page.entries.single { it.messageIdHex == value.getString("originalMessage") }
                val assets =
                    runtime.attachmentLocalAssets(
                        account,
                        value.getString("group"),
                        listOf(AttachmentLocalTargetFfi(entry.messageIdHex, entry.sourceMessageIdHex, 0u)),
                    )
                val reference = requireNotNull(assets.single().reference) { "retained attachment became unavailable" }
                val bytes = runtime.readAttachmentAsset(account, reference, 0uL, PAYLOAD_SIZE.toUInt()).bytes
                assertArrayEquals(payload(), bytes)
            } finally {
                page.nextCursor?.close()
                page.version.close()
            }
        }
    }

    /** Promotes the baseline upload without uploading it again, then verifies its distinct retained plaintext. */
    private suspend fun verifyPendingUpload(
        runtime: Marmot,
        receipt: File,
        phase: String,
    ) {
        val value = JSONObject(receipt.readText())
        val account = value.getString("sender")
        val group = value.getString("group")
        if (phase == "upgrade") {
            val reference = value.getJSONObject("pendingReference").upgradeMediaReference()
            val sent = runtime.sendMediaReference(account, group, reference, "Promoted baseline pending upload")
            value.put("pendingMessage", sent.messageIds.single())
            receipt.writeText(value.toString())
        }
        val message = value.getString("pendingMessage")
        val target = AttachmentLocalTargetFfi(message, message, 0u)
        val assets = runtime.attachmentLocalAssets(account, group, listOf(target))
        val reference = requireNotNull(assets.single().reference) { "baseline pending upload did not retain plaintext" }
        val bytes = runtime.readAttachmentAsset(account, reference, 0uL, PAYLOAD_SIZE.toUInt()).bytes
        assertArrayEquals(payload(1), bytes)
    }

    /** The native account roster and usable chat projections must match what the baseline actually wrote. */
    private suspend fun verifyHistory(
        runtime: Marmot,
        value: JSONObject,
    ) {
        val expected = value.getJSONArray("accounts").let { array -> List(array.length()) { array.getString(it) } }
        assertEquals(expected, runtime.listAccounts().map { it.accountIdHex }.sorted())
        val account = value.getString("sender")
        val group = value.getString("group")
        assertTrue(runtime.chatList(account, true).any { it.groupIdHex == group })
        assertEquals(GROUP, runtime.groupDetails(account, group).group.name)
        val query = TimelineMessageQueryFfi(group, null, null, null, null, null, 100u)
        val messages = runtime.timelineMessages(account, query)
        repeat(3) { index -> assertTrue(messages.messages.any { it.plaintext == "$TEXT $index" }) }
    }

    private companion object {
        const val GROUP = "Nightly upgrade qualification"
        const val TEXT = "Retained qualification history"
        const val PAYLOAD_SIZE = 1024

        /** Deterministic synthetic bytes let both runtime generations assert content, not just attachment counts. */
        fun payload(offset: Int = 0): ByteArray = ByteArray(PAYLOAD_SIZE) { ((it + offset) % 251).toByte() }
    }
}
