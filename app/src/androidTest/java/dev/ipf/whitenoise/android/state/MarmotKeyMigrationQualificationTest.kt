package dev.ipf.whitenoise.android.state

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AttachmentKeepMediaFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.qualification.upgradeFixtureRuntime
import dev.ipf.whitenoise.android.qualification.upgradeSaltDigest
import dev.ipf.whitenoise.android.qualification.verifyUpgradeRemoval
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reopens retained raw-key and migration-0116 state; a baseline already past the cutover proves compatibility. */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class MarmotKeyMigrationQualificationTest {
    /** Opening migrated accounts preserves Forever, explicit removal and every baseline salt across processes. */
    @Test
    fun migratedAccountsPreserveRetentionAndSalts() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "dev.ipf.whitenoise.android.mdkqualification")
            val root = File(context.filesDir, "Marmot")
            val receipt = File(context.filesDir, "upgrade-fixture.json")
            check(root.isDirectory && receipt.isFile)
            val value = JSONObject(receipt.readText())
            val port = requireNotNull(InstrumentationRegistry.getArguments().getString("relayPort"))
            val runtime = upgradeFixtureRuntime(context, root, "ws://127.0.0.1:$port")
            try {
                withTimeout(120_000L) {
                    runtime.start()
                    val accounts = runtime.listAccounts()
                    check(accounts.size == 2)
                    for (account in accounts) {
                        check(runtime.attachmentKeepMedia(account.label) == AttachmentKeepMediaFfi.FOREVER)
                    }
                    verifyUpgradeRemoval(runtime, value)
                }
            } finally {
                runtime.shutdownAndClose()
            }
            val salts = value.getJSONObject("baselineSaltHashes")
            check(salts.length() > 0)
            for (relative in salts.keys()) {
                val salt = File(root, relative)
                check(salt.isFile && upgradeSaltDigest(salt) == salts.getString(relative))
            }
            val unfinished =
                root.walkTopDown().any {
                    it.name.endsWith(".raw-key-migrating") || it.name.endsWith(".salt-migrating")
                }
            check(!unfinished)
            val report =
                JSONObject()
                    .put("foreverDefaultPreserved", true)
                    .put("explicitRemovalPreserved", true)
                    .put("baselineSaltsPreserved", salts.length())
                    .put("migrationMarkersRemaining", 0)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString("qualification_key_migration", report.toString()) },
            )
        }
}
