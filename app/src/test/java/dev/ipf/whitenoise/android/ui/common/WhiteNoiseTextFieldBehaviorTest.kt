package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Editing contracts the custom field decorator must preserve for form consumers. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhiteNoiseTextFieldBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** User edits reach the caller's state and still obey its input transformation. */
    @Test
    fun callerStateAndInputLimitArePreserved() {
        val state = TextFieldState()
        composeRule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseTextField(
                    state = state,
                    modifier = Modifier.testTag(FIELD),
                    inputTransformation = InputTransformation.maxLength(5),
                )
            }
        }

        val field = composeRule.onNodeWithTag(FIELD)
        field.assertHeightIsAtLeast(56.dp)
        field.performTextInput("hello")
        field.performTextInput("!")
        composeRule.runOnIdle { assertEquals("hello", state.text.toString()) }
    }

    /** Read-only values remain readable while exposing no text mutation action. */
    @Test
    fun readOnlyFieldRetainsTextWithoutEditingAction() {
        composeRule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseTextField(
                    state = TextFieldState("Read-only account value"),
                    modifier = Modifier.testTag(FIELD),
                    readOnly = true,
                )
            }
        }

        composeRule
            .onNodeWithTag(FIELD)
            .assertTextContains("Read-only account value")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
    }

    /** Disabled forms announce the disabled state and cannot offer an editing action. */
    @Test
    fun disabledFieldIsNotEditable() {
        composeRule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseTextField(
                    state = TextFieldState("Pending save"),
                    modifier = Modifier.testTag(FIELD),
                    enabled = false,
                )
            }
        }

        composeRule
            .onNodeWithTag(FIELD)
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
    }

    /** Correcting validation removes the prior localized error from accessibility semantics. */
    @Test
    fun errorAnnouncementTracksCallerValidation() {
        val error = mutableStateOf<String?>("Enter a secure relay address")
        val state = TextFieldState("http://relay.example")
        composeRule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseTextField(
                    state = state,
                    modifier = Modifier.testTag(FIELD),
                    label = { Text("Relay") },
                    errorMessage = error.value,
                )
            }
        }

        composeRule.onNodeWithTag(FIELD).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, "Enter a secure relay address"),
        )
        composeRule.runOnIdle { error.value = null }
        composeRule
            .onNodeWithTag(FIELD)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    /** A single IME submission invokes the consumer's action exactly once without editing text. */
    @Test
    fun imeActionRemainsCallerOwned() {
        var submissions = 0
        val state = TextFieldState("Alice")
        composeRule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseTextField(
                    state = state,
                    modifier = Modifier.testTag(FIELD),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { submissions++ },
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
            }
        }

        composeRule.onNodeWithTag(FIELD).performImeAction()
        composeRule.runOnIdle {
            assertEquals(1, submissions)
            assertEquals("Alice", state.text.toString())
        }
    }

    private companion object {
        const val FIELD = "white-noise-text-field"
    }
}
