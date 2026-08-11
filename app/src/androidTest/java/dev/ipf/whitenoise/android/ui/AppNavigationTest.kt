package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.account.AccountAvatarButton
import dev.ipf.whitenoise.android.ui.account.SettingsAccountHeader
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TRANSIENT_NOTICE_TAG
import dev.ipf.whitenoise.android.ui.conversation.ConversationTransientNotice
import dev.ipf.whitenoise.android.ui.settings.SettingsTopBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun avatarButtonOpensSettingsWithoutDrawerNavigation() {
        var settingsClicks = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                AccountAvatarButton(
                    title = "Ada Lovelace",
                    seed = "ada",
                    pictureUrl = null,
                    size = 40.dp,
                    onClick = { settingsClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open navigation").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
        composeRule.onNodeWithText("AL").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.runOnIdle { assertEquals(1, settingsClicks) }
    }

    @Test
    fun avatarWithoutUnreadDoesNotAnnounceUnread() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountAvatarButton(
                    title = "Ada Lovelace",
                    seed = "ada",
                    pictureUrl = null,
                    size = 40.dp,
                    onClick = {},
                    showUnreadDot = false,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("This account has unread messages", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun avatarWithUnreadAnnouncesThisAccountUnread() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountAvatarButton(
                    title = "Ada Lovelace",
                    seed = "ada",
                    pictureUrl = null,
                    size = 40.dp,
                    onClick = {},
                    showUnreadDot = true,
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("This account has unread messages", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun settingsTopBarReturnsToChatListWithBackLink() {
        var backClicks = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsTopBar(onBackToChats = { backClicks += 1 })
            }
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Chats").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open navigation").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Back to chats").performClick()
        composeRule.runOnIdle { assertEquals(1, backClicks) }
    }

    @Test
    fun loadingScreenHasNoBrandingText() {
        // LoadingScreen is a bare centered spinner — no branding text. Its visual
        // rendering is covered by the Roborazzi screenshot pilot; this just guards
        // that stray branding copy doesn't creep back onto it.
        composeRule.setContent {
            WhiteNoiseTheme {
                LoadingScreen()
            }
        }

        composeRule.onNodeWithText("Loading White Noise").assertDoesNotExist()
        composeRule.onNodeWithText("Starting Marmot").assertDoesNotExist()
    }

    @Test
    fun groupConfirmationStaysInsideItsOriginatingConversationWithoutMovingHeader() {
        val visibleGroup = mutableStateOf("group-b")
        val notice = mutableStateOf<TransientNotice?>(null)
        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("conversation-header"),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        ConversationTransientNotice(
                            notice = notice.value,
                            accountRef = "account-a",
                            groupIdHex = visibleGroup.value,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val headerBefore = composeRule.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot

        composeRule.runOnUiThread {
            notice.value =
                TransientNotice(
                    id = 1L,
                    title = AppText.Plain("Admin removed"),
                    conversation = ConversationNoticeDestination("account-a", "group-a"),
                )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Admin removed").assertDoesNotExist()
        assertEquals(headerBefore, composeRule.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot)

        composeRule.runOnUiThread { visibleGroup.value = "group-a" }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Admin removed").assertIsDisplayed()
        val noticeBounds =
            composeRule.onNodeWithTag(CONVERSATION_TRANSIENT_NOTICE_TAG).fetchSemanticsNode().boundsInRoot
        val headerAfter = composeRule.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        assertEquals(headerBefore, headerAfter)
        assertTrue(noticeBounds.top >= headerAfter.bottom)
    }

    @Test
    fun accountHeaderSeparatesSelectorFromQrAction() {
        var selectorClicks = 0
        var qrClicks = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsAccountHeader(
                    title = "Main Identity",
                    subtitle = "npub1abc...xyz",
                    seed = "main-identity",
                    pictureUrl = null,
                    onOpenAccountSelector = { selectorClicks += 1 },
                    onOpenQr = { qrClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Switch Account").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("My QR code").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Switch Account").performClick()
        composeRule.runOnIdle {
            assertEquals(1, selectorClicks)
            assertEquals(0, qrClicks)
        }

        composeRule.onNodeWithContentDescription("My QR code").performClick()
        composeRule.runOnIdle {
            assertEquals(1, selectorClicks)
            assertEquals(1, qrClicks)
        }
    }
}
