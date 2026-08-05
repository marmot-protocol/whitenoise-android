package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMemberSummary(
                    members = members,
                    appState = state,
                    onClick = { opens += 1 },
                )
            }
        }

        composeRule
            .onNodeWithText(members.joinToString { member -> member.displayName })
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.selected_members_count, 2, 2))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.review_selected_members))
            .performClick()

        assertEquals(1, opens)
        assertEquals(2, members.size)
    }

    @Test
    fun busySummaryCannotOpenReview() {
        var opens = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMemberSummary(
                    members = members,
                    appState = appState(),
                    onClick = { opens += 1 },
                    enabled = false,
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.review_selected_members))
            .assertIsNotEnabled()
            .performClick()

        assertEquals(0, opens)
    }

    @Test
    fun reviewShowsFullIdentityRowsAndLargeRemoveActions() {
        val removed = mutableListOf<RecipientSearch.Candidate>()
        var confirmed = false
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMembersReviewScreen(
                    members = members,
                    appState = state,
                    busy = false,
                    onBack = {},
                    onRemove = { removed += it },
                    onConfirm = { confirmed = true },
                    confirmIcon = Icons.AutoMirrored.Filled.Send,
                    confirmLabel = context.getString(R.string.send),
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.review_selection)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).assertIsDisplayed()
        members.forEach { member ->
            composeRule
                .onNodeWithContentDescription(
                    context.getString(R.string.remove_member_named, member.displayName),
                ).performClick()
        }
        assertEquals(members, removed)

        composeRule.onNodeWithText(context.getString(R.string.send)).performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun busyReviewCannotConfirmOrRemoveMembers() {
        var removed = false
        var confirmed = false
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMembersReviewScreen(
                    members = members,
                    appState = state,
                    busy = true,
                    onBack = {},
                    onRemove = { removed = true },
                    onConfirm = { confirmed = true },
                    confirmIcon = Icons.Default.Check,
                    confirmLabel = context.getString(R.string.add_member),
                )
            }
        }

        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsNotEnabled().performClick()
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.remove_member_named, members.first().displayName),
            ).assertIsNotEnabled()
            .performClick()

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
