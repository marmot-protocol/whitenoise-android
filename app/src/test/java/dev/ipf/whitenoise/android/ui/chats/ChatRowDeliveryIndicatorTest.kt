package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A chat row carries its own latest outgoing message's delivery state, using the bubble's glyphs so the
 * list and the conversation cannot describe the same message differently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatRowDeliveryIndicatorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A message still on its way shows the pending clock. */
    @Test
    fun pendingOutgoingMessageShowsTheSendingGlyph() {
        render(ChatRowPortFixtures.item(delivery = ChatListMessageDeliveryStateFfi.PENDING))
        composeRule.onNodeWithContentDescription(context.getString(R.string.sending)).assertIsDisplayed()
    }

    /** A delivered message shows the sent disc, the same one its bubble draws. */
    @Test
    fun deliveredOutgoingMessageShowsTheSentGlyph() {
        render(ChatRowPortFixtures.item(delivery = ChatListMessageDeliveryStateFfi.DELIVERED))
        composeRule.onNodeWithContentDescription(context.getString(R.string.sent)).assertIsDisplayed()
    }

    /** An incoming message is nobody's delivery to track, so the row stays clean. */
    @Test
    fun incomingMessageShowsNoDeliveryGlyph() {
        render(ChatRowPortFixtures.item(delivery = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE))
        composeRule.onNodeWithContentDescription(context.getString(R.string.sending)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.sent)).assertDoesNotExist()
    }

    /** The glyph follows the message rather than the row it was first composed with. */
    @Test
    fun theGlyphFollowsTheMessageFromPendingToSent() {
        var item by mutableStateOf(ChatRowPortFixtures.item(delivery = ChatListMessageDeliveryStateFfi.PENDING))
        renderState { item }
        composeRule.onNodeWithContentDescription(context.getString(R.string.sending)).assertIsDisplayed()

        composeRule.runOnIdle {
            item = ChatRowPortFixtures.item(delivery = ChatListMessageDeliveryStateFfi.DELIVERED)
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.sent)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.sending)).assertDoesNotExist()
    }

    /**
     * A deleted last message has no delivery worth reporting: the row must not keep showing a tick for
     * something that is no longer there.
     */
    @Test
    fun aDeletedLastMessageShowsNoDeliveryGlyph() {
        render(
            ChatRowPortFixtures.item(
                delivery = ChatListMessageDeliveryStateFfi.DELIVERED,
                deletedLastMessage = true,
            ),
        )
        composeRule.onNodeWithContentDescription(context.getString(R.string.sent)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.sending)).assertDoesNotExist()
    }

    private fun render(item: ChatListItem) = renderState { item }

    private fun renderState(item: () -> ChatListItem) {
        val state = ChatRowPortFixtures.state(context)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    ChatRow(
                        item = item(),
                        appState = state,
                        onClick = {},
                        onOpenProfile = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
