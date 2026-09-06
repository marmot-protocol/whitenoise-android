package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactRowSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Followed and selected are announced separately while row actions remain available. */
    @Test
    fun followedAndSelectedStatesAreDistinctAndActionsRemainAvailable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val followedLabel = context.getString(R.string.user_search_you_follow)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ContactRow(
                        title = "Alice",
                        subtitle = followedLabel,
                        avatarSeed = "alice",
                        avatarUrl = null,
                        isFollowed = true,
                        selectionState = true,
                        onClick = {},
                        onLongClick = {},
                        modifier = Modifier.testTag(MEMBER_LIST_MARKER),
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(MEMBER_LIST_MARKER)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, followedLabel))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        composeRule
            .onNodeWithTag(FOLLOWED_PERSON_BADGE_TEST_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onAllNodes(hasStateDescription(followedLabel), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithText(followedLabel, useUnmergedTree = true).assertDoesNotExist()
    }

    /** Unfollowed rows retain their ordinary subtitle and do not leak followed semantics. */
    @Test
    fun unfollowedRowHasNoBadgeOrFollowedState() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val followedLabel = context.getString(R.string.user_search_you_follow)
        val searchResultLabel = context.getString(R.string.user_search_result)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ContactRow(
                        title = "Grace",
                        subtitle = searchResultLabel,
                        avatarSeed = "grace",
                        avatarUrl = null,
                        onClick = {},
                        modifier = Modifier.testTag(MEMBER_LIST_MARKER),
                    )
                }
            }
        }

        composeRule.onNodeWithText(searchResultLabel).assertIsDisplayed()
        composeRule.onNodeWithTag(FOLLOWED_PERSON_BADGE_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule
            .onAllNodes(hasStateDescription(followedLabel), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private companion object {
        const val MEMBER_LIST_MARKER = "performance.member_list"
    }
}
