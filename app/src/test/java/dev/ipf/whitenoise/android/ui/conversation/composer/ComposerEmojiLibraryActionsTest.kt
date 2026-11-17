package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The conversation emoji pane offers Browse sets and the emoji settings cog beside search (#3882).
 * Each opens its page through the conversation and leaves the draft alone; without an owner for the
 * active account's library the pane hides both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerEmojiLibraryActionsTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearRememberedKeyboardHeight() {
        composerImePaneHeightMemory.clear()
    }

    @Test
    fun paneActionsOpenBrowseAndSettingsAndKeepTheDraft() {
        var browse = 0
        var settings = 0
        val text = ComposerTextState(TextFieldValue("draft"))
        render(text, onBrowse = { browse++ }, onSettings = { settings++ })
        openPane()

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.emoji_sets_title))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.custom_emoji))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, browse)
        assertEquals(1, settings)
        assertEquals("the draft survives both actions", "draft", text.valueState.value.text)
    }

    @Test
    fun paneHidesLibraryActionsWithoutAnOwner() {
        render(ComposerTextState(TextFieldValue()), onBrowse = null, onSettings = null)
        openPane()

        composeRule.onNodeWithTag(EMOJI_PICKER_BROWSE_SETS_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(EMOJI_PICKER_SETTINGS_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun paneActionsLight() = capture(fontScale = 1f, direction = LayoutDirection.Ltr, dark = false, name = "light")

    @Test
    fun paneActionsLargeRtlDark() = capture(fontScale = 2f, direction = LayoutDirection.Rtl, dark = true, name = "large_rtl_dark")

    private fun capture(
        fontScale: Float,
        direction: LayoutDirection,
        dark: Boolean,
        name: String,
    ) {
        render(ComposerTextState(TextFieldValue()), onBrowse = {}, onSettings = {}, fontScale, direction, dark)
        openPane()
        composeRule.onNodeWithTag(EMOJI_PICKER_BROWSE_SETS_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(EMOJI_PICKER_SETTINGS_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/composer_emoji_library_actions_$name.png")
    }

    private fun openPane() {
        composeRule.onNode(hasSetTextAction()).requestFocus()
        composeRule.waitForIdle()
        composeRule
            .onAllNodesWithContentDescription(context.getString(R.string.open_emoji_picker))
            .onFirst()
            .performClick()
        composeRule.waitForIdle()
    }

    @Suppress("LongParameterList")
    private fun render(
        text: ComposerTextState,
        onBrowse: (() -> Unit)?,
        onSettings: (() -> Unit)?,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        dark: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface {
                        Box(Modifier.width(360.dp).height(760.dp), contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                appState = null,
                                draftAccountRef = null,
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                onPickDocument = {},
                                textState = text,
                                onBrowseEmojiSets = onBrowse,
                                onOpenEmojiSettings = onSettings,
                                modifier = Modifier.testTag(ROOT_TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val ROOT_TAG = "composer-emoji-library-actions"
    }
}
