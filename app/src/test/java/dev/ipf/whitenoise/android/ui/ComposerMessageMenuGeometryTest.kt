package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerMessageMenuGeometryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyComposerRetainsEditingHeightAcrossMenuFocusLossAndRestoration() {
        val focus = FocusRequester()
        lateinit var focusManager: FocusManager
        var preserve by mutableStateOf(false)
        var height = 0
        composeRule.setContent {
            focusManager = LocalFocusManager.current
            WhiteNoiseTheme {
                Surface {
                    Box(Modifier.width(360.dp)) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            composerFocus = focus,
                            preserveComposerEditingLayout = preserve,
                            modifier = Modifier.onSizeChanged { height = it.height },
                        )
                    }
                }
            }
        }
        composeRule.runOnIdle { focus.requestFocus() }
        composeRule.waitForIdle()
        val editingHeight = height
        composeRule.runOnIdle {
            preserve = true
            focusManager.clearFocus(force = true)
        }
        composeRule.waitForIdle()
        assertEquals("Menu focus loss must not collapse an empty composer", editingHeight, height)
        composeRule.runOnIdle { focus.requestFocus() }
        composeRule.runOnIdle { preserve = false }
        composeRule.waitForIdle()
        assertEquals("Returning focus must keep the same composer geometry", editingHeight, height)
        composeRule.runOnIdle { focusManager.clearFocus(force = true) }
        composeRule.waitForIdle()
        assertTrue("Ordinary focus loss must still collapse the composer", height < editingHeight)
    }
}
