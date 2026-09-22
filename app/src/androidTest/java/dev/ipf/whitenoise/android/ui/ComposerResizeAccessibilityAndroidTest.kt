package dev.ipf.whitenoise.android.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_ACCESSIBILITY_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerResizeAccessibilityAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Android exports the dedicated resize leaf as screen-reader focusable with no click action. */
    @Test
    fun resizeLeafIsScreenReaderFocusableAndOwnsTheNamedCustomAction() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    Box(Modifier.width(320.dp).height(280.dp)) {
                        ComposerPill(
                            textFieldValue = TextFieldValue(LONG_DRAFT),
                            composerFocus = remember { FocusRequester() },
                            emojiPickerOpen = false,
                            onValueChange = {},
                            onEmojiPickerToggle = {},
                            onAttachmentsToggle = {},
                            attachmentSheetOpen = false,
                            onPickFromGallery = {},
                            onPickDocument = null,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(COMPOSER_RESIZE_ACCESSIBILITY_TAG, useUnmergedTree = true).assertExists()
        val description = composeRule.activity.getString(R.string.composer_resize)
        val accessibility = InstrumentationRegistry.getInstrumentation().uiAutomation
        composeRule.waitForIdle()
        val info = accessibility.rootInActiveWindow?.findByDescription(description)

        assertNotNull("the resize leaf must be exported through AccessibilityNodeInfo", info)
        checkNotNull(info)
        assertTrue("TalkBack must be able to focus the resize leaf", info.isScreenReaderFocusable)
        assertTrue(
            "the platform node must own the named expand action",
            info.actionList.any { it.label == composeRule.activity.getString(R.string.composer_expand_full_screen) },
        )
        assertTrue(
            "the platform node must not restore ordinary tap behavior",
            info.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK },
        )
    }

    /** Finds the target through the platform accessibility tree without Compose virtual IDs. */
    private fun AccessibilityNodeInfo.findByDescription(expectedDescription: String): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending += this
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.contentDescription == expectedDescription) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
        }
        return null
    }

    private companion object {
        const val LONG_DRAFT =
            "A thoughtful long message starts here.\n" +
                "It keeps growing naturally line by line.\n" +
                "The controls remain easy to reach.\n" +
                "Nothing in the draft is replaced."
    }
}
