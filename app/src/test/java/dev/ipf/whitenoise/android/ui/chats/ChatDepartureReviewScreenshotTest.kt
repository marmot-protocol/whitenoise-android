package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.chatListItemFromProjection
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatDepartureReviewScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** The actual bulk review renders every same-named destination and the captured actor. */
    @Test
    fun bulkDepartureNamesEachCapturedGroupAndActor() {
        val state = emptyAppState()
        val controller = ChatsController(state, ACCOUNT_REF) { _, _ -> emptyList() }
        val items =
            listOf("garden-one", "garden-two").map {
                chatListItemFromProjection(chatRow(it).copy(title = "Garden club", groupName = "Garden club"))
            }
        val request = PendingChatDeparture(items, controller, state, GroupTitleCopy.Default)
        rule.setContent {
            WhiteNoiseTheme {
                ChatDepartureBatchDialog(request, state, controller, ChatDepartureActions(onDismiss = {}))
            }
        }
        rule.onNodeWithTag("group.action.acting_account").assertExists()
        rule.onAllNodesWithText("Garden club").assertCountEquals(2)
        rule.onNodeWithTag("confirmation.confirm").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed()
        rule.onRoot().captureRoboImage("src/test/snapshots/group_departure_bulk_review_light.png")
    }

    /** The actual final successor review keeps its actions reachable in large RTL AMOLED. */
    @Test
    fun successorReviewLargeRtlKeepsActorGroupAndCombinedAction() {
        val state = emptyAppState()
        val successor = AppGroupMemberRecordFfi("b".repeat(64), null, false)
        val decision = ChatDepartureDecision(ChatDepartureTarget("garden-one", "Garden club", false), listOf(successor))
        val ui =
            ChatDepartureUi().apply {
                this.decision = decision
                selected = successor
            }
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 2f) {
                    ChatDepartureSuccessor(ui, state, ACCOUNT_REF)
                }
            }
        }
        rule.onNodeWithTag("group.action.acting_account").assertExists()
        rule.onNodeWithTag("group.members.review.destination").assertExists()
        rule.onNodeWithTag("group.members.review.member").assertExists()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.onNodeWithText(context.getString(R.string.group_action_transfer_and_leave)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed()
        rule.onRoot().captureRoboImage("src/test/snapshots/group_departure_successor_review_amoled_large_rtl.png")
        rule
            .onNodeWithContentDescription("Public key (hex), ${successor.memberIdHex}")
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("Garden club").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.group_action_transfer_and_leave)).assertIsDisplayed()
        rule.onRoot().captureRoboImage("src/test/snapshots/group_departure_successor_identity_amoled_large_rtl.png")
    }
}
