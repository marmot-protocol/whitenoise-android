package dev.ipf.whitenoise.android.qualification

import dev.ipf.marmotkit.AttachmentControlFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Seeds a separately removed synthetic body, leaving the original retained media and pending upload intact. */
internal suspend fun seedUpgradeRemoval(
    runtime: Marmot,
    receipt: File,
    blobPort: String,
) {
    val value = JSONObject(receipt.readText())
    val account = value.getString("sender")
    val group = value.getString("group")
    val uploaded =
        runtime.uploadMedia(
            account,
            group,
            MediaUploadRequestFfi(
                listOf(MediaUploadAttachmentRequestFfi("removed.txt", "text/plain", byteArrayOf(1, 2, 3), null, null)),
                null,
                true,
                "http://127.0.0.1:$blobPort",
            ),
        )
    val message = requireNotNull(uploaded.sent).messageIds.single()
    val target =
        withTimeout(30_000L) {
            awaitRemovalTarget(runtime, account, group, message)
        }
    val reference =
        withTimeout(30_000L) {
            var status = runtime.attachmentTransferSnapshot(account, group, listOf(target)).items.single()
            while (status.state != AttachmentTransferStateFfi.READY || status.reference == null) {
                delay(100L)
                status = runtime.attachmentTransferSnapshot(account, group, listOf(target)).items.single()
            }
            requireNotNull(status.reference)
        }
    check(runtime.controlAttachment(account, reference, AttachmentControlFfi.REMOVE))
    value.put("removedMessage", target.messageIdHex)
    value.put("removedSource", target.sourceMessageIdHex)
    receipt.writeText(value.toString())
    verifyUpgradeRemoval(runtime, value)
}

/** Waits for the native source identity rather than substituting the published application's event ID. */
private suspend fun awaitRemovalTarget(
    runtime: Marmot,
    account: String,
    group: String,
    message: String,
): AttachmentLocalTargetFfi {
    while (true) {
        val page = (runtime.attachmentHistoryPage(account, group, 100u, null) as AttachmentPageReadFfi.Page).page
        try {
            page.entries.singleOrNull { it.messageIdHex == message }?.let {
                return AttachmentLocalTargetFfi(it.messageIdHex, it.sourceMessageIdHex, 0u)
            }
        } finally {
            page.nextCursor?.close()
            page.version.close()
        }
        delay(100L)
    }
}

/** Migration must preserve explicit removal as Removed, not reinterpret it as setting-driven cleanup. */
internal suspend fun verifyUpgradeRemoval(
    runtime: Marmot,
    value: JSONObject,
) {
    val target = AttachmentLocalTargetFfi(value.getString("removedMessage"), value.getString("removedSource"), 0u)
    val account = value.getString("sender")
    val group = value.getString("group")
    val state = runtime.attachmentTransferSnapshot(account, group, listOf(target)).items.single().state
    check(state == AttachmentTransferStateFfi.REMOVED)
    check(runtime.attachmentLocalAssets(account, group, listOf(target)).single().reference == null)
}

/** Retains private digests of existing salts so rekey qualification can detect deletion or replacement. */
internal fun recordUpgradeSalts(
    root: File,
    receipt: File,
) {
    val salts = root.walkTopDown().filter { it.isFile && it.name.endsWith(".salt") }.toList()
    check(salts.isNotEmpty())
    val hashes = JSONObject()
    for (salt in salts) hashes.put(salt.relativeTo(root).path, upgradeSaltDigest(salt))
    val value = JSONObject(receipt.readText()).put("baselineSaltHashes", hashes)
    receipt.writeText(value.toString())
}

/** Hashes salt bytes only; private paths and digests stay in the unexported synthetic receipt. */
internal fun upgradeSaltDigest(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
