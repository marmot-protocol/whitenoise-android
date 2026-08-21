package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ComposerSendAcceptanceBoundaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun optimisticAcceptanceClearsVisibleTextWithoutDeletingThePersistedDraft() {
        val persistedDraftChanges = mutableListOf<String>()
        var afterSendCount = 0
        val sentText = "survives until MDK accepts"

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, onAccepted -> onAccepted() },
                        initialDraft = TextFieldValue(sentText),
                        onDraftChange = { persistedDraftChanges += it.text },
                        onAfterSend = { afterSendCount += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(sentText).assertDoesNotExist()
        assertEquals(emptyList<String>(), persistedDraftChanges)
        assertEquals(1, afterSendCount)
    }
}
