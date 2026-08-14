package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class ComposerDictationControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun availableDictationDoesNotDisplaceOrMoveTheEmojiAction() {
        render()

        val before =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()

        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()

        val after =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()

        assertEquals(before.left, after.left)
        assertEquals(before.top, after.top)
        assertEquals(before.right, after.right)
        assertEquals(before.bottom, after.bottom)
    }

    @Test
    fun compactLargeFontRtlLayoutKeepsTheEmojiActionReachableWithoutClipping() {
        render(fontScale = 2f, rtl = true)

        val root = composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val action =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        val field = composeRule.onNode(hasSetTextAction()).assertIsDisplayed().getUnclippedBoundsInRoot()

        assertTrue(action.left >= root.left && action.right <= root.right)
        assertTrue(action.top >= root.top && action.bottom <= root.bottom)
        assertTrue(action.right - action.left >= 48.dp)
        assertTrue(action.bottom - action.top >= 48.dp)
        assertTrue("RTL emoji action must remain on the leading side", action.left >= field.left)
    }

    private fun render(
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        val draft = TextFieldValue("")
        val dictationController = idleDictationController(draft)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = draft,
                        dictationController = dictationController,
                        dictationAccountRef = ACCOUNT,
                        dictationGroupIdHex = GROUP,
                        modifier = Modifier.width(320.dp).testTag(ROOT_TAG),
                    )
                }
            }
        }
    }

    private fun idleDictationController(draft: TextFieldValue): ConversationDictationController =
        ConversationDictationController(
            platform = FakeDictationPlatform,
            readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, 0L) },
            writeDraft = { _, _, _, _ -> true },
            disclosureAccepted = { true },
            markDisclosureAccepted = {},
            scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
        )

    private data object FakeDictationPlatform : ConversationDictationPlatform {
        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession =
            object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit
            }
    }

    private companion object {
        const val ROOT_TAG = "composer-dictation-control-root"
        const val ACCOUNT = "account"
        const val GROUP = "group"
    }
}
