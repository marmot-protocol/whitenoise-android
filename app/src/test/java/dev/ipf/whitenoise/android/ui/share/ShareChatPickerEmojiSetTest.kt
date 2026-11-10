package dev.ipf.whitenoise.android.ui.share

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opening a shared emoji-set link from the share sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShareChatPickerEmojiSetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val payload =
        SharePayload(
            text = "shared text",
            streamUris = emptyList(),
            intentMimeType = "text/plain",
        )

    /** A set shared to another profile opens for the chooser's profile, even with the active profile unchanged. */
    @Test
    fun sharedEmojiSetUsesTheSelectedSendingProfile() {
        val otherRef = "Second profile"
        val otherHex = "b3".repeat(32)
        val state =
            appStateWithDirectChats(
                GROUP_A to PEER_A,
                accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX), testAccount(otherRef, otherHex)),
            )
        val opened = mutableListOf<String>()
        val otherTitle = state.networkDisplayName(otherHex)
        composeRule.setContent {
            WhiteNoiseTheme {
                ShareChatPickerFullScreenContent(
                    appState = state,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> false },
                    controllerBinder = { _, _ -> },
                    onOpenEmojiSet = opened::add,
                )
            }
        }
        composeRule.onNodeWithContentDescription(app.getString(R.string.share_choose_sending_account)).performClick()
        composeRule.onNodeWithText(otherTitle).performClick()
        composeRule.onNodeWithText(app.getString(R.string.emoji_sets_open)).performClick()
        assertEquals(listOf(otherRef), opened)
        assertEquals(ACCOUNT_REF, state.activeAccountRef)
    }
}
