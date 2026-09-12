package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageBodyMatch
import dev.ipf.whitenoise.android.core.SnippetHighlight
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real row inputs retain their native meaning while presentation changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatRowPortPresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A body hit replaces the latest preview and displays the matched message's own timestamp. */
    @Test fun bodyMatchKeepsItsOwnPreviewAndTimestamp() {
        val item =
            ChatRowPortFixtures.item().let { original ->
                original.copy(
                    projection =
                        requireNotNull(original.projection).copy(
                            lastMessage = requireNotNull(original.projection?.lastMessage).copy(timelineAt = 1uL),
                        ),
                )
            }
        val match =
            MessageBodyMatch(
                "g1",
                "match-id",
                SnippetHighlight("The matching words", 4, 12),
                (System.currentTimeMillis() / 1000).toULong(),
            )
        render(item, match)
        composeRule.onNodeWithText("The matching words").assertExists()
        composeRule.onNodeWithText(ChatRowPortFixtures.PREVIEW).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.relative_time_now)).assertExists()
    }

    /** Removed membership suppresses the old unread alert while keeping its precise accessibility description. */
    @Test fun removedRowRetainsRemovalMeaningAndSuppressesUnread() {
        render(ChatRowPortFixtures.item(membership = SelfMembershipFfi.REMOVED, unread = true))
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.chat_row_removed_description))
            .assertExists()
        composeRule.onNodeWithText("3").assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.group_system_you_member_left))
            .assertDoesNotExist()
    }

    /** A pin and muted/timer status remain independently present instead of replacing each other. */
    @Test fun pinnedMutedTimedRowKeepsEveryStatus() {
        render(ChatRowPortFixtures.item(pinned = true, retentionSeconds = 86_400uL), muted = true)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_pinned_badge)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_muted_badge)).assertExists()
        val timed =
            context.getString(R.string.disappearing_messages) + ": " + context.getString(R.string.disappearing_1_day)
        composeRule.onNodeWithContentDescription(timed).assertExists()
    }

    /** Terminal lifecycle copy must not describe a disbanded conversation as the user's voluntary leave. */
    @Test fun disbandedAndLeavingHaveDifferentNativeLabels() {
        val item = ChatRowPortFixtures.item()
        val disbanded =
            item.copy(
                projection = requireNotNull(item.projection).copy(lifecycleState = GroupLifecycleStateFfi.DISBANDED),
            )
        val leaving = item.copy(projection = requireNotNull(item.projection).copy(leaveRequestPending = true))
        val groupLeaving = item.copy(group = item.group.copy(leaveRequestPending = true), projection = null)
        assertEquals(
            R.string.conversation_disbanded_notice,
            chatRowMembershipStatus(disbanded, ChatRowPortFixtures.ACCOUNT_HEX),
        )
        assertEquals(R.string.leaving_chat, chatRowMembershipStatus(leaving, ChatRowPortFixtures.ACCOUNT_HEX))
        assertEquals(R.string.leaving_chat, chatRowMembershipStatus(groupLeaving, ChatRowPortFixtures.ACCOUNT_HEX))
        assertEquals(
            R.string.group_system_you_member_left,
            chatRowMembershipStatus(
                ChatRowPortFixtures.item(membership = SelfMembershipFfi.LEFT),
                ChatRowPortFixtures.ACCOUNT_HEX,
            ),
        )
    }

    private fun render(
        item: ChatListItem,
        bodyMatch: MessageBodyMatch? = null,
        muted: Boolean = false,
    ) {
        val state = ChatRowPortFixtures.state(context)
        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.fillMaxWidth()) {
                    ChatRow(item, state, onClick = {}, onOpenProfile = {}, isMuted = muted, bodyMatch = bodyMatch)
                }
            }
        }
    }
}
