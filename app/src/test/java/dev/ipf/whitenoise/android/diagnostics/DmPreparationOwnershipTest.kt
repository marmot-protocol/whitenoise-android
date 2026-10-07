package dev.ipf.whitenoise.android.diagnostics

import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageRecipientPreparationCoordinator
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageRecipientPreparationKey
import dev.ipf.whitenoise.android.ui.chats.newchat.withDmCreationOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preparation retries and screen disposal must retain the attempt that actually owned unfinished work. */
class DmPreparationOwnershipTest {
    /** Retry and chat-revision changes get unique ordinals; completed work is never reported as replaced. */
    @Test
    fun completedPreparationsAndBackNavigationDoNotInventReplacement() =
        runTest {
            val records = mutableListOf<Map<String, Any>>()
            val interaction = DmCreationInteraction(records::add)
            val coordinator = NewMessageRecipientPreparationCoordinator()
            val key = NewMessageRecipientPreparationKey("a", 1, "q", "p", 0)
            for (next in listOf(key, key.copy(retryKey = 1), key.copy(retryKey = 1, chatRevision = 2))) {
                coordinator
                    .prepare(
                        this,
                        next,
                        prewarm = {},
                        lookup = { NewMessageDirectChatResolution(null, true) },
                        diagnosticAttempt = interaction.preparation(),
                    ).awaitCompletion()
            }
            coordinator.clear()
            assertFalse(records.any { it["phase"] == "owner" })
            assertEquals(
                listOf(0L, 1L, 2L),
                records
                    .filter {
                        it["phase"] == "existing_lookup" && it["outcome"] == "start"
                    }.map { it["attempt"] },
            )
            assertEquals(1, records.map { it["interaction"] }.toSet().size)
            interaction.nextAttempt().record(DmCreationPhase.CREATE, DmCreationOutcome.START)
            assertEquals(3L, records.last()["attempt"])
        }

    /** An overlapping re-preparation cancels and attributes only the unfinished ordinal. */
    @Test
    fun inFlightRePreparationReplacesOnlyTheOldOrdinal() =
        runTest {
            val records = mutableListOf<Map<String, Any>>()
            val interaction = DmCreationInteraction(records::add)
            val coordinator = NewMessageRecipientPreparationCoordinator()
            val key = NewMessageRecipientPreparationKey("a", 1, "q", "p", 0)
            coordinator.prepare(
                this,
                key,
                prewarm = { CompletableDeferred<Unit>().await() },
                lookup = { CompletableDeferred<NewMessageDirectChatResolution>().await() },
                diagnosticAttempt = interaction.preparation(),
            )
            runCurrent()
            coordinator
                .prepare(
                    this,
                    key.copy(retryKey = 1),
                    prewarm = {},
                    lookup = { NewMessageDirectChatResolution(null, true) },
                    diagnosticAttempt = interaction.preparation(),
                ).awaitCompletion()
            coordinator.clear()
            runCurrent()
            assertEquals(listOf(0L), records.filter { it["phase"] == "owner" }.map { it["attempt"] })
            assertEquals(2, records.count { it["attempt"] == 0L && it["outcome"] == "cancelled" })
            assertTrue(records.any { it["attempt"] == 1L && it["outcome"] == "success" })
        }

    /** Both UI finally blocks use this production owner boundary, including normal navigation disposal. */
    @Test
    fun cancelledLostOwnerIsRecordedButSuccessfulHandoffIsNot() =
        runTest {
            val records = mutableListOf<Map<String, Any>>()
            val old = DmCreationInteraction(records::add).nextAttempt()
            var current = true
            val job =
                launch {
                    withDmCreationOwner(old, { current }) { CompletableDeferred<Unit>().await() }
                }
            runCurrent()
            current = false
            job.cancel()
            job.join()
            assertEquals(1, records.count { it["failure"] == "owner_replaced" })
            val replacement = DmCreationInteraction(records::add).nextAttempt()
            current = true
            withDmCreationOwner(replacement, { current }) { markOpened ->
                markOpened()
                current = false
            }
            withDmCreationOwner(replacement, { true }) { }
            assertEquals(1, records.size)
        }
}
