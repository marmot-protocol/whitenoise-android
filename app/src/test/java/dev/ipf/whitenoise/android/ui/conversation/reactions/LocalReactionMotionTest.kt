package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReactionMotionTest {
    /** Identical adds each get a fresh intent sequence, and a removal cancels the pending effect. */
    @Test fun identicalAddsGetDistinctIntentsAndRemovalCancels() {
        val motion = LocalReactionMotion()
        motion.accept("👍", true)
        val first = motion.effect
        motion.accept("👍", true)
        assertNotEquals(first?.sequence, motion.effect?.sequence)
        motion.settle(first)
        assertNotEquals(null, motion.effect)
        motion.accept("👍", false)
        assertNull(motion.effect)
    }

    /** An origin is consumed only by its selected emoji, and a mismatched take discards it. */
    @Test fun originIsConsumedOnlyByItsSelectedEmoji() {
        val motion = LocalReactionMotion()
        motion.choose("👍", Rect(10f, 20f, 40f, 50f))
        assertNull(motion.takeOrigin("❤"))
        assertNull(motion.takeOrigin("👍"))
    }

    /** A matching origin is returned once and is gone on the second take. */
    @Test fun matchingOriginIsConsumedOnce() {
        val motion = LocalReactionMotion()
        val bounds = Rect(10f, 20f, 40f, 50f)
        motion.choose("👍", bounds)
        assertEquals(bounds, motion.takeOrigin("👍"))
        assertNull(motion.takeOrigin("👍"))
    }

    /** A disabled owner starts no effects, and a disposed owner starts none and keeps no targets. */
    @Test fun disabledAndDisposedOwnersCannotStartEffects() {
        val motion = LocalReactionMotion()
        motion.enabled = false
        motion.accept("👍", true)
        assertNull(motion.effect)
        motion.enabled = true
        motion.accept("👍", true)
        motion.dispose()
        motion.accept("👍", true)
        assertNull(motion.effect)
        assertEquals(0, motion.targets.size)
    }

    /** A custom emoji reaction carries no origin, so it never starts an extra decode or glyph flight. */
    @Test fun customEmojiNeverStartsAnAdditionalDecodeOrGlyphFlight() {
        val motion = LocalReactionMotion()
        motion.accept(":party:", true, Rect(10f, 20f, 40f, 50f))
        assertNull(motion.effect?.origin)
    }

    /**
     * A popup origin outside the transcript viewport, or an empty viewport, cannot fly and falls back to chip feedback.
     */
    @Test fun popupOutsideTranscriptFallsBackToChipFeedback() {
        val motion = LocalReactionMotion()
        motion.viewport = Rect(0f, 80f, 360f, 520f)
        val chip = Rect(250f, 120f, 280f, 148f)
        assertFalse(motion.canFly(Rect(30f, 60f, 58f, 88f), chip))
        assertTrue(motion.canFly(Rect(30f, 90f, 58f, 118f), chip))
        motion.viewport = Rect.Zero
        assertFalse(motion.canFly(Rect(30f, 90f, 58f, 118f), chip))
    }

    /** An accepted fifth reaction type targets the existing overflow chip, and its removal clears that target. */
    @Test fun acceptedFifthTypeTargetsTheExistingOverflowChip() {
        val motion = LocalReactionMotion()
        val tallies =
            listOf("❤", "🎉", "🔥", "😂").map {
                dev.ipf.whitenoise.android.core
                    .ReactionTally(it, 2, false)
            } +
                dev.ipf.whitenoise.android.core
                    .ReactionTally("👍", 1, true)
        motion.accept("👍", true)
        val overflow = reactionPills(tallies).last()
        assertEquals("👍", overflow.motionEmoji(tallies, motion))
        motion.accept("👍", false)
        assertNull(overflow.motionEmoji(tallies, motion))
    }
}
