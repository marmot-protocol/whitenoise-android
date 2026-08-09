package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageActionMenuLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actionModelPreservesCapabilityOrder() {
        assertEquals(
            listOf(
                MessageActionKind.Reply,
                MessageActionKind.Edit,
                MessageActionKind.Select,
                MessageActionKind.SelectText,
                MessageActionKind.CopyText,
                MessageActionKind.Speak,
                MessageActionKind.Forward,
                MessageActionKind.Save,
                MessageActionKind.Info,
            ),
            messageActionKinds(
                canReply = true,
                canEdit = true,
                canSelect = true,
                canSelectText = true,
                canCopyText = true,
                canSpeak = true,
                canForward = true,
                canSave = true,
            ),
        )
    }

    @Test
    fun columnAndHeightEstimatesTrackGridRowsAndDeleteSection() {
        assertEquals(2, messageActionColumnCount(312.dp, 136.dp))
        assertEquals(1, messageActionColumnCount(260.dp, 136.dp))
        assertEquals(382.dp, estimatedMessageActionMenuHeight(9, 2, canReact = true, canDelete = true))
        assertEquals(582.dp, estimatedMessageActionMenuHeight(9, 1, canReact = true, canDelete = true))
    }

    @Test
    fun maximumMenuUsesRowMajorTwoColumnGridAndFullWidthDelete() {
        renderMenu(fontScale = 1f)

        val reply = bounds("Reply")
        val edit = bounds("Edit")
        val select = bounds("Select")
        val selectText = bounds("Select text")
        val delete = bounds("Delete")

        assertEquals(reply.top, edit.top, 0.5f)
        assertTrue(reply.left < edit.left)
        assertEquals(select.top, selectText.top, 0.5f)
        assertTrue(select.top > reply.top)
        assertTrue(delete.width > reply.width * 1.8f)
        assertTrue(delete.top > bounds("Message info").bottom)
    }

    @Test
    fun largeFontFallsBackToOneReadableColumn() {
        renderMenu(fontScale = 2f)

        assertTrue(bounds("Edit").top > bounds("Reply").top)
        assertTrue(bounds("Select text").top > bounds("Select").top)
        composeRule.onNodeWithText("Delete", substring = false).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun rtlKeepsRowMajorOrderFromTheStartEdge() {
        renderMenu(fontScale = 1f, layoutDirection = LayoutDirection.Rtl)

        assertEquals(bounds("Reply").top, bounds("Edit").top, 0.5f)
        assertTrue(bounds("Reply").left > bounds("Edit").left)
    }

    @Test
    fun everyMaximumVariantActionInvokesItsOriginalCallback() {
        val callbacks = mutableListOf<String>()
        renderMenu(fontScale = 1f, callbacks = callbacks)

        listOf(
            "Reply",
            "Edit",
            "Select",
            "Select text",
            "Copy text",
            "Speak aloud",
            "Forward",
            "Save",
            "Message info",
            "Delete",
        ).forEach { composeRule.onNodeWithText(it, substring = false).performClick() }

        assertEquals(
            listOf("reply", "edit", "select", "selectText", "copy", "speak", "forward", "save", "info", "delete"),
            callbacks,
        )
    }

    private fun renderMenu(
        fontScale: Float,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        callbacks: MutableList<String> = mutableListOf(),
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    MessageActionMenu(
                        expanded = true,
                        anchorWindowYPx = 8f,
                        alignEnd = false,
                        canReply = true,
                        canReact = false,
                        canDelete = true,
                        canEdit = true,
                        canForward = true,
                        canSelect = true,
                        canCopyText = true,
                        canSpeak = true,
                        canSelectText = true,
                        canSave = true,
                        quickReactionEmojis = emptyList(),
                        onDismissRequest = {},
                        onReact = {},
                        onOpenEmojiPicker = {},
                        onReply = { callbacks += "reply" },
                        onEdit = { callbacks += "edit" },
                        onForward = { callbacks += "forward" },
                        onSelect = { callbacks += "select" },
                        onSelectText = { callbacks += "selectText" },
                        onCopyText = { callbacks += "copy" },
                        onSpeak = { callbacks += "speak" },
                        onSave = { callbacks += "save" },
                        onInfo = { callbacks += "info" },
                        onDelete = { callbacks += "delete" },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(label: String) =
        composeRule
            .onNodeWithText(label, substring = false)
            .fetchSemanticsNode()
            .boundsInRoot
}
