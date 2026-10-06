package dev.ipf.whitenoise.android.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.messages.KEPT_MESSAGE_CARD_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.KEPT_MESSAGE_MENU_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessageKey
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessagePresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessagesController
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessagesOverlay
import dev.ipf.whitenoise.android.ui.conversation.messages.KeptMessagesOverlayState
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberKeptMessageEntries
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android runtime coverage for the real floating card; projected media mapping is covered by JVM regressions. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class KeptMediaCardDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Large-text RTL keeps file identity, expansion, original-message navigation and removal usable. */
    @Test
    fun mediaCardRemainsUsableAtLargeTextRtl() {
        val key = KeptMessageKey("synthetic-account", "synthetic-group", "synthetic-message")
        val controller = KeptMessagesController().apply { keep(key) }
        val item = fixtureMessage()
        var opened: KeptMessageKey? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 2f) {
                    Box(Modifier.fillMaxSize()) {
                        val entries = rememberKeptMessageEntries(controller, key.accountRef) { item }
                        KeptMessagesOverlay(
                            state = KeptMessagesOverlayState(entries, controller, key.accountRef),
                            composerHeight = 180.dp,
                            presentation = {
                                KeptMessagePresentation(
                                    "Alice",
                                    "Review before tomorrow",
                                    "Design",
                                    "12:34",
                                    listOf(
                                        KeptAttachmentPresentation(
                                            KeptAttachmentKind.File,
                                            "Release notes.pdf",
                                            "File",
                                            "Open the original to view",
                                        ),
                                    ),
                                )
                            },
                            onOpenMessage = { opened = it },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("Release notes.pdf").assertIsDisplayed()
        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).performTouchInput { longClick() }
        composeRule.onNodeWithTag(KEPT_MESSAGE_MENU_TAG).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.go_to_message)).performClick()
        assertEquals(key, opened)
        composeRule.runOnIdle { controller.remove(key) }
        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).assertDoesNotExist()
    }

    /** A generated record supplies only identity; this test never opens an account, relay or media source. */
    private fun fixtureMessage(): TimelineMessage =
        TimelineMessage(
            "msg:synthetic-message",
            AppMessageRecordFfi(
                messageIdHex = "synthetic-message",
                direction = "received",
                groupIdHex = "synthetic-group",
                sender = "synthetic-author",
                plaintext = "Review before tomorrow",
                contentTokens =
                    MarkdownDocumentFfi(
                        blocks = emptyList(),
                        truncated = false,
                        blankLinesBefore = byteArrayOf(),
                    ),
                kind = 9uL,
                tags = emptyList(),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = 1_700_000_000uL,
                receivedAt = 1_700_000_000uL,
            ),
            MessageStatus.Received,
        )
}
