package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class SystemPasteIconButtonTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun disabledAfterOpeningMenuCannotPaste() {
        val toolbar = TestToolbar()
        val enabled = mutableStateOf(true)
        var pastes = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SystemPasteIconButton(onPaste = { pastes++ }, enabled = enabled.value) { Text("Paste") }
            }
        }
        composeRule.onNodeWithText("Paste").performClick()
        composeRule.runOnIdle { enabled.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            toolbar.selectPaste()
            assertEquals(0, pastes)
        }
    }

    @Test fun leavingCompositionDismissesOwnedMenu() {
        val toolbar = TestToolbar()
        val visible = mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                if (visible.value) SystemPasteIconButton(onPaste = {}) { Text("Paste") }
            }
        }
        composeRule.onNodeWithText("Paste").performClick()
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    private class TestToolbar : TextToolbar {
        private var paste: (() -> Unit)? = null
        override var status = TextToolbarStatus.Hidden

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            paste = onPasteRequested
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            paste = null
            status = TextToolbarStatus.Hidden
        }

        fun selectPaste() {
            check(status == TextToolbarStatus.Shown)
            paste?.invoke()
            hide()
        }
    }
}
