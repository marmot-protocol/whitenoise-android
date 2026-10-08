package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.core.MessageSearchConstraints
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchEpochBounds
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelineRecord
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

    /** Filter-only history scans advance paired cursors and intersect all constraints, excluding expiry. */
    @Test
    fun filterOnlyScanUsesPairedPagesAndIntersectsSenderDateAndContent() =
        runBlocking {
            val eligible = timelineRecord("eligible", 30uL, "see https://example.com")
            val expired = eligible.copy(messageIdHex = "expired", retentionExpiresAt = 1uL)
            val wrongSender = eligible.copy(messageIdHex = "other", sender = "other")
            val old = timelineRecord("old", 10uL, "https://example.com")
            val seen = mutableListOf<Pair<ULong?, String?>>()
            val result =
                searchConversationHistoryMatches(
                    appState = testAppState(),
                    accountRef = PINNED_ACCOUNT,
                    groupIdHex = group.groupIdHex,
                    query = "",
                    constraints =
                        MessageSearchConstraints(
                            senderIds = setOf(eligible.sender.lowercase()),
                            dateBounds = GlobalSearchEpochBounds(20_000, 40_000),
                            contentKinds = setOf(GlobalSearchContentKind.LINKS),
                        ),
                ) { account, query ->
                    assertEquals(PINNED_ACCOUNT, account)
                    assertNull(query.search)
                    seen += query.before to query.beforeMessageId
                    if (query.before == null) {
                        TimelinePageFfi(listOf(eligible, expired, wrongSender), true, false)
                    } else {
                        TimelinePageFfi(listOf(old), false, false)
                    }
                }
            assertEquals(listOf(ConversationSearchMatch("eligible", 30uL)), result)
            assertEquals(listOf<Pair<ULong?, String?>>(null to null, 30uL to "eligible"), seen)
        }

    /** A contradictory sender restriction yields no match instead of silently broadening the request. */
    @Test
    fun impossibleFilterDoesNotFallBackToUnfilteredHistory() =
        runBlocking {
            val row = timelineRecord("row", 30uL, "needle")
            val result =
                searchConversationHistoryMatches(
                    appState = testAppState(),
                    accountRef = PINNED_ACCOUNT,
                    groupIdHex = group.groupIdHex,
                    query = "needle",
                    constraints = MessageSearchConstraints(senderIds = setOf("absent")),
                ) { _, _ -> TimelinePageFfi(listOf(row), false, false) }
            assertEquals(emptyList<ConversationSearchMatch>(), result)
        }

    /** Conversation history keeps matches beyond the separate home preview-text scan limit. */
    @Test
    fun fullHistoryNeedleMatchesBeyondTheHomePreviewTextLimit() =
        runBlocking {
            val row = timelineRecord("long", 30uL, "a".repeat(5_000) + " needle")
            val result =
                searchConversationHistoryMatches(
                    appState = testAppState(),
                    accountRef = PINNED_ACCOUNT,
                    groupIdHex = group.groupIdHex,
                    query = "needle",
                ) { _, _ -> TimelinePageFfi(listOf(row), false, false) }
            assertEquals(listOf(ConversationSearchMatch("long", 30uL)), result)
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
