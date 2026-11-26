package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.MentionComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rendered `:shortcode:` emoji in the composer: visible slots, offset mapping and whole-emoji editing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerEmojiTextTest {
    @Test
    fun renderedShortcodeBecomesOneArtworkSlot() {
        val text = "hi :marmot: and :party:!"
        val visual = visual(text)

        assertEquals("hi $COMPOSER_EMOJI_SLOT and $COMPOSER_EMOJI_SLOT!", visual.mapping.text)
        assertEquals(listOf(":marmot:", ":party:"), visual.emoji.map { it.item })
        assertEquals(3, visual.emoji.first().start)
        // The caret after the shortcode sits after its slot, and the slot maps back to the whole code.
        assertEquals(3 + COMPOSER_EMOJI_SLOT.length, visual.mapping.originalToTransformed("hi :marmot:".length))
        assertEquals("hi :marmot:".length, visual.mapping.transformedToOriginal(3 + COMPOSER_EMOJI_SLOT.length))
        assertEquals(3, visual.mapping.transformedToOriginal(3))
    }

    @Test
    fun unknownAndLiteralShortcodesStayText() {
        val text = "`:marmot:` :unknown: :party:"
        val visual = visual(text)

        assertEquals(listOf(":party:"), visual.emoji.map { it.item })
        assertEquals("`:marmot:` :unknown: $COMPOSER_EMOJI_SLOT", visual.mapping.text)
    }

    @Test
    fun mentionLabelsAndEmojiShareOneMapping() {
        val npub = "npub1" + "q".repeat(58)
        val text = "@$npub :marmot: hey"
        val mentions =
            MentionComposer.editingVisualText(
                text,
                listOf(MentionComposer.Candidate(accountIdHex = "aa".repeat(32), npub = npub, displayName = "Alice")),
            )
        val visual = composerVisualText(text, mentions, composerEmojiRanges(text, CODES))

        assertEquals("@Alice $COMPOSER_EMOJI_SLOT hey", visual.mapping.text)
        assertEquals(listOf(0 until "@Alice".length), visual.mentionRanges)
        assertEquals("@Alice ".length, visual.emoji.single().start)
        assertEquals(visual.mapping.text.length, visual.mapping.originalToTransformed(text.length))
    }

    @Test
    fun backspaceAfterRenderedEmojiDeletesTheWholeShortcode() {
        val old = TextFieldValue("a :marmot: b", selection = TextRange("a :marmot:".length))
        val proposed = TextFieldValue("a :marmot b", selection = TextRange("a :marmot".length))

        val repaired = repairComposerMentionEdit(old, proposed, clampMentionSelection = false, emojiShortcodes = CODES)

        assertEquals("a  b", repaired.text)
        assertEquals(TextRange(2), repaired.selection)
    }

    @Test
    fun backspaceBetweenAdjacentShortcodesDeletesTheOneBeforeTheCaret() {
        val old = TextFieldValue(":marmot::party:", selection = TextRange(":marmot:".length))
        val proposed = TextFieldValue(":marmot:party:", selection = TextRange(":marmot".length))

        val repaired = repairComposerEmojiDeletion(old, proposed, CODES)

        assertEquals(":party:", repaired?.text)
        assertEquals(TextRange(0), repaired?.selection)
    }

    @Test
    fun deletionsOutsideRenderedShortcodesAreUnchanged() {
        val old = TextFieldValue("ab :unknown:", selection = TextRange(12))
        val proposed = TextFieldValue("ab :unknown", selection = TextRange(11))

        assertNull(repairComposerEmojiDeletion(old, proposed, CODES))
        assertEquals(proposed, repairComposerMentionEdit(old, proposed, false, CODES))
    }

    @Test
    fun caretMovementStepsOverARenderedShortcode() {
        val text = "x :marmot: y"
        val afterEmoji = TextFieldValue(text, selection = TextRange("x :marmot:".length))
        val leftOne = afterEmoji.copy(selection = TextRange("x :marmot".length))
        val beforeEmoji = TextFieldValue(text, selection = TextRange(2))
        val rightOne = beforeEmoji.copy(selection = TextRange(3))

        assertEquals(TextRange(2), clampComposerEmojiSelection(afterEmoji, leftOne, CODES).selection)
        assertEquals(
            TextRange("x :marmot:".length),
            clampComposerEmojiSelection(beforeEmoji, rightOne, CODES).selection,
        )
    }

    @Test
    fun aComposingShortcodeStaysTextUntilCommitted() {
        val text = "say :marmot:"
        val composing = TextFieldValue(text, selection = TextRange(11), composition = TextRange(5, 11))
        val committed = TextFieldValue(text, selection = TextRange(text.length))

        assertEquals(emptyList<Any>(), activeComposerEmojiRanges(composing, CODES))
        val ranges = activeComposerEmojiRanges(committed, CODES)
        val visible = ComposerVisualTransformation(null, SpanStyle(), null, ranges).filter(AnnotatedString(text)).text
        assertEquals("say $COMPOSER_EMOJI_SLOT", visible.text)
        val slots = visible.getStringAnnotations(COMPOSER_EMOJI_TAG, 0, visible.length)
        assertEquals(listOf(":marmot:"), slots.map { it.item })
    }

    @Test
    fun aCaretInsideAShortcodeKeepsItEditableText() {
        // Typing the opening `:` in front of `marmot:` leaves the caret inside the new code.
        val typed = TextFieldValue(":marmot:", selection = TextRange(1))

        assertEquals(emptyList<Any>(), activeComposerEmojiRanges(typed, CODES))
        val proposed = TextFieldValue("::marmot:", selection = TextRange(2))
        assertEquals(proposed, repairComposerMentionEdit(typed, proposed, false, CODES))
    }

    @Test
    fun backspaceInAComposingShortcodeDeletesOneCharacter() {
        val old = TextFieldValue("a :marmot:", selection = TextRange(10), composition = TextRange(3, 10))
        val proposed = TextFieldValue("a :marmot", selection = TextRange(9), composition = TextRange(3, 9))

        assertNull(repairComposerEmojiDeletion(old, proposed, CODES))
    }

    @Test
    fun aKeyboardThatStartsComposingInsideARenderedEmojiEditsItAsText() {
        val old = TextFieldValue(":marmot:", selection = TextRange(8))
        val renamed = TextFieldValue(":marmo:", selection = TextRange(6), composition = TextRange(1, 6))
        val moved = old.copy(selection = TextRange(4), composition = TextRange(1, 7))

        assertEquals(renamed, repairComposerMentionEdit(old, renamed, false, CODES))
        assertEquals(moved, repairComposerMentionEdit(old, moved, false, CODES))
    }

    @Test
    fun compositionIsComparedAfterTheDeletionShiftsIt() {
        // The keyboard deletes around the caret and composes the emoji's first letter: edit it as text.
        val spaced = TextFieldValue(" :marmot:", selection = TextRange(1))
        val composing = TextFieldValue("marmot:", selection = TextRange(1), composition = TextRange(0, 1))
        assertNull(repairComposerEmojiDeletion(spaced, composing, CODES))

        // Composition on the following word shifts onto the emoji's old offsets; the emoji still deletes whole.
        val trailing = TextFieldValue(":marmot: x", selection = TextRange(9), composition = TextRange(9, 10))
        val split = TextFieldValue(":marmotx", selection = TextRange(7), composition = TextRange(7, 8))
        assertEquals("x", repairComposerEmojiDeletion(trailing, split, CODES)?.text)
    }

    private fun visual(text: String): ComposerVisualText =
        composerVisualText(
            text,
            MentionComposer.ChipVisualText(text, emptyList(), text.length),
            composerEmojiRanges(text, CODES),
        )

    private companion object {
        val CODES = setOf(":marmot:", ":party:")
    }
}
