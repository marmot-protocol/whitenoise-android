package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Selection and dismissal contracts for the choice dialog and the grouped choice row. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChoiceDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Tapping a choice reports that value exactly once and Cancel only dismisses. */
    @Test
    fun dialogReportsSelectionOnceAndCancelOnlyDismisses() {
        val selections = mutableListOf<String>()
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChoiceDialog(
                    title = "Font size",
                    values = listOf("Small", "Default", "Large"),
                    selected = "Default",
                    label = { it },
                    onDismiss = { dismissals++ },
                    onSelect = { selections += it },
                )
            }
        }

        composeRule.onAllNodes(isSelectable()).assertCountEquals(3)
        composeRule.onNodeWithText("Default").assertIsSelected()
        composeRule.onNodeWithText("Large").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("Large"), selections)
            assertEquals(1, dismissals)
        }
    }

    /** Each dialog row exposes one radio target; the decorative radio button adds no second node. */
    @Test
    fun dialogRowsCarryOneRadioRoleEach() {
        composeRule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseDialogChoiceRow(
                    title = "Deutsch",
                    selected = false,
                    onClick = {},
                    modifier = Modifier.testTag(ROW),
                )
            }
        }

        composeRule
            .onNodeWithTag(ROW)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onAllNodes(isSelectable()).assertCountEquals(1)
    }

    private companion object {
        const val ROW = "dialog-choice"
    }
}
