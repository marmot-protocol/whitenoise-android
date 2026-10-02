package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1100dp-mdpi")
class SmartFolderEditorTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun absenceAndIgnoreAreDifferentDraftEdits() {
        val root = render(SmartFolderFilter.Group(children = listOf(SmartFolderFilter.Condition(FolderField.UNREAD))))
        composeRule.onNodeWithTag("folder.condition.0").performClick()
        composeRule.onNodeWithTag("folder.mode").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_none)).performClick()
        composeRule.onNodeWithTag("folder.conditionDone").performClick()
        assertEquals(FolderMode.NONE, (root.value.children.single() as SmartFolderFilter.Condition).mode)
        composeRule.onNodeWithTag("folder.condition.0").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_ignore)).performClick()
        assertTrue(root.value.children.isEmpty())
    }

    @Test fun matchAnyAndNestedNotRemainIndependent() {
        val child = SmartFolderFilter.Group(children = listOf(SmartFolderFilter.Condition(FolderField.MENTIONS)))
        val root = render(SmartFolderFilter.Group(children = listOf(child)))
        composeRule.onNodeWithTag("folder.match.").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_any)).performClick()
        assertFalse(root.value.all)
        composeRule
            .onNodeWithText(
                context.getString(
                    R.string.smart_folder_group_summary,
                    context.getString(R.string.smart_folder_all),
                    1,
                ),
            ).performClick()
        composeRule.onNodeWithTag("folder.not.0").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_not_hint)).performClick()
        assertTrue((root.value.children.single() as SmartFolderFilter.Group).not)
        assertFalse(root.value.not)
    }

    @Test fun participantConditionRequiresSelectionBeforeSaveAndPickerPreservesChoices() {
        val root = render(SmartFolderFilter.Group())
        composeRule.onNodeWithTag("folder.add.").performClick()
        composeRule.onNodeWithTag("folder.property").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_participants)).performClick()
        composeRule.onNodeWithTag("folder.conditionDone").assertIsNotEnabled()
        composeRule.onNodeWithTag("folder.choosePeople").performClick()
        composeRule.onNodeWithText("Agent").performClick()
        composeRule.onNodeWithText(context.getString(R.string.done)).performClick()
        composeRule.onNodeWithTag("folder.conditionDone").assertIsEnabled().performClick()
        assertEquals(
            setOf("a".repeat(64)),
            (root.value.children.single() as SmartFolderFilter.Condition).values,
        )
    }

    @Test fun cancelDoesNotChangeTheRuleAndTreeEditsDoNotMutateSiblingGroups() {
        val initial = defaultSmartFolder()
        val root = render(initial)
        composeRule.onNodeWithTag("folder.condition.0").performClick()
        composeRule.onNodeWithTag("folder.mode").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_present)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(initial, root.value)
        val child = SmartFolderFilter.Group(children = listOf(SmartFolderFilter.Condition(FolderField.UNREAD)))
        val tree = SmartFolderFilter.Group(children = listOf(child, child))
        val changed = tree.updateAt(listOf(0, 0), SmartFolderFilter.Condition(FolderField.MENTIONS))
        assertEquals(child, changed.children[1])
        assertNotEquals(child, changed.children[0])
    }

    private fun render(initial: SmartFolderFilter.Group): MutableState<SmartFolderFilter.Group> {
        val state = mutableStateOf(initial)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    SmartFolderEditor(
                        state.value,
                        listOf(
                            WhiteNoisePickerItem(
                                "a".repeat(64),
                                "Agent",
                                "agent",
                            ),
                        ),
                        resolveKey = {
                            null
                        },
                        onChange = {
                            state.value =
                                it
                        },
                    )
                }
            }
        }
        return state
    }
}
