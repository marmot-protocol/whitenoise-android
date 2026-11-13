package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Holding a composer control or + menu row explains it instead of running it or opening customization. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerShortcutExplanationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var cameraCalls = 0
    private var galleryCalls = 0
    private var customizeCalls = 0

    @Test
    fun holdingCameraExplainsItWithoutCapturingAndTheSheetActionOpensCustomization() {
        render()
        composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { longClick() }
        assertExplanation(R.string.explanation_composer_camera)
        assertEquals(listOf(0, 0), listOf(cameraCalls, customizeCalls))
        composeRule.onNodeWithTag("explanation.action").performClick()
        composeRule.onNodeWithTag("explanation.sheet").assertDoesNotExist()
        assertEquals(listOf(0, 1), listOf(cameraCalls, customizeCalls))
    }

    @Test
    fun explainAccessibilityActionShowsTheSameSheetWithoutCapturing() {
        render()
        val explain =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.attachment_camera))
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == context.getString(R.string.explanation_action_explain) }
        composeRule.runOnIdle { assertTrue(explain.action()) }
        assertExplanation(R.string.explanation_composer_camera)
        assertEquals(0, cameraCalls)
    }

    @Test
    fun holdingAPlusMenuRowClosesTheMenuAndExplainsItWithoutRunningTheRow() {
        render()
        composeRule.onNodeWithTag("composer.shortcut.more").performTouchInput { click() }
        val gallery = hasText(context.getString(R.string.attachment_photos_videos)) and hasAnyAncestor(isPopup())
        composeRule.onNode(gallery).performTouchInput { longClick() }
        composeRule.onNodeWithTag("conversation.attachment.menu").assertDoesNotExist()
        assertExplanation(R.string.explanation_composer_gallery)
        assertEquals(listOf(0, 0), listOf(galleryCalls, customizeCalls))
    }

    @Test
    fun plusMenuCustomizeRowOpensCustomization() {
        render()
        composeRule.onNodeWithTag("composer.shortcut.more").performTouchInput { click() }
        composeRule.onNodeWithTag("conversation.attachment.menu.customize").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation.attachment.menu").assertDoesNotExist()
        assertEquals(listOf(0, 1), listOf(galleryCalls, customizeCalls))
    }

    private fun assertExplanation(body: Int) {
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
    }

    private fun render() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString(
                "composer_toolbar_v1",
                ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera)).encode(),
            ).commit()
        val appState = emptyAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ExplanationHost {
                    Surface {
                        Box(Modifier.width(360.dp).height(600.dp), contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                appState = appState,
                                onCustomizeToolbar = { customizeCalls++ },
                                draftAccountRef = null,
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                textState = ComposerTextState(TextFieldValue()),
                                onCaptureFromCamera = { cameraCalls++ },
                                onPickFromGallery = { galleryCalls++ },
                                onPickDocument = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
