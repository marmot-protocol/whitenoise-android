package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerMotionForegroundTest {
    @Test fun oppositeInkDoesNotPassThroughAnInvisibleGrayAtTheMidpoint() {
        assertEquals(Color.Black, composerMotionForeground(Color.Black, Color.White, Color(0xff808080)))
        assertEquals(Color.White, composerMotionForeground(Color.Black, Color.White, Color.Black))
        assertEquals(Color.Black, composerMotionForeground(Color.Black, Color.White, Color.White))
    }
}
