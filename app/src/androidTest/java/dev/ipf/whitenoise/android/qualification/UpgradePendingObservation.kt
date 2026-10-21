package dev.ipf.whitenoise.android.qualification

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import org.json.JSONObject

/** Records native send/history/retention stages without account IDs, descriptors, plaintext or persistence edits. */
internal suspend fun reportUpgradePending(
    runtime: Marmot,
    receipt: JSONObject,
    stage: String,
    summary: SendSummaryFfi? = null,
) {
    val report = JSONObject().put("stage", stage)
    if (summary != null) {
        report
            .put("published", summary.published.toString())
            .put("acceptDisposition", summary.acceptDisposition.name)
            .put("maintenanceDisposition", summary.maintenanceDisposition.name)
    }
    runCatching {
        val account = receipt.getString("sender")
        val group = receipt.getString("group")
        val message = receipt.getString("pendingMessage")
        val query = TimelineMessageQueryFfi(group, null, null, null, null, null, 100u)
        val matches = runtime.timelineMessages(account, query).messages.filter { it.messageIdHex == message }
        report.put("timelineMatches", matches.size)
        report.put("confirmedSources", matches.count { it.sourceMessageIdHex != null })
        val result = runtime.attachmentHistoryPage(account, group, 100u, null)
        val page = (result as AttachmentPageReadFfi.Page).page
        try {
            val entries = page.entries.filter { it.messageIdHex == message }
            report.put("historyEntries", page.entries.size).put("historyMatches", entries.size)
            val target = entries.singleOrNull()?.let { AttachmentLocalTargetFfi(it.messageIdHex, it.sourceMessageIdHex, 0u) }
            report.put(
                "localReferenceAvailable",
                target?.let { runtime.attachmentLocalAssets(account, group, listOf(it)).single().reference != null }
                    ?: false,
            )
        } finally {
            page.nextCursor?.close()
            page.version.close()
        }
    }.onFailure { report.put("observationError", it.javaClass.simpleName) }
    InstrumentationRegistry.getInstrumentation().sendStatus(
        0,
        Bundle().apply { putString("qualification_pending", report.toString()) },
    )
}
