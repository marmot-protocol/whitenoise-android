package dev.ipf.whitenoise.android.ui.search

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class GlobalSearchContentFilterChipsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allTypedContentKindsAreVisibleAndToggleable() {
        var selection by mutableStateOf(GlobalSearchContentFilterSelection.EMPTY)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    GlobalSearchContentFilterChips(
                        selection = selection,
                        onSelectionChange = { selection = it },
                    )
                }
            }
        }

        GlobalSearchContentKind.entries.forEach { kind ->
            composeRule
                .onNodeWithTag(globalSearchContentChipTag(kind))
                .performScrollTo()
                .assertIsDisplayed()
        }

        composeRule
            .onNodeWithTag(globalSearchContentChipTag(GlobalSearchContentKind.TEXT), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle {
            assertEquals(setOf(GlobalSearchContentKind.TEXT), selection.selectedKinds)
        }

        composeRule
            .onNodeWithTag(globalSearchContentChipTag(GlobalSearchContentKind.LINKS), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle {
            assertEquals(
                setOf(GlobalSearchContentKind.TEXT, GlobalSearchContentKind.LINKS),
                selection.selectedKinds,
            )
        }
    }

    @Test
    fun selectedChipExposesCheckboxRoleAndSelectedSemantics() {
        render(
            selection = GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.IMAGES_VIDEO)),
        )

        val node =
            composeRule
                .onNodeWithTag(globalSearchContentChipTag(GlobalSearchContentKind.IMAGES_VIDEO), useUnmergedTree = true)
                .fetchSemanticsNode()

        assertEquals(Role.Checkbox, node.config.getOrNull(SemanticsProperties.Role))
        assertTrue(node.config.getOrNull(SemanticsProperties.Selected) == true)
        composeRule
            .onNodeWithTag(globalSearchContentChipTag(GlobalSearchContentKind.IMAGES_VIDEO))
            .assertIsSelected()
    }

    @Test
    fun chipsHaveOneKeyboardFocusTargetEachAndActivateWithEnter() {
        var changedSelection: GlobalSearchContentFilterSelection? = null
        lateinit var focusManager: FocusManager
        composeRule.setContent {
            focusManager = LocalFocusManager.current
            WhiteNoiseTheme {
                Surface {
                    GlobalSearchContentFilterChips(
                        selection = GlobalSearchContentFilterSelection.EMPTY,
                        onSelectionChange = { changedSelection = it },
                    )
                }
            }
        }

        val textChip =
            composeRule.onNodeWithTag(
                globalSearchContentChipTag(GlobalSearchContentKind.TEXT),
                useUnmergedTree = true,
            )
        textChip
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }

        composeRule.runOnIdle {
            assertEquals(setOf(GlobalSearchContentKind.TEXT), changedSelection?.selectedKinds)
            assertTrue(focusManager.moveFocus(FocusDirection.Next))
        }
        composeRule
            .onNodeWithTag(
                globalSearchContentChipTag(GlobalSearchContentKind.LINKS),
                useUnmergedTree = true,
            ).assertIsFocused()
        assertEquals(
            GlobalSearchContentKind.entries.size,
            composeRule.onAllNodes(isFocusable(), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun unselectedChipIsNotMarkedSelected() {
        render(selection = GlobalSearchContentFilterSelection.EMPTY)

        val node =
            composeRule
                .onNodeWithTag(
                    globalSearchContentChipTag(GlobalSearchContentKind.FILES_DOCUMENTS),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()

        assertFalse(node.config.getOrNull(SemanticsProperties.Selected) == true)
    }

    private fun render(
        selection: GlobalSearchContentFilterSelection,
        onSelectionChange: (GlobalSearchContentFilterSelection) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    GlobalSearchContentFilterChips(
                        selection = selection,
                        onSelectionChange = onSelectionChange,
                    )
                }
            }
        }
    }
}
