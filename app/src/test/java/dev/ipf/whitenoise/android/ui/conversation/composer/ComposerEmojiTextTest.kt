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
        assertEquals(TextRange("x :marmot:".length), clampComposerEmojiSelection(beforeEmoji, rightOne, CODES).selection)
    }

    @Test
    fun aComposingShortcodeStaysTextUntilCommitted() {
        val text = "say :marmot:"
        val emoji = setOf(":marmot:")
        val composing =
            ComposerVisualTransformation(null, SpanStyle(), TextRange(5, 11), emoji).filter(AnnotatedString(text)).text
        val committed = ComposerVisualTransformation(null, SpanStyle(), null, emoji).filter(AnnotatedString(text)).text

        assertEquals(text, composing.text)
        assertEquals("say $COMPOSER_EMOJI_SLOT", committed.text)
        assertEquals(
            listOf(":marmot:"),
            committed.getStringAnnotations(COMPOSER_EMOJI_TAG, 0, committed.length).map { it.item },
        )
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
