package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The full-history scan reads the conversation's bound account, not whichever account is active (#2873). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationHistorySearchAccountTest {
    private val group = conversationTimelineTestGroup()

    /** A notification-pinned conversation scans its own account while another account is still active. */
    @Test
    fun scanUsesTheConversationAccountNotTheActiveOne() =
        runBlocking {
            val appState = testAppState()
            val readAccounts = mutableListOf<String>()

            val result =
                searchConversationHistoryMatches(
                    appState = appState,
                    accountRef = PINNED_ACCOUNT,
                    groupIdHex = group.groupIdHex,
                    query = "needle",
                ) { account, _ ->
                    readAccounts += account
                    TimelinePageFfi(messages = emptyList(), hasMoreBefore = false, hasMoreAfter = false)
                }

            assertEquals(ConversationTimelineTestIds.ACCOUNT_REF, appState.activeAccountRef)
            assertEquals(listOf(PINNED_ACCOUNT), readAccounts)
            assertEquals(emptyList<Any>(), result)
        }

    /** Without a bound account the scan fails instead of falling back to the active account. */
    @Test
    fun scanWithoutABoundAccountFailsRatherThanUsingTheActiveOne() =
        runBlocking {
            val readAccounts = mutableListOf<String>()

            val result =
                searchConversationHistoryMatches(
                    appState = testAppState(),
                    accountRef = null,
                    groupIdHex = group.groupIdHex,
                    query = "needle",
                ) { account, _ ->
                    readAccounts += account
                    TimelinePageFfi(messages = emptyList(), hasMoreBefore = false, hasMoreAfter = false)
                }

            assertNull(result)
            assertEquals(emptyList<String>(), readAccounts)
        }

    /** Builds an app state whose active account differs from the pinned conversation account. */
    private fun testAppState() =
        conversationTimelineTestAppState(
            ScriptedConversationLiveSubscriptions(timelineScripts = emptyList(), group = group).subscriptions,
        )

    private companion object {
        const val PINNED_ACCOUNT = "notification-target"
    }
}
