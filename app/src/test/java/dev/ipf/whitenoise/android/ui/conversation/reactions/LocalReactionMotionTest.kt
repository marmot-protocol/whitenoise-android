package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReactionMotionTest {
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

    @Test fun originIsConsumedOnlyByItsSelectedEmoji() {
        val motion = LocalReactionMotion()
        motion.choose("👍", Rect(10f, 20f, 40f, 50f))
        assertNull(motion.takeOrigin("❤"))
        assertNull(motion.takeOrigin("👍"))
    }

    @Test fun matchingOriginIsConsumedOnce() {
        val motion = LocalReactionMotion()
        val bounds = Rect(10f, 20f, 40f, 50f)
        motion.choose("👍", bounds)
        assertEquals(bounds, motion.takeOrigin("👍"))
        assertNull(motion.takeOrigin("👍"))
    }

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

    @Test fun customEmojiNeverStartsAnAdditionalDecodeOrGlyphFlight() {
        val motion = LocalReactionMotion()
        motion.accept(":party:", true, Rect(10f, 20f, 40f, 50f))
        assertNull(motion.effect?.origin)
    }

    @Test fun popupOutsideTranscriptFallsBackToChipFeedback() {
        val motion = LocalReactionMotion()
        motion.viewport = Rect(0f, 80f, 360f, 520f)
        val chip = Rect(250f, 120f, 280f, 148f)
        assertFalse(motion.canFly(Rect(30f, 60f, 58f, 88f), chip))
        assertTrue(motion.canFly(Rect(30f, 90f, 58f, 118f), chip))
        motion.viewport = Rect.Zero
        assertFalse(motion.canFly(Rect(30f, 90f, 58f, 118f), chip))
    }

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
