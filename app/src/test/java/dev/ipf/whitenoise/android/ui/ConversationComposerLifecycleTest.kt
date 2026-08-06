package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.ipf.whitenoise.android.ui.conversation.ConversationComposerLifecycleEffect
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationComposerLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun focusedComposerRestoresExactEditingStateAfterPauseResume() {
        val text = "draft text"
        var value by mutableStateOf(TextFieldValue(text, selection = TextRange(2, 7)))
        var composerFocused by mutableStateOf(false)
        var showComposer by mutableStateOf(true)
        val lifecycleOwner = TestLifecycleOwner()
        val focusRequester = FocusRequester()

        composeRule.setContent {
            ComposerLifecycleHarness(
                lifecycleOwner = lifecycleOwner,
                value = value,
                onValueChange = { value = it },
                focusRequester = focusRequester,
                composerFocused = composerFocused,
                onComposerFocusChanged = { composerFocused = it },
                showComposer = showComposer,
            )
        }

        val composer = composeRule.onNodeWithText(text)
        composeRule.runOnIdle {
            lifecycleOwner.handle(Lifecycle.Event.ON_START)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
            focusRequester.requestFocus()
        }
        composeRule.waitForIdle()
        composer.assertIsFocused()

        val selection = TextRange(2, 7)
        assertEquals(selection, value.selection)
        composeRule.runOnIdle { lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { lifecycleOwner.handle(Lifecycle.Event.ON_RESUME) }
        composeRule.waitForIdle()
        composer.assertIsFocused()
        assertEquals(selection, value.selection)

        val caret = TextRange(4)
        composeRule.runOnIdle { value = value.copy(selection = caret) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            showComposer = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showComposer = true }
        composeRule.waitForIdle()
        composer.assertIsNotFocused()
        assertEquals(caret, value.selection)

        composeRule.runOnIdle { lifecycleOwner.handle(Lifecycle.Event.ON_RESUME) }
        composeRule.waitForIdle()

        composer.assertIsFocused()
        assertEquals(caret, value.selection)
    }

    @Test
    fun blurredComposerClearsSystemRestoredFocusAfterPauseResume() {
        val initialValue = TextFieldValue("draft text", selection = TextRange(4))
        var value by mutableStateOf(initialValue)
        var composerFocused by mutableStateOf(false)
        val lifecycleOwner = TestLifecycleOwner()
        val focusRequester = FocusRequester()
        var clearFocusCalls = 0

        composeRule.setContent {
            ComposerLifecycleHarness(
                lifecycleOwner = lifecycleOwner,
                value = value,
                onValueChange = { value = it },
                focusRequester = focusRequester,
                composerFocused = composerFocused,
                onComposerFocusChanged = { composerFocused = it },
                onClearFocus = { clearFocusCalls++ },
            )
        }

        val composer = composeRule.onNodeWithText(initialValue.text)
        composeRule.runOnIdle {
            lifecycleOwner.handle(Lifecycle.Event.ON_START)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()
        composer.assertIsNotFocused()

        composeRule.runOnIdle {
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            focusRequester.requestFocus()
        }
        composeRule.waitForIdle()
        composer.assertIsFocused()
        clearFocusCalls = 0

        composeRule.runOnIdle { lifecycleOwner.handle(Lifecycle.Event.ON_RESUME) }
        composeRule.waitForIdle()

        composer.assertIsNotFocused()
        assertEquals(1, clearFocusCalls)
        assertEquals(initialValue, value)
    }

    @Test
    fun lifecycleObserverReadsLatestSearchFocusOwnership() {
        var searchOpen by mutableStateOf(false)
        val lifecycleOwner = TestLifecycleOwner()
        var clearFocus = false

        composeRule.setContent {
            ConversationComposerLifecycleEffect(
                observerKey = lifecycleOwner,
                lifecycleOwner = lifecycleOwner,
                composerFocused = false,
                searchOpen = searchOpen,
                hasActiveEditOrReplySession = false,
                onPause = {},
                onResume = { _, shouldClearFocus -> clearFocus = shouldClearFocus },
            )
        }

        composeRule.runOnIdle {
            lifecycleOwner.handle(Lifecycle.Event.ON_START)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
        }
        assertTrue(clearFocus)
        composeRule.runOnIdle {
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            searchOpen = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { lifecycleOwner.handle(Lifecycle.Event.ON_RESUME) }

        assertFalse(clearFocus)
    }
}

@Composable
private fun ComposerLifecycleHarness(
    lifecycleOwner: LifecycleOwner,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    composerFocused: Boolean,
    onComposerFocusChanged: (Boolean) -> Unit,
    showComposer: Boolean = true,
    onClearFocus: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    ConversationComposerLifecycleEffect(
        observerKey = lifecycleOwner,
        lifecycleOwner = lifecycleOwner,
        composerFocused = composerFocused,
        searchOpen = false,
        hasActiveEditOrReplySession = false,
        onPause = {},
        onResume = { restoreComposerFocus, clearFocus ->
            when {
                restoreComposerFocus -> focusRequester.requestFocus()
                clearFocus -> {
                    onClearFocus()
                    focusManager.clearFocus(force = true)
                }
            }
        },
    )
    WhiteNoiseTheme {
        Surface {
            if (showComposer) {
                ComposerPill(
                    textFieldValue = value,
                    composerFocus = focusRequester,
                    emojiPickerOpen = false,
                    onValueChange = onValueChange,
                    onEmojiPickerToggle = {},
                    onAttachmentsToggle = {},
                    attachmentSheetOpen = false,
                    onPickFromGallery = null,
                    onPickDocument = null,
                    onComposerFocusChanged = onComposerFocusChanged,
                )
            }
        }
    }
}

private class TestLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    init {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override val lifecycle: Lifecycle = registry

    fun handle(event: Lifecycle.Event) {
        registry.handleLifecycleEvent(event)
    }
}
