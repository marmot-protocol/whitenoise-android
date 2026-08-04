package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class SelectedMembersReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun summaryOpensReviewWithoutChangingTheSelection() {
        var opens = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMemberSummary(
                    members = members,
                    appState = appState(),
                    onClick = { opens += 1 },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.selected)).assertIsDisplayed().performClick()

        assertEquals(1, opens)
        assertEquals(2, members.size)
    }

    @Test
    fun reviewShowsFullIdentityRowsAndLargeRemoveActions() {
        var removed: RecipientSearch.Candidate? = null
        var confirmed = false
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMembersReviewScreen(
                    members = members,
                    appState = appState(),
                    busy = false,
                    onBack = {},
                    onRemove = { removed = it },
                    onConfirm = { confirmed = true },
                )
            }
        }

        composeRule
            .onAllNodesWithContentDescription(context.getString(R.string.remove_member))
            .assertCountEquals(2)[0]
            .performClick()
        assertEquals(members.first(), removed)

        composeRule.onNodeWithContentDescription(context.getString(R.string.next)).performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun busyReviewCannotConfirmOrRemoveMembers() {
        var removed = false
        var confirmed = false
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMembersReviewScreen(
                    members = members,
                    appState = appState(),
                    busy = true,
                    onBack = {},
                    onRemove = { removed = true },
                    onConfirm = { confirmed = true },
                )
            }
        }

        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsNotEnabled().performClick()
        composeRule
            .onAllNodesWithContentDescription(context.getString(R.string.remove_member))[0]
            .assertIsNotEnabled()

        assertEquals(false, confirmed)
        assertEquals(false, removed)
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "alice",
                        accountIdHex = "alice",
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = "alice",
        )

    private companion object {
        val members =
            listOf(
                RecipientSearch.Candidate("a".repeat(64), "Alexandria Example", "npub1alexandria"),
                RecipientSearch.Candidate("b".repeat(64), "Benjamin Example", "npub1benjamin"),
            )

        object EmptyDraftPersistence : DraftPersistence {
            override fun read(): Map<String, String> = emptyMap()

            override fun write(
                key: String,
                value: String?,
            ) = Unit
        }
    }
}
