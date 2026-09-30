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

    @Test fun readableClipboardPastesOnOneClickWithoutOpeningMenu() {
        val toolbar = TestToolbar()
        var pastes = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SystemPasteIconButton(onPaste = {
                    pastes++
                    true
                }) { Text("Paste") }
            }
        }
        composeRule.runOnIdle { assertEquals(0, pastes) }
        composeRule.onNodeWithText("Paste").performClick()
        composeRule.runOnIdle {
            assertEquals(1, pastes)
            assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        }
    }

    @Test fun deniedClipboardPastesOnlyAfterSystemGrant() {
        val toolbar = TestToolbar()
        var readable = false
        var pastes = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SystemPasteIconButton(onPaste = {
                    if (readable) pastes++
                    readable
                }) { Text("Paste") }
            }
        }
        composeRule.onNodeWithText("Paste").performClick()
        composeRule.runOnIdle {
            assertEquals(0, pastes)
            assertEquals(TextToolbarStatus.Shown, toolbar.status)
            readable = true
            toolbar.selectPaste()
            assertEquals(1, pastes)
        }
    }

    @Test fun cancellingFallbackDoesNotRetryClipboardRead() {
        val toolbar = TestToolbar()
        var attempts = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SystemPasteIconButton(onPaste = {
                    attempts++
                    false
                }) { Text("Paste") }
            }
        }
        composeRule.onNodeWithText("Paste").performClick()
        composeRule.runOnIdle {
            toolbar.hide()
            assertEquals(1, attempts)
        }
    }

    @Test fun disabledButtonDoesNotReadClipboardOrOpenMenu() {
        val toolbar = TestToolbar()
        var attempts = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SystemPasteIconButton(onPaste = {
                    attempts++
                    true
                }, enabled = false) { Text("Paste") }
            }
        }
        composeRule.onNodeWithText("Paste").performClick()
        composeRule.runOnIdle {
            assertEquals(0, attempts)
            assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        }
    }

    @Test fun disabledAfterOpeningMenuCannotPaste() {
        val toolbar = TestToolbar()
        val enabled = mutableStateOf(true)
        var readable = false
        var pastes = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SystemPasteIconButton(onPaste = {
                    if (readable) pastes++
                    readable
                }, enabled = enabled.value) {
                    Text("Paste")
                }
            }
        }
        composeRule.onNodeWithText("Paste").performClick()
        composeRule.runOnIdle { enabled.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            readable = true
            toolbar.selectPaste()
            assertEquals(0, pastes)
        }
    }

    @Test fun leavingCompositionDismissesOwnedMenu() {
        val toolbar = TestToolbar()
        val visible = mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                if (visible.value) SystemPasteIconButton(onPaste = { false }) { Text("Paste") }
            }
        }
        composeRule.onNodeWithText("Paste").performClick()
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    @Test fun callbackRetainedByDismissedToolbarCannotPasteAfterDisposal() {
        val toolbar = TestToolbar()
        val visible = mutableStateOf(true)
        var attempts = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                if (visible.value) {
                    SystemPasteIconButton(onPaste = {
                        attempts++
                        false
                    }) { Text("Paste") }
                }
            }
        }
        composeRule.onNodeWithText("Paste").performClick()
        lateinit var stalePaste: () -> Unit
        composeRule.runOnIdle {
            stalePaste = checkNotNull(toolbar.paste)
            visible.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            stalePaste()
            assertEquals(1, attempts)
        }
    }

    private class TestToolbar : TextToolbar {
        var paste: (() -> Unit)? = null
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
