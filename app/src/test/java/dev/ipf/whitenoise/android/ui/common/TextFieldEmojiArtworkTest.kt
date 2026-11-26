package dev.ipf.whitenoise.android.ui.common

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Custom emoji in authored text fields and text-only surfaces behave like Unicode emoji. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class TextFieldEmojiArtworkTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Text-only surfaces read built-in and device emoji by name; unknown codes and code spans stay as written. */
    @Test
    fun plainTextNamesEveryDrawableShortcode() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val custom = localEmoji()
        assertEquals(
            "Work Marmot party :unknown: `:wn:`",
            EmojiShortcodes.plainText("Work :marmot: :party: :unknown: `:wn:`", custom, resources),
        )
        assertEquals("Hi Marmot party there", EmojiShortcodes.plainText("Hi:marmot::party:there", custom, resources))
        assertEquals("No codes", EmojiShortcodes.plainText("No codes", custom, resources))
        assertEquals("Ada :party:", EmojiShortcodes.plainText("Ada :party:", CustomEmojiSet.Empty, resources))
    }

    /** A folder name field draws the artwork in place of the code and keeps the stored value canonical. */
    @Test
    fun emojiFieldDrawsArtworkAndKeepsCanonicalValue() {
        val state = TextFieldState("Work :party:")
        setField(state)

        val node = composeRule.onNodeWithTag(FIELD).fetchSemanticsNode()
        val spoken = node.config[SemanticsProperties.EditableText]
        assertEquals("Work :party:", spoken.text)
        assertEquals(VerbatimTtsAnnotation("party"), spoken.getTtsAnnotations(5, 12).single().item)
        assertEquals("Work :party:", state.text.toString())
        assertArtworkVisible()
    }

    @Test
    fun copyingAndCuttingArtworkLeavesTheCanonicalTextOnTheClipboard() {
        val state = TextFieldState("Work :party:")
        setField(state)
        val clipboard =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        val field = composeRule.onNodeWithTag(FIELD)
        field.performClick()

        composeRule.runOnIdle { state.edit { selection = TextRange(5, length) } }
        field.performKeyInput { pressKey(Key.Copy) }
        composeRule.waitUntil(5_000) { clipboard.hasPrimaryClip() }
        composeRule.runOnIdle {
            assertEquals(
                ":party:",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }

        composeRule.runOnIdle {
            clipboard.clearPrimaryClip()
            state.edit { selection = TextRange(5, length) }
        }
        field.performKeyInput { pressKey(Key.Cut) }
        composeRule.waitUntil(5_000) { clipboard.hasPrimaryClip() }
        composeRule.runOnIdle {
            assertEquals(
                ":party:",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
            assertEquals("Work ", state.text.toString())
        }
    }

    @Test
    fun emojiArtworkFollowsTheCaretInAnOverflowingRtlField() {
        val state = TextFieldState("مرحبا ".repeat(20) + ":party:")
        setField(state, LayoutDirection.Rtl)

        composeRule.onNodeWithTag(FIELD).performClick()
        composeRule.runOnIdle { state.edit { placeCursorAtEnd() } }

        assertArtworkVisible()
    }

    @Test
    fun customAndUnicodeEmojiHaveTheSamePositionInBidirectionalText() {
        val state = TextFieldState("אב A :party: אב")
        val actualLayout = AtomicReference<TextLayoutResult>()
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalCustomEmoji provides localEmoji(),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    val artwork = rememberTextFieldEmojiArtwork(state, enabled = true)
                    BasicTextField(
                        state = state,
                        outputTransformation = artwork.output,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        onTextLayout = { result -> actualLayout.set(result()) },
                    )
                }
            }
        }

        fun isLeftOfEnglishLetter(emoji: String): Boolean {
            val layout = requireNotNull(actualLayout.get())
            val text = layout.layoutInput.text.text
            val emojiX = layout.getBoundingBox(text.indexOf(emoji)).center.x
            val englishX = layout.getBoundingBox(text.indexOf('A')).center.x
            return emojiX < englishX
        }
        val customLeft = isLeftOfEnglishLetter(":party:")
        composeRule.runOnIdle { state.edit { replace(0, length, "אב A 🙂 אב") } }
        composeRule.waitUntil(5_000) { actualLayout.get()?.layoutInput?.text?.text?.contains("🙂") == true }
        assertTrue(isLeftOfEnglishLetter("🙂"))
        assertTrue(customLeft)
    }

    @Test
    fun accessibilitySelectionUsesCanonicalOffsets() {
        val authored = "Work :party: there"
        val state = TextFieldState(authored)
        setField(state)
        val field = composeRule.onNodeWithTag(FIELD)
        field.performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        val layout = layouts.single()
        assertEquals(authored, layout.layoutInput.text.text)
        assertTrue(layout.placeholderRects.single() != null)
        val lineEnd = layout.getLineEnd(layout.lineCount - 1)
        field.performSemanticsAction(SemanticsActions.SetSelection) { action ->
            assertTrue(action(lineEnd, lineEnd, true))
        }

        val caret = TextRange(authored.length)
        composeRule.runOnIdle { assertEquals(caret, state.selection) }
        assertEquals(caret, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    @Test
    fun copyingPreservesAuthoredIsolatesAndShortcodesInsideCode() {
        val authored = "`\u2066\u2060:party:\u2069` :party:"
        val state = TextFieldState(authored)
        setField(state)
        val clipboard =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        val field = composeRule.onNodeWithTag(FIELD)
        field.performClick()
        composeRule.runOnIdle { state.edit { selection = TextRange(0, length) } }
        field.performSemanticsAction(SemanticsActions.CopyText)
        composeRule.runOnIdle {
            assertEquals(
                authored,
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }
    }

    @Test
    fun accessibilityCutKeepsOnlyTheSelectedAuthoredControl() {
        val authored = "A\u2066\u2060B\u2069 :party:"
        val state = TextFieldState(authored)
        setField(state)
        val clipboard =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        val field = composeRule.onNodeWithTag(FIELD)
        field.performClick()
        field.performSemanticsAction(SemanticsActions.SetSelection) { action -> assertTrue(action(1, 2, true)) }
        field.performSemanticsAction(SemanticsActions.CutText) { action -> assertTrue(action()) }

        composeRule.runOnIdle {
            val copied =
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
            assertEquals("\u2066", copied)
            assertEquals(authored.removeRange(1, 2), state.text.toString())
        }
    }

    /** One Backspace removes the whole custom emoji, as it removes a Unicode emoji. */
    @Test
    fun backspaceRemovesTheWholeEmoji() {
        val state = TextFieldState("Hi :party:")
        setField(state)

        composeRule.onNodeWithTag(FIELD).performClick()
        composeRule.runOnIdle { state.edit { placeCursorAtEnd() } }
        composeRule.onNodeWithTag(FIELD).performKeyInput { pressKey(Key.Backspace) }
        composeRule.runOnIdle { assertEquals("Hi ", state.text.toString()) }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun softKeyboardDeletesTheWholeEmojiWithoutStartingAnotherInputSession() {
        val state = TextFieldState("Hi :party:")
        val connection = AtomicReference<InputConnection>()
        composeRule.setContent {
            InterceptPlatformTextInput(
                interceptor = { request, _ ->
                    connection.set(request.createInputConnection(EditorInfo()))
                    awaitCancellation()
                },
            ) {
                WhiteNoiseTheme {
                    CompositionLocalProvider(LocalCustomEmoji provides localEmoji()) {
                        WhiteNoiseTextField(state = state, modifier = Modifier.testTag(FIELD), emojiAction = true)
                    }
                }
            }
        }
        composeRule.onNodeWithTag(FIELD).performClick()
        composeRule.waitUntil(5_000) { connection.get() != null }
        val input = connection.get()
        composeRule.runOnIdle {
            state.edit { placeCursorAtEnd() }
            assertTrue(input.deleteSurroundingText(1, 0))
        }
        composeRule.runOnIdle {
            assertEquals("Hi ", state.text.toString())
            assertSame(input, connection.get())
        }
    }

    /** Fields without the emoji action keep shortcodes as text, so codes typed into keys or searches stay literal. */
    @Test
    fun plainFieldsKeepShortcodesAsText() {
        val state = TextFieldState("Work :party:")
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalCustomEmoji provides localEmoji()) {
                    WhiteNoiseTextField(state = state, modifier = Modifier.testTag(FIELD))
                }
            }
        }
        val node = composeRule.onNodeWithTag(FIELD).fetchSemanticsNode()
        assertEquals("Work :party:", node.config[SemanticsProperties.EditableText].text)
    }

    private fun assertArtworkVisible() {
        val pixels = composeRule.onNodeWithTag(FIELD).captureToImage().toPixelMap()
        assertTrue(
            "expected the emoji artwork in the field",
            (0 until pixels.height).any { y -> (0 until pixels.width).any { x -> pixels[x, y].toArgb() == ORANGE } },
        )
    }

    private fun setField(
        state: TextFieldState,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalCustomEmoji provides localEmoji(),
                    LocalLayoutDirection provides direction,
                ) {
                    WhiteNoiseTextField(
                        state = state,
                        modifier = Modifier.testTag(FIELD),
                        emojiAction = true,
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                }
            }
        }
    }

    private fun localEmoji(): CustomEmojiSet {
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(ORANGE) }
        return CustomEmojiSet(listOf(CustomEmoji(":party:", File("party.png"), image.asImageBitmap())))
    }

    private companion object {
        const val FIELD = "emoji.field"
        const val ORANGE = 0xFFEF6C00.toInt()
    }
}
