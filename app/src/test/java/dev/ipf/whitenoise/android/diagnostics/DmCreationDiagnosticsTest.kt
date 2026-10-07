package dev.ipf.whitenoise.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.audio.DictationDiagnosticRecorder
import dev.ipf.whitenoise.android.audio.DictationDiagnosticStore
import dev.ipf.whitenoise.android.state.clearAuditAndDictationLogShares
import dev.ipf.whitenoise.android.state.prepareAuditAndDictationLogArchive
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageRecipientPreparationCoordinator
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageRecipientPreparationKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class DmCreationDiagnosticsTest {
    @get:Rule val folder = TemporaryFolder()

    /** An overlapping preparation error remains separate from retries and survives the real archive path. */
    @Test
    fun productionArchiveRetainsTypedRetriesAndBuildContextWithoutPrivateErrors() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            DmCreationDiagnostics.attach(context)
            clearAuditAndDictationLogShares(context.cacheDir)
            DmCreationDiagnostics.setEnabled(true)
            try {
                val interaction = DmCreationInteraction()
                val coordinator = NewMessageRecipientPreparationCoordinator()
                val finishPrewarm = CompletableDeferred<Unit>()
                val preparation =
                    coordinator.prepare(
                        this,
                        NewMessageRecipientPreparationKey("private-account", 1, "private-query", "private-recipient", 0),
                        prewarm = {
                            finishPrewarm.await()
                            throw MarmotKitException.MissingKeyPackage(PRIVATE)
                        },
                        lookup = { NewMessageDirectChatResolution(null, true) },
                        diagnosticAttempt = interaction.preparation(),
                    )
                runCurrent()
                val failures =
                    listOf(
                        MarmotKitException.MissingKeyPackage(PRIVATE),
                        MarmotKitException.MissingMemberInboxRoute(PRIVATE),
                        MarmotKitException.InvalidKeyPackageEvent(PRIVATE),
                        MarmotKitException.Publish(PRIVATE),
                        IllegalStateException(PRIVATE),
                    )
                failures.forEach { error ->
                    val attempt = interaction.nextAttempt()
                    attempt.record(DmCreationPhase.CREATE, DmCreationOutcome.START)
                    attempt.failed(DmCreationPhase.CREATE, error)
                }
                finishPrewarm.complete(Unit)
                preparation.awaitCompletion()
                val sixth = interaction.nextAttempt()
                sixth.record(DmCreationPhase.CREATE, DmCreationOutcome.START)
                sixth.record(DmCreationPhase.CREATE, DmCreationOutcome.SUCCESS)
                sixth.record(DmCreationPhase.PROJECTION, DmCreationOutcome.SUCCESS)
                val archive = requireNotNull(prepareAuditAndDictationLogArchive(context, emptyList(), null))
                ZipFile(archive).use { zip ->
                    val text = zip.getInputStream(zip.getEntry("dm-create-current.jsonl")).bufferedReader().readText()
                    assertFalse(text.contains("PRIVATE"))
                    assertFalse(text.contains("private-"))
                    val records =
                        text
                            .lineSequence()
                            .filter(String::isNotBlank)
                            .map(::JSONObject)
                            .toList()
                    assertEquals(1, records.map { it.getString("interaction") }.distinct().size)
                    assertEquals((0..6).toSet(), records.map { it.getInt("attempt") }.toSet())
                    assertEquals(
                        listOf("missing_key_package", "missing_inbox", "invalid_key_package", "publish", "unknown"),
                        records.filter { it.getString("phase") == "create" && it.getString("outcome") == "failure" }.map { it.getString("failure") },
                    )
                    assertTrue(
                        records.any { it.getInt("attempt") == 0 && it.getString("phase") == "prewarm" && it.getString("failure") == "missing_key_package" },
                    )
                    records.forEach {
                        listOf("app_version", "app_version_code", "app_revision", "distribution", "android_api", "mdk_revision").forEach { field ->
                            assertTrue(it.has(field))
                        }
                        assertEquals("unavailable", it.getString("native_phase_detail"))
                    }
                }
            } finally {
                DmCreationDiagnostics.setEnabled(false)
                clearAuditAndDictationLogShares(context.cacheDir)
            }
        }

    /** Cancelled/replaced preparations and destination retries cannot attribute late frames to another token. */
    @Test
    fun replacementAndLateFramesStayWithTheirCapturedOwners() =
        runTest {
            val records = mutableListOf<Map<String, Any>>()
            val old = DmCreationInteraction(records::add)
            val replacement = DmCreationInteraction(records::add)
            val coordinator = NewMessageRecipientPreparationCoordinator()
            coordinator.prepare(
                this,
                NewMessageRecipientPreparationKey("a", 1, "q", "x", 0),
                prewarm = { CompletableDeferred<Unit>().await() },
                lookup = { CompletableDeferred<NewMessageDirectChatResolution>().await() },
                diagnosticAttempt = old.preparation(),
            )
            runCurrent()
            coordinator.prepare(
                this,
                NewMessageRecipientPreparationKey("a", 2, "q", "y", 0),
                prewarm = {},
                lookup = { NewMessageDirectChatResolution(null, true) },
                diagnosticAttempt = replacement.preparation(),
            )
            runCurrent()
            coordinator.clear()
            val oldToken = records.first().getValue("interaction")
            assertEquals(2, records.count { it["interaction"] == oldToken && it["outcome"] == "cancelled" })
            val stale = old.nextAttempt()
            val current = replacement.nextAttempt()
            DmCreationDiagnostics.awaitFrame("a", "g", 2, stale)
            DmCreationDiagnostics.awaitFrame("a", "g", 2, current)
            DmCreationDiagnostics.firstFrame("a", "g", 2, stale)
            DmCreationDiagnostics.firstFrame("a", "g", 1, current)
            DmCreationDiagnostics.firstFrame("other", "g", 2, current)
            DmCreationDiagnostics.firstFrame("a", "g", 2, current)
            DmCreationDiagnostics.firstFrame("a", "g", 2, current)
            assertEquals(1, records.count { it["phase"] == "first_frame" && it["outcome"] == "success" })
            assertFalse(records.last()["interaction"] == oldToken)
        }

    /** Both bounded files expire from record time; disabled and cleared writer epochs cannot append stale work. */
    @Test
    fun dmStoreRetentionDisabledAndClearAreBounded() {
        var now = 1000L
        val store =
            DictationDiagnosticStore(folder.newFolder(), "abcdef012", nowMillis = { now }, maxBytes = 1600, retentionMillis = 100, filePrefix = "dm-create")
        DictationDiagnosticRecorder(store).use { recorder ->
            val interaction = DmCreationInteraction(recorder::recordFields)
            interaction.nextAttempt().failed(DmCreationPhase.CREATE, IllegalArgumentException(PRIVATE))
            assertEquals(setOf("dm-create-manifest.json"), recorder.snapshot().keys)
            recorder.setEnabled(true)
            repeat(30) { interaction.nextAttempt().failed(DmCreationPhase.CREATE, IllegalArgumentException(PRIVATE)) }
            val retained = recorder.snapshot()
            assertTrue(retained.keys.any { it.endsWith(".jsonl") })
            assertTrue(retained.values.all { it.size <= 1600 })
            assertTrue(retained.keys.size <= 3)
            now += 101
            assertEquals(setOf("dm-create-manifest.json"), recorder.snapshot().keys)
            interaction.nextAttempt().record(DmCreationPhase.CREATE, DmCreationOutcome.SUCCESS)
            recorder.clear()
            assertEquals(setOf("dm-create-manifest.json"), recorder.snapshot().keys)
            recorder.setEnabled(false)
            interaction.nextAttempt().failed(DmCreationPhase.CREATE, CancellationException(PRIVATE))
            assertEquals(setOf("dm-create-manifest.json"), recorder.snapshot().keys)
        }
    }

    private companion object {
        const val PRIVATE = "PRIVATE name npub1secret account group event wss://relay token payload key-material"
    }
}
