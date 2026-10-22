package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.chatListItemFromProjection
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.ui.common.captureClickCallbackForReplay
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonGroupsMembershipLifetimeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun disposedVisitCallbacksStayRetiredWhenTheSamePersonIsMountedAgain() {
        val appState = emptyAppState()
        val mounted = mutableStateOf(true)
        var adds = 0
        rule.setContent {
            WhiteNoiseTheme {
                if (mounted.value) {
                    PersonGroupsMembershipContent(
                        appState = appState,
                        account = null,
                        personHex = "cc".repeat(32),
                        personName = "River",
                        groups = emptyList(),
                        ownerIsCurrent = { true },
                        onFeedback = {},
                        onMembershipChanged = {},
                        rows = emptyList(),
                        unresolved = false,
                        onBack = {},
                        onOpen = {},
                        onAdd = { adds++ },
                        onRetry = {},
                        avatar = { null },
                    )
                }
            }
        }
        val oldAdd = addButton().captureClickCallbackForReplay()
        rule.runOnIdle { mounted.value = false }
        rule.waitForIdle()
        rule.runOnIdle { mounted.value = true }
        rule.waitForIdle()
        rule.runOnIdle {
            oldAdd()
            assertEquals(0, adds)
        }
        addButton().performClick()
        rule.runOnIdle { assertEquals(1, adds) }
    }

    @Test
    fun knownMembershipRowsDoNotBecomeUnavailableWhileAdministrationIsAbsent() {
        val appState = emptyAppState()
        val item = chatListItemFromProjection(chatRow("cc".repeat(32)))
        rule.setContent {
            WhiteNoiseTheme {
                PersonGroupsMembershipContent(
                    appState = appState,
                    account = null,
                    personHex = "dd".repeat(32),
                    personName = "River",
                    groups = listOf(item),
                    ownerIsCurrent = { true },
                    onFeedback = {},
                    onMembershipChanged = {},
                    rows = listOf(PersonSharedGroupRow(item.id, "Friends", 2)),
                    unresolved = false,
                    onBack = {},
                    onOpen = {},
                    onAdd = {},
                    onRetry = {},
                    avatar = { null },
                )
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.onNodeWithText(context.getString(R.string.person_group_memberships_unavailable)).assertDoesNotExist()
        rule.onNodeWithText("Friends").assertExists()
    }

    @Test
    fun committedRemovalRefreshesParentAfterChildRetiresWithoutChildFeedback() =
        runBlocking {
            val state = MembershipVisitState(PersonProfileOwner { true })
            state.busy = true
            state.owner.dispose()
            var refreshes = 0
            var feedback = 0
            var retries = 0
            completeMembershipRemoval(
                emptyAppState(),
                state,
                true,
                null,
                { refreshes++ },
                { feedback++ },
                { retries++ },
            )
            assertEquals(1, refreshes)
            assertEquals(0, feedback)
            assertEquals(0, retries)
            assertEquals(0, state.retry)
        }

    /** The tag belongs to SettingsBottomAction; capture the actual nested production button's callback. */
    private fun addButton() =
        rule.onNode(
            hasClickAction() and hasAnyAncestor(hasTestTag("groups_in_common.add")),
            useUnmergedTree = true,
        )
}
