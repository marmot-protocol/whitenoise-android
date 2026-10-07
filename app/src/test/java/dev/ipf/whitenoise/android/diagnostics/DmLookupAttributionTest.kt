package dev.ipf.whitenoise.android.diagnostics

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.state.DIRECT_LOOKUP_TIMEOUT_MS
import dev.ipf.whitenoise.android.state.withDirectChatLookupDeadline
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageRecipientPreparationCoordinator
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageRecipientPreparationKey
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatAttemptResult
import dev.ipf.whitenoise.android.ui.chats.newchat.attemptOpenOrStartProfileChat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the shipping preparation and tap classifiers, including the real lookup deadline. */
class DmLookupAttributionTest {
    /** Neither entry path can describe an uncertain native read as success or create another DM. */
    @Test
    fun unavailableAndTimeoutHaveTheSameClosedAttributionOnBothPaths() =
        runTest {
            for (timeout in listOf(false, true)) {
                for (prepare in listOf(false, true)) {
                    val records = mutableListOf<Map<String, Any>>()
                    val interaction = DmCreationInteraction(records::add)
                    val attempt = if (prepare) interaction.preparation() else interaction.nextAttempt()
                    val lookup: suspend () -> NewMessageDirectChatResolution = {
                        if (timeout) {
                            withDirectChatLookupDeadline(attempt) {
                                delay(DIRECT_LOOKUP_TIMEOUT_MS + 1)
                                NewMessageDirectChatResolution(null, true)
                            } ?: NewMessageDirectChatResolution(null, false)
                        } else {
                            NewMessageDirectChatResolution(null, false)
                        }
                    }
                    if (prepare) {
                        val coordinator = NewMessageRecipientPreparationCoordinator()
                        coordinator
                            .prepare(
                                this,
                                NewMessageRecipientPreparationKey("a", 1, "q", "p", 0),
                                prewarm = {},
                                lookup = lookup,
                                diagnosticAttempt = attempt,
                            ).awaitCompletion()
                        coordinator.clear()
                    } else {
                        val result =
                            attemptOpenOrStartProfileChat(
                                npub = "private-peer",
                                progressHex = "private-peer",
                                recipientName = null,
                                resolveDirectChat = lookup,
                                createGroup = { error("An uncertain lookup must not create a DM") },
                                loadCreatedChatListItem = { error("No new group may be read") },
                                displayName = { it },
                                diagnosticAttempt = attempt,
                            )
                        assertTrue(result is StartChatAttemptResult.Failed)
                    }
                    val lookupRecords = records.filter { it["phase"] == "existing_lookup" }
                    val terminal = lookupRecords.filter { it["outcome"] != "start" }
                    assertTrue(terminal.isNotEmpty())
                    assertTrue(terminal.all { it["outcome"] == "failure" })
                    assertEquals(
                        setOf(if (timeout) "lookup_timeout" else "lookup_unavailable"),
                        terminal.map { it["failure"] }.toSet(),
                    )
                    assertFalse(records.any { it["phase"] == "owner" || it["phase"] == "create" })
                }
            }
        }

    /** Native variants without a dedicated code remain distinguishable from Android bugs without error text. */
    @Test
    fun unmappedBindingErrorsAreSeparateFromHostErrors() {
        val records = mutableListOf<Map<String, Any>>()
        val attempt = DmCreationInteraction(records::add).nextAttempt()
        attempt.failed(DmCreationPhase.EXISTING_LOOKUP, MarmotKitException.UnknownAccount("PRIVATE"))
        attempt.failed(DmCreationPhase.EXISTING_LOOKUP, IllegalStateException("PRIVATE"))
        assertEquals(listOf("other_binding_error", "unknown"), records.map { it["failure"] })
        assertFalse(records.toString().contains("PRIVATE"))
    }
}
