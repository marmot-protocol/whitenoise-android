package dev.ipf.whitenoise.android.qualification

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

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
            val relay = "ws://127.0.0.1:${requireNotNull(arguments.getString("relayPort"))}"
            if (phase == "stale-upload-retry") {
                val root = Files.createTempDirectory(context.cacheDir.toPath(), "stale-upload-retry-").toFile()
                val runtime = upgradeFixtureRuntime(context, root, relay)
                try {
                    withTimeout(120_000L) {
                        runtime.start()
                        verifyStaleUploadRetry(runtime, relay, requireNotNull(arguments.getString("blobPort")))
                    }
                } finally {
                    runtime.shutdownAndClose()
                }
                return@runBlocking
            }
            require(
                phase in
                    setOf(
                        "seed",
                        "upgrade",
                        "cold",
                        "promote",
                        "promoted-cold",
                        "fault-enospc",
                        "fault-crash",
                        "fault-witness",
                        "corrupt",
                        "lost",
                    ),
            )
            val receipt = File(context.filesDir, "upgrade-fixture.json")
            val root = File(context.filesDir, "Marmot")
            prepareUpgradeFixture(root, receipt, phase)
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
                        if (fileStoreBaseline()) verifyUpgradeStorage(runtime, phase, beforeStart = false)
                    } else if (phase in setOf("corrupt", "lost")) {
                        recoverDamagedMedia(runtime, root, receipt)
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
                        if (phase == "upgrade" && !fileStoreBaseline()) {
                            awaitUpgradeCompaction(root, receipt)
                            verifyUpgradeStorage(
                                runtime,
                                "compacted",
                                beforeStart = false,
                                pendingLegacyBytes = PENDING_SIZE.toULong(),
                            )
                            verifyRetained(runtime, JSONObject(receipt.readText()))
                        }
                    }
                }
            } finally {
                runtime.shutdownAndClose()
            }
            recordClosedStorage(root, receipt, phase)
        }

    /** Validates the fixture layout and applies media damage only for the two recovery phases. */
    private fun prepareUpgradeFixture(
        root: File,
        receipt: File,
        phase: String,
    ) {
        if (phase == "seed") {
            check(!root.exists() && !receipt.exists())
        } else {
            check(root.isDirectory && receipt.isFile)
        }
        if (phase in setOf("corrupt", "lost")) damageUpgradeMedia(root, receipt, phase)
    }

    /** Records database and baseline salt evidence only after the native runtime has fully closed. */
    private fun recordClosedStorage(
        root: File,
        receipt: File,
        phase: String,
    ) {
        recordUpgradeDatabase(root, receipt, phase)
        if (phase == "seed" && fileStoreBaseline()) recordUpgradeSalts(root, receipt)
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
        val initial =
            JSONObject()
                .put("accounts", JSONArray(runtime.listAccounts().map { it.accountIdHex }.sorted()))
                .put("sender", sender.label)
                .put("receiver", receiver.label)
                .put("group", group)
                .put("seedPid", Process.myPid())
                .put("originalMessage", requireNotNull(upload.sent).messageIds.single())
        receipt.writeText(initial.toString())
        // Avatar metadata advances the group epoch; finish it before preparing epoch-bound unsent media.
        if (fileStoreBaseline()) seedUpgradeAvatar(runtime, receipt)
        if (fileStoreBaseline()) seedUpgradeRemoval(runtime, receipt, blobPort)
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
        assertEquals(reference, reference.upgradeReceipt().upgradeMediaReference())
        val value =
            JSONObject(receipt.readText())
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
        if (fileStoreBaseline()) verifyUpgradeAvatar(runtime, value)
        if (fileStoreBaseline()) verifyUpgradeRemoval(runtime, value)
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
        root: File,
        receipt: File,
    ) {
        val value = JSONObject(receipt.readText())
        val account = value.getString("receiver")
        val group = value.getString("group")
        val target = awaitTarget(runtime, account, group, value.getString("originalMessage"))
        val reference = runtime.attachmentLocalAssets(account, group, listOf(target)).single().reference
        if (reference != null) {
            val read = runCatching { runtime.readAttachmentAsset(account, reference, 0uL, READ_SIZE.toUInt()) }
            check(read.isFailure || read.getOrNull()?.let { !it.available && it.bytes.isEmpty() } == true) {
                "Damaged local file returned bytes without an explicit download"
            }
        }
        requireNotNull(runtime.downloadAttachmentAgain(account, group, target)) {
            "Native Retry did not admit the unavailable receiver attachment"
        }
        withTimeout(120_000L) {
            while (runCatching { verifyRetained(runtime, value) }.isFailure) delay(250L)
        }
        awaitUpgradeMediaCleanup(root, receipt)
        verifyRetained(runtime, value)
        verifyHistory(runtime, value)
        if (fileStoreBaseline()) verifyUpgradeAvatar(runtime, value)
    }

    /** Rejects deliberately stripped provenance while retaining the baseline's complete upload unchanged. */
    private suspend fun retainedPendingReference(
        runtime: Marmot,
        account: String,
        group: String,
        value: JSONObject,
    ): MediaAttachmentReferenceFfi {
        val reference = value.getJSONObject("pendingReference").upgradeMediaReference()
        requireNotNull(reference.sourceEpochIdentity) { "Baseline upload must retain its branch identity" }
        val refused =
            try {
                runtime.sendMediaReference(
                    account,
                    group,
                    reference.copy(sourceEpochIdentity = null),
                    "Missing upload identity",
                )
                false
            } catch (_: MarmotKitException.MediaReferenceStaleEpoch) {
                true
            }
        assertTrue("An upload stripped of its branch identity must be refused as stale", refused)
        return reference
    }

    /** Sends the baseline's original pending upload unchanged, then verifies its plaintext after another restart. */
    private suspend fun verifyPendingUpload(
        runtime: Marmot,
        receipt: File,
        phase: String,
    ) {
        val value = JSONObject(receipt.readText())
        val account = value.getString("sender")
        val group = value.getString("group")
        if (phase == "promote") {
            val reference = retainedPendingReference(runtime, account, group, value)
            val sent = runtime.sendMediaReference(account, group, reference, "Promoted baseline pending upload")
            value.put("pendingMessage", sent.messageIds.single())
            receipt.writeText(value.toString())
            reportUpgradePending(runtime, value, "send-returned", sent)
        }
        val message = value.getString("pendingMessage")
        try {
            withTimeout(30_000L) {
                val target = awaitTarget(runtime, account, group, message)
                reportUpgradePending(runtime, value, "history-found")
                while (true) {
                    val assets = runtime.attachmentLocalAssets(account, group, listOf(target))
                    val reference = assets.single().reference
                    if (reference != null) {
                        val bytes = runtime.readAttachmentAsset(account, reference, 0uL, PENDING_SIZE.toUInt()).bytes
                        assertArrayEquals(payload(1), bytes)
                        reportUpgradePending(runtime, value, "retained-plaintext-passed")
                        break
                    }
                    delay(100L)
                }
            }
        } finally {
            reportUpgradePending(runtime, value, "read-finished")
            verifyUpgradeStorage(runtime, "pending-read-observation", beforeStart = true)
        }
    }

    /** Forces an epoch change between upload and send, then retries from the same fixture plaintext. */
    private suspend fun verifyStaleUploadRetry(
        runtime: Marmot,
        relay: String,
        blobPort: String,
    ) {
        val sender = runtime.createIdentity(listOf(relay), listOf(relay))
        val peer = runtime.createIdentity(listOf(relay), listOf(relay))
        val group = runtime.createGroup(sender.label, "Stale upload retry", listOf(peer.accountIdHex), null)
        while (runCatching { runtime.acceptGroupInvite(peer.label, group) }.isFailure) delay(100L)

        val plaintext = payload(1)
        val caption = "Stale upload retry fixture"
        val request =
            MediaUploadRequestFfi(
                listOf(MediaUploadAttachmentRequestFfi("epoch-retry.txt", "text/plain", plaintext, null, null)),
                null,
                false,
                "http://127.0.0.1:$blobPort",
            )
        val original =
            runtime
                .uploadMedia(sender.label, group, request)
                .attachments
                .single()
                .reference
        requireNotNull(original.sourceEpochIdentity)
        runtime.updateGroupProfile(sender.label, group, "Stale upload retry advanced", null)
        val epoch = awaitAdvancedEpoch(runtime, sender, peer, group, original.sourceEpoch)

        val refusal =
            try {
                runtime.sendMediaReference(sender.label, group, original, caption)
                error("The old attachment reference was accepted after the epoch changed")
            } catch (failure: MarmotKitException.MediaReferenceStaleEpoch) {
                failure
            }
        assertEquals(original.sourceEpoch, refusal.sourceEpoch)
        assertEquals(epoch, refusal.currentEpoch)

        val fresh =
            runtime
                .uploadMedia(sender.label, group, request)
                .attachments
                .single()
                .reference
        assertEquals(epoch, fresh.sourceEpoch)
        assertNotEquals(original.ciphertextSha256, fresh.ciphertextSha256)
        assertEquals(original.plaintextSha256, fresh.plaintextSha256)
        val sent = runtime.sendMediaReference(sender.label, group, fresh, caption)
        val message = sent.messageIds.single()
        verifyRetriedAttachmentOnPeer(runtime, peer.label, group, message, caption, plaintext)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "stale_upload_retry",
                    "refused epoch ${original.sourceEpoch}, retried at epoch $epoch, " +
                        "peer attachment count 1, bytes matched",
                )
            },
        )
    }

    /** Verifies one received message and one readable attachment, including duplicates with a different message ID. */
    private suspend fun verifyRetriedAttachmentOnPeer(
        runtime: Marmot,
        peer: String,
        group: String,
        message: String,
        caption: String,
        plaintext: ByteArray,
    ) {
        val target = awaitTarget(runtime, peer, group, message)
        runtime.requestExplicitAttachment(peer, group, target)
        var asset = runtime.attachmentLocalAssets(peer, group, listOf(target)).single().reference
        while (asset == null) {
            delay(100L)
            asset = runtime.attachmentLocalAssets(peer, group, listOf(target)).single().reference
        }
        val read = runtime.readAttachmentAsset(peer, asset, 0uL, plaintext.size.toUInt() + 1u)
        assertTrue(read.available)
        assertArrayEquals(plaintext, read.bytes)
        // Watch for a duplicate relay delivery over a bounded window, failing on the first observation that has one.
        val query = TimelineMessageQueryFfi(group, null, null, null, null, null, 100u)
        repeat(DUPLICATE_OBSERVATIONS) {
            val messages = runtime.timelineMessages(peer, query).messages
            assertEquals(listOf(message), messages.filter { it.plaintext == caption }.map { it.messageIdHex })
            delay(DUPLICATE_OBSERVATION_INTERVAL_MS)
        }
        val page = (runtime.attachmentHistoryPage(peer, group, 100u, null) as AttachmentPageReadFfi.Page).page
        try {
            assertFalse(page.hasMore)
            assertEquals(listOf(message), page.entries.map { it.messageIdHex })
        } finally {
            page.nextCursor?.close()
            page.version.close()
        }
    }

    /**
     * Waits until the sender's epoch has moved past [sourceEpoch] and the peer has reached it, and returns that epoch.
     * Fails with the epochs it saw, before the outer timeout would hide which account fell behind.
     */
    private suspend fun awaitAdvancedEpoch(
        runtime: Marmot,
        sender: AccountSummaryFfi,
        peer: AccountSummaryFfi,
        group: String,
        sourceEpoch: ULong,
    ): ULong {
        var epoch = runtime.groupDetails(sender.label, group).mlsState.epoch
        withTimeoutOrNull(EPOCH_SYNC_TIMEOUT_MS) {
            while (epoch <= sourceEpoch || runtime.groupDetails(peer.label, group).mlsState.epoch != epoch) {
                delay(100L)
                epoch = runtime.groupDetails(sender.label, group).mlsState.epoch
            }
        } ?: fail(
            "Epoch did not advance past $sourceEpoch on both accounts: sender at $epoch, " +
                "peer at ${runtime.groupDetails(peer.label, group).mlsState.epoch}",
        )
        return epoch
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

        /** How long both accounts get to agree on the advanced epoch before the stale-upload phase fails. */
        const val EPOCH_SYNC_TIMEOUT_MS = 60_000L

        /** Timeline observations, and the spacing between them, that must each hold exactly one stale-retry message. */
        const val DUPLICATE_OBSERVATIONS = 8
        const val DUPLICATE_OBSERVATION_INTERVAL_MS = 250L

        /** Deterministic synthetic bytes let both runtime generations assert content, not just attachment counts. */
        fun payload(offset: Int = 0): ByteArray {
            val size = if (offset == 0) PAYLOAD_SIZE else PENDING_SIZE
            return ByteArray(size) { ((it + offset) % 251).toByte() }
        }
    }
}
