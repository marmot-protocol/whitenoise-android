package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertEquals

/**
 * Fails unless the node shows its whole [size] square on screen. `assertIsDisplayed` still accepts a control that a
 * crowded row measured narrower or that an ancestor clipped.
 */
internal fun SemanticsNodeInteraction.assertShownWhole(size: Dp): SemanticsNodeInteraction {
    val shown = getBoundsInRoot()
    assertEquals("visible width", size.value, (shown.right - shown.left).value, 0.5f)
    assertEquals("visible height", size.value, (shown.bottom - shown.top).value, 0.5f)
    return this
}
