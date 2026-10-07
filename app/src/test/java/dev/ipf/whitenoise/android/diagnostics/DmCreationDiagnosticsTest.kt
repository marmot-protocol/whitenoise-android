package dev.ipf.whitenoise.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.audio.DictationDiagnosticRecorder
import dev.ipf.whitenoise.android.audio.DictationDiagnosticStore
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.clearAuditAndDictationLogShares
import dev.ipf.whitenoise.android.state.prepareAuditAndDictationLogArchive
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageRecipientPreparationCoordinator
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageRecipientPreparationKey
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatAttemptResult
import dev.ipf.whitenoise.android.ui.chats.newchat.attemptOpenOrStartProfileChat
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
                        NewMessageRecipientPreparationKey(
                            accountRef = "private-account",
                            runtimeGeneration = 1,
                            query = "private-query",
                            targetReference = "private-recipient",
                            retryKey = 0,
                        ),
                        prewarm = {
                            finishPrewarm.await()
                            throw MarmotKitException.MissingKeyPackage(PRIVATE)
                        },
                        lookup = { NewMessageDirectChatResolution(null, true) },
                        diagnosticAttempt = interaction.preparation(),
                    )
                runCurrent()
                exerciseCreateRetries(interaction) {
                    finishPrewarm.complete(Unit)
                    preparation.awaitCompletion()
                }
                val archive = requireNotNull(prepareAuditAndDictationLogArchive(context, emptyList(), null))
                verifyArchive(archive)
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
            assertTrue(
                records.any {
                    it["interaction"] == oldToken &&
                        it["outcome"] == "replaced" &&
                        it["failure"] == "owner_replaced"
                },
            )
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
            DictationDiagnosticStore(
                folder.newFolder(),
                "abcdef012",
                nowMillis = { now },
                maxBytes = 1600,
                retentionMillis = 100,
                filePrefix = "dm-create",
            )
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

    /** Calls the production lookup/create/projection machine while preparation is genuinely overlapping. */
    private suspend fun exerciseCreateRetries(
        interaction: DmCreationInteraction,
        beforeSuccess: suspend () -> Unit,
    ) {
        val failures =
            listOf(
                MarmotKitException.MissingKeyPackage(PRIVATE),
                MarmotKitException.MissingMemberInboxRoute(PRIVATE),
                MarmotKitException.InvalidKeyPackageEvent(PRIVATE),
                MarmotKitException.Publish(PRIVATE),
                IllegalStateException(PRIVATE),
            )
        failures.forEach { error ->
            val result =
                attemptOpenOrStartProfileChat(
                    npub = "private-recipient",
                    progressHex = "private-identity",
                    recipientName = "private-name",
                    resolveDirectChat = { NewMessageDirectChatResolution(null, true) },
                    createGroup = { throw error },
                    loadCreatedChatListItem = { error("pre-return failure must not read a group") },
                    displayName = { it },
                    diagnosticAttempt = interaction.nextAttempt(),
                )
            assertTrue(result is StartChatAttemptResult.Failed)
        }
        beforeSuccess()
        val sixth = interaction.nextAttempt()
        val opened =
            attemptOpenOrStartProfileChat(
                npub = "private-recipient",
                progressHex = "private-identity",
                recipientName = "private-name",
                resolveDirectChat = { NewMessageDirectChatResolution(null, true) },
                createGroup = { "private-group" },
                loadCreatedChatListItem = {
                    ChatListItem(
                        group(""),
                        null,
                        null,
                        0,
                        dev.ipf.whitenoise.android.state
                            .GroupMemberSnapshot(emptyList()),
                    )
                },
                displayName = { it },
                diagnosticAttempt = sixth,
            )
        assertTrue(opened is StartChatAttemptResult.Open)
    }

    /** Inspects the existing archive exporter, never a synthetic diagnostic map or exception sentence. */
    private fun verifyArchive(archive: java.io.File) {
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
                records
                    .filter { it.getString("phase") == "create" && it.getString("outcome") == "failure" }
                    .map { it.getString("failure") },
            )
            assertTrue(
                records.any {
                    it.getInt("attempt") == 0 &&
                        it.getString("phase") == "prewarm" &&
                        it.getString("failure") == "missing_key_package"
                },
            )
            records.forEach {
                listOf(
                    "app_version",
                    "app_version_code",
                    "app_revision",
                    "distribution",
                    "android_api",
                    "mdk_revision",
                ).forEach { field ->
                    assertTrue(it.has(field))
                }
                assertEquals("unavailable", it.getString("native_phase_detail"))
            }
        }
    }

    /** An explicit synthetic group keeps private fixture values out of the diagnostic vocabulary. */
    private fun group(name: String) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = "group",
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = name,
            description = "A group",
            admins = emptyList(),
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "nostr",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    /** Builds required native group media metadata without issuing a network read or upload. */
    private fun encryptedMedia(): AppGroupEncryptedMediaComponentFfi {
        val endpoint = AppBlobEndpointFfi("blossom-v1", "https://blossom.primal.net")
        return AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(endpoint),
        )
    }

    private companion object {
        const val PRIVATE = "PRIVATE name npub1secret account group event wss://relay token payload key-material"
    }
}
