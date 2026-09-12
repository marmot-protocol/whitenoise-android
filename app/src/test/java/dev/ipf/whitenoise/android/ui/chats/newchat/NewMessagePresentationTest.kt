package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Material pointer/semantic behavior over the production presentation and already-merged recipient data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1600dp-mdpi")
class NewMessagePresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val calls = mutableListOf<String>()
    private val person = RecipientSearch.Candidate("b".repeat(64), "Ada", "npub1ada", isFollowing = true)

    /** Target quick links remain present for a nonblank search and invoke their distinct existing owners. */
    @Test fun searchKeepsQuickActionsAvailable() {
        show()
        composeRule.onNodeWithText(context.getString(R.string.new_group)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.new_message_connect_qr)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.new_message_invite_friend)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.show_my_qr_code)).performClick()
        assertEquals(listOf("group", "scan", "invite", "qr"), calls)
    }

    /** Material long-press opens the profile and does not also activate the chat tap callback. */
    @Test fun nativeLongPressAndTapAreIndependent() {
        show()
        composeRule
            .onNodeWithTag("creation.person.${person.accountIdHex}")
            .performTouchInput { longClick() }
        assertEquals(listOf("profile"), calls)
        composeRule.onNodeWithTag("creation.person.${person.accountIdHex}").performClick()
        assertEquals(listOf("profile", "person"), calls)
    }

    /** Followed status is announced independently of grouping and is not a selection state. */
    @Test fun followedNativeRowPreservesRelationshipSemantics() {
        show()
        composeRule.onNodeWithTag("creation.person.${person.accountIdHex}").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.user_search_you_follow),
            ),
        )
        composeRule.onNodeWithTag(FOLLOWED_PERSON_BADGE_TEST_TAG, useUnmergedTree = true).assertExists()
    }

    /** A current create prevents new navigation or a second person activation. */
    @Test fun creatingDisablesActionsAndPeople() {
        show(creating = person.accountIdHex)
        composeRule.onNodeWithText(context.getString(R.string.new_group)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.new_message_connect_qr)).assertIsNotEnabled()
        composeRule.onNodeWithTag("creation.person.${person.accountIdHex}").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.clear)).assertIsNotEnabled()
    }

    /** Partial remote results never remove local matches; retry invokes the real owner's retry boundary. */
    @Test fun partialSearchRetainsPeopleAndOffersRetry() {
        show(search = RecipientUserSearchState(isIncomplete = true))
        composeRule.onNodeWithTag("creation.person.${person.accountIdHex}").assertExists()
        composeRule.onNodeWithTag("people.retry").performClick()
        assertEquals(listOf("retrySearch"), calls)
    }

    /** Resolving an identifier is not a completed empty search and never exposes a premature invitation. */
    @Test fun identifierResolutionDoesNotClaimNoMatches() {
        show(people = emptyList(), identifier = true, resolving = true)
        composeRule.onNodeWithText(context.getString(R.string.no_matches)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.user_search_searching)).assertExists()
    }

    /** An unresolved address offers a deliberate retry without implying it is a valid recipient. */
    @Test fun unresolvedAddressOffersRetry() {
        composeRule.setContent {
            WhiteNoiseTheme {
                NewMessageSearchFeedback(
                    false,
                    false,
                    false,
                    true,
                    false,
                    { calls += "retryAddress" },
                    {},
                    retryableIdentifier = true,
                )
            }
        }
        composeRule.onNodeWithTag("people.retry").performClick()
        assertEquals(listOf("retryAddress"), calls)
    }

    /** Native canonical creation recovery shows Open chat and keeps the original diagnostics copy/retry callbacks. */
    @Test fun createdRecoveryKeepsRetryAndCopy() {
        val error =
            StartChatErrorUiState(
                "npub",
                "hex",
                AppText.Plain("Projection unavailable"),
                diagnosticReport = "Safe diagnostic",
                retryGroupIdHex = "canonical",
            )
        composeRule.setContent {
            WhiteNoiseTheme { Surface { StartChatErrorCard(error, { calls += "retryChat" }, {}, { calls += it }) } }
        }
        composeRule.onNodeWithText(context.getString(R.string.new_message_chat_created)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.new_message_open_chat)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.copy)).performClick()
        assertEquals(listOf("retryChat", "Safe diagnostic"), calls)
    }

    /** The display source honors known-chat provenance and explicit following, never discovery radius. */
    @Test fun sourceGroupingUsesOnlyNativeProvenanceAndFollowing() {
        assertEquals(
            R.string.new_message_source_chats,
            newMessageSource(person.copy(source = RecipientSearch.Source.InDm)),
        )
        assertEquals(R.string.new_message_source_following, newMessageSource(person))
        assertEquals(
            R.string.new_message_source_network,
            newMessageSource(person.copy(isFollowing = false, searchRadius = 1u)),
        )
        assertEquals(R.string.new_message_source_local, newMessageSource(person.copy(isFollowing = false)))
    }

    /** Narrow large text keeps a recovery action reachable by scrolling, with a native minimum touch target. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h480dp-mdpi")
    fun largeTextRetryRemainsReachable() {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                NewMessageContent(
                    TextFieldState("Ada"),
                    emptyList(),
                    RecipientUserSearchState(failed = true),
                    false,
                    false,
                    true,
                    null,
                    null,
                    NewMessageActions({}, {}, {}, {}, {}, { calls += "retrySearch" }, {}, {}, {}, {}, {}),
                )
            }
        }
        val retry = composeRule.onNodeWithTag("people.retry").performScrollTo()
        val bounds = retry.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(bounds.height >= 48f)
        retry.performClick()
        assertEquals(listOf("retrySearch"), calls)
    }

    /** Display fixtures supply no search engine or mutation success; callback tests use the production composables. */
    private fun show(
        search: RecipientUserSearchState = RecipientUserSearchState(),
        people: List<NewMessagePerson> = listOf(NewMessagePerson(person, "npub1ada", null)),
        creating: String? = null,
        identifier: Boolean = false,
        resolving: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                NewMessageContent(
                    TextFieldState("Ada"),
                    people,
                    search,
                    identifier,
                    resolving,
                    true,
                    creating,
                    null,
                    NewMessageActions(
                        {},
                        { calls += "group" },
                        { calls += "scan" },
                        { calls += "qr" },
                        { calls += "invite" },
                        { calls += "retrySearch" },
                        { calls += "retryChat" },
                        {},
                        { calls += "person" },
                        { calls += "profile" },
                        {},
                    ),
                )
            }
        }
    }
}
