package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExistingDirectChatOpenAttemptTest {
    @Test
    fun resolvedDmOpensWithoutCreatingGroupAndRecordsLookupTiming() =
        runTest {
            val expected = dmChatItem(groupId = "existing-dm", activeHex = "me", members = emptyList())
            val stages = mutableListOf<String>()
            val abandoned = mutableListOf<String>()
            var createCalled = false

            val result =
                attemptOpenOrStartProfileChat(
                    npub = "npub1support",
                    progressHex = "support",
                    recipientName = "White Noise support",
                    resolveDirectChat = {
                        NewMessageDirectChatResolution(item = expected, createRequired = false)
                    },
                    createGroup = {
                        createCalled = true
                        "duplicate-group"
                    },
                    loadCreatedChatListItem = { error("must not load a newly created chat") },
                    displayName = { it },
                    markCreateOpenStage = stages::add,
                    abandonCreateOpenTiming = abandoned::add,
                )

            assertFalse(createCalled)
            val opened = result as StartChatAttemptResult.Open
            assertEquals(expected, opened.item)
            assertFalse(opened.newlyCreated)
            assertEquals(
                listOf(
                    ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_START,
                    ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_RETURN,
                ),
                stages,
            )
            assertEquals(emptyList<String>(), abandoned)
        }

    @Test
    fun unavailableResolutionShowsRetryErrorWithoutCreatingGroup() =
        runTest {
            val stages = mutableListOf<String>()
            val abandoned = mutableListOf<String>()
            var createCalled = false

            val result =
                attemptOpenOrStartProfileChat(
                    npub = "npub1support",
                    progressHex = "support",
                    recipientName = "White Noise support",
                    resolveDirectChat = {
                        NewMessageDirectChatResolution(item = null, createRequired = false)
                    },
                    createGroup = {
                        createCalled = true
                        "duplicate-group"
                    },
                    loadCreatedChatListItem = { error("must not load an unavailable chat") },
                    displayName = { it },
                    markCreateOpenStage = stages::add,
                    abandonCreateOpenTiming = abandoned::add,
                )

            assertFalse(createCalled)
            val failure = result as StartChatAttemptResult.Failed
            assertEquals(AppText.Resource(R.string.couldnt_load_chats), failure.error.detail)
            assertEquals("npub1support", failure.error.npub)
            assertEquals(
                listOf(
                    ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_START,
                    ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_RETURN,
                ),
                stages,
            )
            assertEquals(listOf(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_FAILED), abandoned)
        }
}
