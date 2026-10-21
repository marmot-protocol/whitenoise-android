package dev.ipf.whitenoise.android.qualification

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.marmotkit.AttachmentMigrationStateFfi
import dev.ipf.marmotkit.Marmot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val savedUpgradePolicies = mutableMapOf<String, AttachmentDownloadPolicyFfi>()
private var initialLegacyBytes = 0uL

/** Waits for an external, byte-pinned native hook; only the hosted emulator may release this barrier. */
internal suspend fun runUpgradeFault(
    runtime: Marmot,
    phase: String,
    receipt: File,
) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "dev.ipf.whitenoise.android.mdkqualification")
    val accounts = runtime.listAccounts().map { it.label }
    val before = accounts.map { runtime.attachmentStorageUsage(it) }
    check(before.sumOf { it.legacyBytes } >= 16uL * 1024uL * 1024uL)
    if (phase == "fault-witness") {
        // mediaBytes counts only committed ready retained files in MDK's catalogue.
        check(before.any { it.legacyBytes >= 16uL * 1024uL * 1024uL && it.mediaBytes >= 16uL * 1024uL * 1024uL })
        reportUpgradeStorage(runtime, accounts, phase, "ready-file-and-legacy-body-coexist")
        receipt.writeText(JSONObject(receipt.readText()).put("crashWitness", true).toString())
        return
    }
    require(phase in setOf("fault-enospc", "fault-crash"))
    for (account in accounts) {
        val policy = runtime.attachmentDownloadPolicy(account)
        runtime.setAttachmentDownloadPolicy(account, policy.copy(diskReserve = 0uL))
    }
    val waiting = File(receipt.parentFile, "fault-awaiting-hook")
    val release = File(receipt.parentFile, "fault-hook-ready")
    check(!waiting.exists() && !release.exists())
    waiting.writeText(phase)
    withTimeout(120_000L) {
        while (!release.exists()) delay(50L)
    }
    runtime.start()
    if (phase == "fault-crash") {
        // Success is an externally witnessed SIGKILL at the reviewed pre-delete instruction, never a return here.
        delay(120_000L)
        error("Crash hook did not terminate the fixture")
    }
    withTimeout(120_000L) {
        while (accounts.none { isUpgradeMigrationPaused(runtime, it) }) delay(100L)
    }
    val after = accounts.map { runtime.attachmentStorageUsage(it) }
    check(after.any { it.migration == AttachmentMigrationStateFfi.PAUSED && it.legacyBytes >= 16uL * 1024uL * 1024uL })
    reportUpgradeStorage(runtime, accounts, phase, "kernel-enospc-paused-legacy-retained")
}

/** Checks completed-body migration; pending legacy uploads stay in SQL until their separate promotion. */
internal suspend fun verifyUpgradeStorage(
    runtime: Marmot,
    phase: String,
    beforeStart: Boolean,
    pendingLegacyBytes: ULong = 0uL,
    readWhilePaused: suspend () -> Unit = {},
) {
    if (phase == "seed") return
    val accounts = runtime.listAccounts().map { it.label }
    if (phase == "upgrade" && beforeStart) {
        initialLegacyBytes = accounts.sumOf { runtime.attachmentStorageUsage(it).legacyBytes }
        check(initialLegacyBytes > 0uL) { "Baseline did not contain a completed legacy SQL attachment body" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reserve = context.filesDir.usableSpace.toULong() + 1024uL * 1024uL * 1024uL
        val crashWitness =
            JSONObject(File(context.filesDir, "upgrade-fixture.json").readText()).optBoolean("crashWitness")
        // A committed file needs no new allocation: native crash recovery correctly bypasses reserve admission.
        if (!crashWitness) {
            for (account in accounts) {
                val policy = runtime.attachmentDownloadPolicy(account)
                savedUpgradePolicies[account] = policy
                runtime.setAttachmentDownloadPolicy(account, policy.copy(diskReserve = reserve))
            }
        }
    }
    reportUpgradeStorage(runtime, accounts, phase, if (beforeStart) "before-start" else "before-resume")
    if (beforeStart) return
    if (phase == "upgrade" && savedUpgradePolicies.isNotEmpty()) {
        try {
            withTimeout(120_000L) {
                while (accounts.none { isUpgradeMigrationPaused(runtime, it) }) {
                    delay(250L)
                }
            }
            check(accounts.sumOf { runtime.attachmentStorageUsage(it).legacyBytes } == initialLegacyBytes)
            check(accounts.any { runtime.attachmentStorageUsage(it).migrationBytes > 0uL })
            reportUpgradeStorage(runtime, accounts, phase, "reserve-paused")
            readWhilePaused()
            reportUpgradeStorage(runtime, accounts, phase, "paused-read-passed")
        } finally {
            for ((account, policy) in savedUpgradePolicies) runtime.setAttachmentDownloadPolicy(account, policy)
            savedUpgradePolicies.clear()
        }
    }
    try {
        awaitUpgradeStorage(runtime, accounts, pendingLegacyBytes)
    } finally {
        reportUpgradeStorage(runtime, accounts, phase, "completion-observation")
    }
    reportUpgradeStorage(runtime, accounts, phase, "complete")
}

/** Waits for the expected legacy balance and settled native states; COMPACTING cannot count as completion. */
private suspend fun awaitUpgradeStorage(
    runtime: Marmot,
    accounts: List<String>,
    pendingLegacyBytes: ULong,
) {
    withTimeout(120_000L) {
        val settled = setOf(AttachmentMigrationStateFfi.COMPLETE, AttachmentMigrationStateFfi.NOT_NEEDED)
        while (true) {
            val usage = accounts.map { runtime.attachmentStorageUsage(it) }
            if (usage.sumOf { it.legacyBytes } == pendingLegacyBytes && usage.all { it.migration in settled }) {
                return@withTimeout
            }
            delay(250L)
        }
    }
}

/** Reports only byte counts and migration state, with no account identifiers or key material. */
private suspend fun reportUpgradeStorage(
    runtime: Marmot,
    accounts: List<String>,
    phase: String,
    stage: String,
) {
    val usage =
        accounts.map {
            val value = runtime.attachmentStorageUsage(it)
            JSONObject()
                .put("legacyBytes", value.legacyBytes.toString())
                .put("mediaBytes", value.mediaBytes.toString())
                .put("attachments", value.attachmentCount.toString())
                .put("migration", value.migration.name)
                .put("migrationBytes", value.migrationBytes.toString())
        }
    val report = JSONObject().put("phase", phase).put("stage", stage).put("usage", JSONArray(usage))
    InstrumentationRegistry.getInstrumentation().sendStatus(
        0,
        Bundle().apply { putString("qualification_storage", report.toString()) },
    )
}

/** A storage-usage read observes native progress; it never forces or repairs a migration step. */
private suspend fun isUpgradeMigrationPaused(
    runtime: Marmot,
    account: String,
): Boolean = runtime.attachmentStorageUsage(account).migration == AttachmentMigrationStateFfi.PAUSED
