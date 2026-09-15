package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native composer acceptance protects staged media, empty captions and text typed after the send tap. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerAttachmentSendTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Rejection allows retry and late acceptance preserves newer caption. */
    @Test
    fun rejectionAllowsRetryAndLateAcceptancePreservesNewerCaption() {
        var result: ((Boolean) -> Unit)? = null
        val sent = mutableListOf<String>()
        var plainSends = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> plainSends++ },
                        initialDraft = TextFieldValue("Caption"),
                        hasPendingAttachments = true,
                        onSendAttachments = { text, callback ->
                            sent += text
                            result = callback
                        },
                    )
                }
            }
        }
        val send = composeRule.onNodeWithContentDescription(context.getString(R.string.send))
        send.performClick()
        send.performClick()
        assertEquals(listOf("Caption"), sent)
        composeRule.runOnIdle { result!!(false) }
        send.performClick()
        assertEquals(listOf("Caption", "Caption"), sent)
        composeRule.onNodeWithText("Caption").performTextReplacement("Next message")
        composeRule.runOnIdle { result!!(true) }
        composeRule.onNodeWithText("Next message").assertExists()
        assertEquals(0, plainSends)
    }

    /** Attachment without caption uses media send path. */
    @Test
    fun attachmentWithoutCaptionUsesMediaSendPath() {
        var mediaCaption: String? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerBar(
                    replyingTo = null,
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> error("Plain send must not receive an attachment") },
                    hasPendingAttachments = true,
                    onSendAttachments = { text, result ->
                        mediaCaption = text
                        result(false)
                    },
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        assertEquals("", mediaCaption)
    }
}
