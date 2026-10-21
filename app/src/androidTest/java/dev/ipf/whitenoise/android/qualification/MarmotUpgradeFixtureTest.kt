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
    /** Each invocation is a new process; read phases cannot replace the baseline fixture or its pending upload. */
    @Suppress("LongMethod") // One invocation owns the native lifetime across dependent qualification assertions.
    @Test
    fun retainedAccountHistoryAndMedia() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "dev.ipf.whitenoise.android.mdkqualification")
            val arguments = InstrumentationRegistry.getArguments()
            val phase = requireNotNull(arguments.getString("qualificationPhase"))
            require(
                phase in setOf(
                    "seed", "upgrade", "cold", "promote", "promoted-cold",
                    "fault-enospc", "fault-crash", "fault-witness", "corrupt", "lost",
                ),
            )
            val receipt = File(context.filesDir, "upgrade-fixture.json")
            val root = File(context.filesDir, "Marmot")
            if (phase == "seed") {
                check(!root.exists() && !receipt.exists())
            } else {
                check(root.isDirectory && receipt.isFile)
            }
            if (phase in setOf("corrupt", "lost")) damageUpgradeMedia(root, receipt, phase)
            val relay = "ws://127.0.0.1:${requireNotNull(arguments.getString("relayPort"))}"
            val runtime = upgradeFixtureRuntime(context, root, relay)
            try {
                withTimeout(360_000L) {
                    verifyUpgradeStorage(runtime, phase, beforeStart = true)
                    if (phase.startsWith("fault-")) {
                        runUpgradeFault(runtime, phase, receipt)
                        return@withTimeout
                    }
                    runtime.start()
                    if (phase == "seed") {
                        seed(runtime, relay, requireNotNull(arguments.getString("blobPort")), receipt)
                    } else if (phase in setOf("corrupt", "lost")) {
                        recoverDamagedMedia(runtime, receipt)
                    } else {
                        verify(runtime, receipt, phase)
                        verifyUpgradeStorage(
                            runtime,
                            phase,
                            beforeStart = false,
                            pendingLegacyBytes = if (phase == "promoted-cold") 0uL else PENDING_SIZE.toULong(),
                        ) {
                            verifyRetained(runtime, JSONObject(receipt.readText()))
                        }
                        verify(runtime, receipt, phase)
                        if (phase == "promote") {
                            verifyPendingUpload(runtime, receipt, phase)
                            verifyUpgradeStorage(runtime, "promoted", beforeStart = false)
                            verifyRetained(runtime, JSONObject(receipt.readText()))
                            verifyPendingUpload(runtime, receipt, "promoted")
                        } else if (phase == "promoted-cold") {
                            verifyPendingUpload(runtime, receipt, phase)
                        }
                    }
                }
            } finally {
                runtime.shutdownAndClose()
            }
            recordUpgradeDatabase(root, receipt, phase)
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
        if (phase == "promote") assertNotEquals(value.getInt("coldPid"), Process.myPid())
        if (phase == "promoted-cold") assertNotEquals(value.getInt("promotePid"), Process.myPid())
        verifyHistory(runtime, value)
        verifyRetained(runtime, value)
        value.put("${phase}Pid", Process.myPid())
        receipt.writeText(value.toString())
    }

    /** Resolves native attachment history so retained reads use MDK's source ID, not the application event ID. */
    private suspend fun awaitTarget(
        runtime: Marmot,
        account: String,
        group: String,
        message: String? = null,
    ): AttachmentLocalTargetFfi {
        while (true) {
            val result = runtime.attachmentHistoryPage(account, group, 100u, null)
            val page = (result as AttachmentPageReadFfi.Page).page
            try {
                page.entries.singleOrNull { message == null || it.messageIdHex == message }?.let {
                    // Each fixture message has one attachment; the native source ID is distinct from its event ID.
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
                for (offset in 0 until PAYLOAD_SIZE step READ_SIZE) {
                    val bytes =
                        runtime.readAttachmentAsset(account, reference, offset.toULong(), READ_SIZE.toUInt()).bytes
                    assertArrayEquals(ByteArray(READ_SIZE) { ((it + offset) % 251).toByte() }, bytes)
                }
            } finally {
                page.nextCursor?.close()
                page.version.close()
            }
        }
    }

    /** A damaged local body must never return unauthenticated bytes; only explicit native acquisition restores it. */
    private suspend fun recoverDamagedMedia(
        runtime: Marmot,
        receipt: File,
    ) {
        val value = JSONObject(receipt.readText())
        val account = value.getString("receiver")
        val group = value.getString("group")
        val target = awaitTarget(runtime, account, group, value.getString("originalMessage"))
        val reference = runtime.attachmentLocalAssets(account, group, listOf(target)).single().reference
        if (reference != null) {
            val read = runCatching { runtime.readAttachmentAsset(account, reference, 0uL, READ_SIZE.toUInt()) }
            check(read.isFailure) { "Damaged local file returned bytes without an explicit download" }
        }
        runtime.requestExplicitAttachment(account, group, target)
        withTimeout(120_000L) {
            while (runCatching { verifyRetained(runtime, value) }.isFailure) delay(250L)
        }
        verifyHistory(runtime, value)
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
        if (phase == "promote") {
            val reference = value.getJSONObject("pendingReference").upgradeMediaReference()
            val sent = runtime.sendMediaReference(account, group, reference, "Promoted baseline pending upload")
            value.put("pendingMessage", sent.messageIds.single())
            receipt.writeText(value.toString())
        }
        val message = value.getString("pendingMessage")
        try {
            withTimeout(30_000L) {
                val target = awaitTarget(runtime, account, group, message)
                while (true) {
                    val assets = runtime.attachmentLocalAssets(account, group, listOf(target))
                    val reference = assets.single().reference
                    if (reference != null) {
                        val bytes = runtime.readAttachmentAsset(account, reference, 0uL, PENDING_SIZE.toUInt()).bytes
                        assertArrayEquals(payload(1), bytes)
                        break
                    }
                    delay(100L)
                }
            }
        } finally {
            verifyUpgradeStorage(runtime, "pending-read-observation", beforeStart = true)
        }
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
        const val PAYLOAD_SIZE = 16 * 1024 * 1024
        const val PENDING_SIZE = 1024
        const val READ_SIZE = 1024 * 1024

        /** Deterministic synthetic bytes let both runtime generations assert content, not just attachment counts. */
        fun payload(offset: Int = 0): ByteArray =
            ByteArray(if (offset == 0) PAYLOAD_SIZE else PENDING_SIZE) { ((it + offset) % 251).toByte() }
    }
}
