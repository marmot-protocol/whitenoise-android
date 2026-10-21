package dev.ipf.whitenoise.android.qualification

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Damages only the disposable receiver's sole synthetic media body while no runtime owns the root. */
internal fun damageUpgradeMedia(
    root: File,
    receipt: File,
    phase: String,
) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "dev.ipf.whitenoise.android.mdkqualification")
    val body = upgradeReceiverMediaBodies(root, receipt).single()
    check(body.length() > 16 * 1024 * 1024)
    if (phase == "lost") {
        check(body.delete()) { "Could not remove the isolated synthetic media body" }
    } else {
        require(phase == "corrupt")
        RandomAccessFile(body, "rw").use {
            // Corrupt the first authenticated block, never the database or migration records.
            it.seek(128)
            val original = it.readByte().toInt()
            it.seek(128)
            it.writeByte(original xor 1)
            it.fd.sync()
        }
    }
}

/** Identifies only the disposable receiver's native body files; account labels and paths stay off reports. */
private fun upgradeReceiverMediaBodies(
    root: File,
    receipt: File,
): List<File> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "dev.ipf.whitenoise.android.mdkqualification")
    val receiver = JSONObject(receipt.readText()).getString("receiver")
    val account = root.walkTopDown().single { it.isDirectory && it.name == receiver }
    return File(account, "media").walkTopDown().filter { it.isFile && it.name.matches(Regex("[0-9a-f]{32}")) }.toList()
}

/** Lets MDK unlink a replaced corrupt orphan before the next fault selects the sole recovered receiver body. */
internal suspend fun awaitUpgradeMediaCleanup(
    root: File,
    receipt: File,
) {
    try {
        withTimeout(120_000L) {
            while (upgradeReceiverMediaBodies(root, receipt).size != 1) delay(250L)
        }
    } finally {
        val report =
            JSONObject()
                .put("stage", "native-receiver-cleanup")
                .put("bodyCount", upgradeReceiverMediaBodies(root, receipt).size)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("qualification_media", report.toString()) },
        )
    }
}

/** Leaves MDK running for its next maintenance tick; settled migration alone precedes native compaction. */
internal suspend fun awaitUpgradeCompaction(
    root: File,
    receipt: File,
) {
    val baselineBytes = JSONObject(receipt.readText()).getLong("baselineDatabaseBytes")
    val databases = root.walkTopDown().filter { it.isFile && it.name == "session.sqlite" }.toList()
    check(databases.size == 2)
    try {
        withTimeout(120_000L) {
            while (baselineBytes - databases.sumOf { it.length() } < 24L * 1024L * 1024L) delay(250L)
        }
    } finally {
        val report =
            JSONObject()
                .put("phase", "upgrade-compaction")
                .put("baselineDatabaseBytes", baselineBytes)
                .put("databaseBytes", databases.sumOf { it.length() })
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("qualification_database", report.toString()) },
        )
    }
}

/** Measures real closed database files; substantial shrink plus intact content proves native reclamation. */
internal fun recordUpgradeDatabase(
    root: File,
    receipt: File,
    phase: String,
) {
    if (phase !in setOf("seed", "upgrade", "cold", "promoted-cold")) return
    val databases = root.walkTopDown().filter { it.isFile && it.name == "session.sqlite" }.toList()
    check(databases.size == 2)
    val bytes = databases.sumOf { it.length() }
    val walBytes = databases.sumOf { File(it.path + "-wal").let { wal -> if (wal.exists()) wal.length() else 0L } }
    val value = JSONObject(receipt.readText())
    val report = JSONObject().put("phase", phase).put("databaseBytes", bytes).put("walBytes", walBytes)
    InstrumentationRegistry.getInstrumentation().sendStatus(
        0,
        Bundle().apply { putString("qualification_database", report.toString()) },
    )
    if (phase == "seed") {
        check(bytes >= 32L * 1024L * 1024L) { "Baseline did not persist the large legacy fixture" }
        value.put("baselineDatabaseBytes", bytes)
        receipt.writeText(value.toString())
    } else {
        check(value.getLong("baselineDatabaseBytes") - bytes >= 24L * 1024L * 1024L) {
            "Migration settled without returning the large database body allocation to the filesystem"
        }
        check(walBytes == 0L) { "Closed native runtime retained an untruncated WAL" }
    }
}
