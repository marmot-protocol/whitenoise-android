package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AuditLogDeleteResultFfi
import dev.ipf.marmotkit.AuditLogFileFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Clearing diagnostic logs reports success only when every listed native log was deleted (#3705). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditLogDeleteOutcomeTest {
    /** A failed second deletion is reported as a failure even though the first file was deleted. */
    @Test
    fun laterFailureAfterADeletedFileIsReported() =
        runBlocking {
            val deleted = mutableListOf<String>()
            val state = appState(native(deleted, failing = setOf(SECOND)))

            assertFalse(state.deleteAuditLogs())

            assertEquals(listOf(FIRST), deleted)
            assertEquals(AppText.Resource(R.string.toast_couldnt_delete_audit_logs), state.toast?.title)
        }

    /** A failed first deletion still lets the second be deleted, and the result is a failure. */
    @Test
    fun earlierFailureStillDeletesTheRestAndIsReported() =
        runBlocking {
            val deleted = mutableListOf<String>()
            val state = appState(native(deleted, failing = setOf(FIRST)))

            assertFalse(state.deleteAuditLogs())

            assertEquals(listOf(SECOND), deleted)
            assertEquals(AppText.Resource(R.string.toast_couldnt_delete_audit_logs), state.toast?.title)
        }

    /** Deleting every listed file reports the logs as cleared. */
    @Test
    fun deletingEveryFileReportsCleared() =
        runBlocking {
            val deleted = mutableListOf<String>()
            val state = appState(native(deleted, failing = emptySet()))

            assertTrue(state.deleteAuditLogs())

            assertEquals(listOf(FIRST, SECOND), deleted)
            assertNull("a full deletion raises no failure notice", state.toast)
        }

    /** A runtime listing two logs whose deletion fails for the paths in [failing]. */
    private fun native(
        deleted: MutableList<String>,
        failing: Set<String>,
    ): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "auditLogFiles" -> listOf(file(FIRST), file(SECOND))
                "deleteAuditLogFile" -> {
                    val path = arguments!!.first() as String
                    if (path in failing) throw MarmotKitException.Io("permission denied")
                    deleted += path
                    AuditLogDeleteResultFfi(stillRecording = false)
                }
                else -> error("Unexpected native method: ${method.name}")
            }
        } as MarmotInterface

    /** A listed native log at [path]. */
    private fun file(path: String) =
        AuditLogFileFfi(
            accountRef = "personal",
            path = path,
            fileName = path.substringAfterLast('/'),
            sizeBytes = 1uL,
            modifiedAtMs = null,
        )

    /** An app state whose runtime is [native]. */
    private fun appState(native: MarmotInterface): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "personal",
                        accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = "personal",
            initialMarmotRuntime = AppMarmotRuntime("test", native),
        )

    private companion object {
        const val FIRST = "/logs/first.jsonl"
        const val SECOND = "/logs/second.jsonl"
    }
}
