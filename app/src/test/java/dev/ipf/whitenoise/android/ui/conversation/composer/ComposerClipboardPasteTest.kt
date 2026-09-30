package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Explicit clipboard access enters the ordinary acceptance-aware composer draft without focusing the field. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerClipboardPasteTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager = context.getSystemService(ClipboardManager::class.java)

    /** Paste replaces the mic in the idle slot and Send clears only after acceptance. */
    @Test fun pasteExpandsToSendWithoutFocusAndAcceptanceClears() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "first\nsecond"))
        val recorder = recorder()
        val textState = ComposerTextState(TextFieldValue(""))
        val focusChanges = mutableListOf<Boolean>()
        val sends = mutableListOf<String>()
        var accepted: (() -> Unit)? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { text, callback ->
                            sends += text
                            accepted = callback
                        },
                        voiceRecordingController = recorder,
                        textState = textState,
                        onComposerFocusChanged = { focusChanges += it },
                    )
                }
            }
        }
        val paste = composeRule.onNodeWithContentDescription(context.getString(R.string.paste))
        paste.assertExists().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.paste)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).assertExists().performClick()
        assertEquals("first\nsecond", textState.valueState.value.text)
        assertEquals("first\nsecond".length, textState.valueState.value.selection.start)
        assertEquals(listOf("first\nsecond"), sends)
        assertTrue(focusChanges.none { it })
        composeRule.runOnIdle { accepted!!() }
        assertEquals("", textState.valueState.value.text)
    }

    /** A send rejection retains the paste so the user can retry. */
    @Test fun rejectedSendKeepsPastedDraft() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "retry"))
        val textState = ComposerTextState(TextFieldValue(""))
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerBar(
                    replyingTo = null,
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> },
                    voiceRecordingController = recorder(),
                    textState = textState,
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.paste)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.onNodeWithText("retry").assertExists()
        assertEquals("retry", textState.valueState.value.text)
    }

    /** A recorder fixture supplies the production microphone slot without starting audio capture. */
    private fun recorder() =
        VoiceRecordingController(
            context = context,
            outputDirectory = context.cacheDir,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onPermissionRequest = { true },
            onRecordingComplete = { _, _ -> },
            onError = {},
        )
}
