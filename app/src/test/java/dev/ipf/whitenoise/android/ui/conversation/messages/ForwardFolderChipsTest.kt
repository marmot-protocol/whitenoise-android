package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.ui.chats.ChatFolderChip
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h560dp-mdpi")
class ForwardFolderChipsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Each compact control exposes the full name/count and Off, mixed, or On checkbox state. */
    @Test
    fun chipsExposeFolderNameEligibleCountAndEveryToggleState() {
        val rows =
            listOf(
                folder("on", "Selected folder") to listOf("on-a", "on-b"),
                folder("mixed", "Mixed folder") to listOf("mixed-a", "mixed-b"),
                folder("off", "Available folder") to listOf("off-a", "off-b", "off-c"),
            )
        render(rows = rows, selected = listOf("on-a", "on-b", "mixed-a"))

        assertChipSemantics("on", "Selected folder", 2, ToggleableState.On)
        assertChipSemantics("mixed", "Mixed folder", 2, ToggleableState.Indeterminate)
        assertChipSemantics("off", "Available folder", 3, ToggleableState.Off)
    }

    /** Binary use of the shared chip keeps selected semantics without claiming a tri-state value. */
    @Test
    fun sharedChipPreservesTheChatListBinaryStateContract() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatFolderChip(
                        state = ToggleableState.On,
                        label = "Work",
                        onClick = {},
                        modifier = Modifier.testTag(BINARY_CHIP_TAG),
                    )
                }
            }
        }

        val config = composeRule.onNodeWithTag(BINARY_CHIP_TAG).fetchSemanticsNode().config
        assertEquals(true, config[SemanticsProperties.Selected])
        assertFalse(config.contains(SemanticsProperties.ToggleableState))
    }

    /** Bulk toggling adds missing members and removes only this folder, preserving individual choices outside it. */
    @Test
    fun mixedThenOnTapAddsMissingMembersThenRemovesOnlyFolderMembers() {
        val rows = listOf(folder("work", "Work") to listOf("work-a", "work-b"))
        var selected by mutableStateOf(listOf("outside", "work-a"))
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ForwardFolderChips(
                        folderRows = rows,
                        selected = selected,
                        onSelectionChange = { selected = it },
                    )
                }
            }
        }
        val chip = composeRule.onNodeWithTag(forwardFolderChipTestTag("work"))

        chip.performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("outside", "work-a", "work-b"), selected)
        assertEquals(ToggleableState.On, chipToggleableState("work"))

        chip.performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("outside"), selected)
        assertEquals(ToggleableState.Off, chipToggleableState("work"))
    }

    /** Overflow is horizontal, not an unbounded vertical stack that hides destination rows. */
    @Test
    fun manyLongFoldersStayInOneHorizontallyScrollableControlRow() {
        verifyHorizontalFolderOverflow(largeRtl = false)
    }

    /** Enlarged RTL controls retain full labels for accessibility and at least 48dp touch height. */
    @Test
    fun manyLongFoldersRemainReachableAtLargeRtlWithAccessibleTargets() {
        verifyHorizontalFolderOverflow(largeRtl = true)
    }

    /** Shares the same overflow/target-size assertions across normal and enlarged RTL appearance. */
    private fun verifyHorizontalFolderOverflow(largeRtl: Boolean) {
        val rows =
            (0 until 10).map { index ->
                folder("folder-$index", "A very long folder name number $index") to
                    listOf("group-$index-a", "group-$index-b")
            }
        render(rows = rows, selected = emptyList(), largeRtl = largeRtl)

        val firstBounds =
            composeRule
                .onNodeWithTag(forwardFolderChipTestTag("folder-0"))
                .getUnclippedBoundsInRoot()
        val lastChip = composeRule.onNodeWithTag(forwardFolderChipTestTag("folder-9"))
        val lastBounds = lastChip.getUnclippedBoundsInRoot()
        assertEquals(firstBounds.top.value, lastBounds.top.value, 0.1f)
        val firstNode = composeRule.onNodeWithTag(forwardFolderChipTestTag("folder-0")).fetchSemanticsNode()
        val minimumTargetPixels = with(firstNode.layoutInfo.density) { 48.dp.toPx() }
        assertTrue(
            "interactive folder control touch width is ${firstNode.touchBoundsInRoot.width}px",
            firstNode.touchBoundsInRoot.width >= minimumTargetPixels,
        )
        assertTrue(
            "interactive folder control touch height is ${firstNode.touchBoundsInRoot.height}px",
            firstNode.touchBoundsInRoot.height >= minimumTargetPixels,
        )

        lastChip.performScrollTo().assertIsDisplayed()
        assertChipSemantics("folder-9", "A very long folder name number 9", 2, ToggleableState.Off)
        composeRule.onNodeWithTag(FORWARD_FOLDER_CHIP_ROW_TEST_TAG).assertIsDisplayed()
    }

    /** Checks one merged checkbox node without relying on the truncated visual chip text. */
    private fun assertChipSemantics(
        folderId: String,
        label: String,
        count: Int,
        expectedState: ToggleableState,
    ) {
        val config =
            composeRule
                .onNodeWithTag(forwardFolderChipTestTag(folderId))
                .fetchSemanticsNode()
                .config
        val expectedCount = context.resources.getQuantityString(R.plurals.chat_folder_chat_count, count, count)
        assertEquals(listOf("$label, $expectedCount"), config[SemanticsProperties.ContentDescription])
        assertEquals(expectedState, config[SemanticsProperties.ToggleableState])
        assertEquals(Role.Checkbox, config.getOrNull(SemanticsProperties.Role))
        assertFalse(
            "tri-state controls must not expose a contradictory binary selection",
            config.contains(SemanticsProperties.Selected),
        )
    }

    /** Reads the real tri-state property after the controlled selection has recomposed. */
    private fun chipToggleableState(folderId: String): ToggleableState =
        composeRule
            .onNodeWithTag(forwardFolderChipTestTag(folderId))
            .fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState]

    /** Mounts the production folder-control region using deterministic theme and layout direction. */
    private fun render(
        rows: List<Pair<ChatFolder, List<String>>>,
        selected: List<String>,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = largeRtl, amoled = largeRtl, fontScale = if (largeRtl) 1.6f else 1f) {
                    Surface {
                        ForwardFolderChips(
                            folderRows = rows,
                            selected = selected,
                            onSelectionChange = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Supplies presentation-only folder identity; selection membership is supplied separately by the caller. */
    private fun folder(
        id: String,
        name: String,
    ) = ChatFolder(id = id, name = name, description = "", order = 0, systemKind = null)

    private companion object {
        const val BINARY_CHIP_TAG = "binary-folder-chip"
    }
}
