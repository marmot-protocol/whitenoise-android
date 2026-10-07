package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Checks the visible toolbar action stays an accessible button without a text label. */
internal fun assertDraftTopIconButton(action: SemanticsNodeInteraction) {
    val semantics = action.assertIsDisplayed().fetchSemanticsNode().config
    assertEquals(Role.Button, semantics[SemanticsProperties.Role])
    assertEquals(listOf("Scroll to top"), semantics[SemanticsProperties.ContentDescription])
    assertTrue("the toolbar button must not show a text label", !semantics.contains(SemanticsProperties.Text))
}
