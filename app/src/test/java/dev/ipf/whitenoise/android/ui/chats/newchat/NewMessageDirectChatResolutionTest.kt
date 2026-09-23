package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewMessageDirectChatResolutionTest {
    @Test
    fun failedKeyPackagePrewarmDoesNotPoisonAuthoritativeLookup() =
        runTest {
            val stages = mutableListOf<String>()
            val preparation =
                NewMessageRecipientPreparationCoordinator().prepare(
                    backgroundScope,
                    NewMessageRecipientPreparationKey("account", 1, "query", "target", 0),
                    prewarm = { error("relay unavailable") },
                    lookup = { NewMessageDirectChatResolution(item = null, createRequired = true) },
                    markStage = stages::add,
                )

            runCurrent()

            assertTrue(preparation.directChatResolution().createRequired)
            assertTrue(ChatCreateOpenTiming.STAGE_KEY_PACKAGE_PREWARM_FAILED in stages)
            assertTrue(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_RETURN in stages)
        }

    @Test
    fun missingProvenanceUsesExistingDirectChatFallback() =
        runTest {
            var fallbackLookups = 0

            val resolution =
                resolveNewMessageDirectChat(
                    npub = "npub1target",
                    existingDmGroupIdHex = null,
                    provenanceDirectChat = { _, _ -> error("provenance lookup must not run") },
                    existingDirectChat = {
                        fallbackLookups += 1
                        NewMessageDirectChatResolution(item = null, createRequired = true)
                    },
                )

            assertEquals(1, fallbackLookups)
            assertNull(resolution.item)
            assertTrue(resolution.createRequired)
        }

    @Test
    fun invalidProvenanceChecksOtherDirectChatsBeforeCreating() =
        runTest {
            var fallbackLookups = 0

            val resolution =
                resolveNewMessageDirectChat(
                    npub = "npub1bob",
                    existingDmGroupIdHex = "stale-dm",
                    provenanceDirectChat = { _, _ ->
                        NewMessageDirectChatResolution(item = null, createRequired = true)
                    },
                    existingDirectChat = {
                        fallbackLookups += 1
                        NewMessageDirectChatResolution(item = null, createRequired = false)
                    },
                )

            assertEquals(1, fallbackLookups)
            assertNull(resolution.item)
            assertFalse(resolution.createRequired)
        }

    @Test
    fun unavailableAuthoritativeLookupDoesNotCreateOrUseCachedFallback() =
        runTest {
            var fallbackLookups = 0

            val resolution =
                resolveNewMessageDirectChat(
                    npub = "npub1bob",
                    existingDmGroupIdHex = "known-dm",
                    provenanceDirectChat = { _, _ ->
                        NewMessageDirectChatResolution(item = null, createRequired = false)
                    },
                    existingDirectChat = {
                        fallbackLookups += 1
                        NewMessageDirectChatResolution(item = null, createRequired = true)
                    },
                )

            assertEquals(0, fallbackLookups)
            assertNull(resolution.item)
            assertFalse(resolution.createRequired)
        }
}
