package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Interaction guards for the native Material settings rows and choices. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsComponentsSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The label and switch area activate one row action, with one accessible switch owner. */
    @Test
    fun entireSwitchRowChangesCallerStateExactlyOncePerActivation() {
        val checked = mutableStateOf(false)
        val requests = mutableListOf<Boolean>()
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsGroup {
                    row("theme") { context ->
                        SettingsSwitch(
                            context = context,
                            title = "Use dark theme",
                            checked = checked.value,
                            onCheckedChange = {
                                requests += it
                                checked.value = it
                            },
                            modifier = Modifier.testTag(SWITCH),
                        )
                    }
                }
            }
        }

        val row = composeRule.onNodeWithTag(SWITCH)
        composeRule.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(1)
        row
            .assertIsOff()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        composeRule.onNodeWithText("Use dark theme").performClick()
        row.assertIsOn()
        composeRule.runOnIdle { assertEquals(listOf(true), requests) }
        row.performTouchInput { click(centerRight - Offset(24.dp.toPx(), 0f)) }
        row.assertIsOff()
        composeRule.runOnIdle { assertEquals(listOf(true, false), requests) }
    }

    /** Loading and disabled controls neither mutate preferences nor present an enabled switch. */
    @Test
    fun busyAndDisabledRowsRejectPointerActivation() {
        var requests = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsGroup {
                    row("disabled") { context ->
                        SettingsSwitch(
                            context = context,
                            title = "Disabled option",
                            checked = false,
                            onCheckedChange = { requests++ },
                            enabled = false,
                            modifier = Modifier.testTag("disabled"),
                        )
                    }
                    row("busy") { context ->
                        SettingsSwitch(
                            context = context,
                            title = "Saving option",
                            checked = true,
                            onCheckedChange = { requests++ },
                            busy = true,
                            modifier = Modifier.testTag("busy"),
                        )
                    }
                }
            }
        }

        listOf("disabled", "busy").forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsNotEnabled().performTouchInput { click() }
        }
        composeRule.runOnIdle { assertEquals(0, requests) }
    }

    /** Link activation remains caller-owned and is blocked while its operation is busy. */
    @Test
    fun linkActivationIsBlockedDuringBusyState() {
        val busy = mutableStateOf(false)
        var activations = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsGroup {
                    row("appearance") { context ->
                        SettingsLink(
                            context = context,
                            title = "Appearance",
                            onClick = { activations++ },
                            busy = busy.value,
                            modifier = Modifier.testTag(LINK),
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(LINK)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, activations)
            busy.value = true
        }
        composeRule.onNodeWithTag(LINK).assertIsNotEnabled().performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, activations) }
    }

    /** Removing an earlier visible entry retains the surviving row's keyed composition identity. */
    @Test
    fun hidingEarlierRowPreservesFollowingContentIdentity() {
        val showEarlier = mutableStateOf(true)
        var nextIdentity = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsGroup {
                    if (showEarlier.value) {
                        row("earlier") { Text("Earlier option") }
                    }
                    row("survivor") {
                        val identity = remember { ++nextIdentity }
                        Text("Row identity $identity")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Row identity 1").assertExists()
        composeRule.runOnIdle { showEarlier.value = false }
        composeRule.onNodeWithText("Earlier option").assertDoesNotExist()
        composeRule.onNodeWithText("Row identity 1").assertExists()
        composeRule.runOnIdle { assertEquals(1, nextIdentity) }
    }

    /** A row that loses both neighbors must become a closed singleton, not keep its middle-row seams. */
    @Test
    fun collapsingToOneRowRecomputesConnectedShape() {
        val expanded = mutableStateOf(true)
        var survivorShape: ConnectedRowShape? = null
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                SettingsGroup {
                    if (expanded.value) {
                        row("before") { Text("Before") }
                    }
                    row("survivor") { context ->
                        survivorShape = context.shapes.shape as ConnectedRowShape
                        Text("Survivor")
                    }
                    if (expanded.value) {
                        row("after") { Text("After") }
                    }
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(false, survivorShape?.first)
            assertEquals(false, survivorShape?.last)
            expanded.value = false
        }
        composeRule.runOnIdle {
            assertEquals(true, survivorShape?.first)
            assertEquals(true, survivorShape?.last)
        }
    }

    /** A grouped choice row selects on tap, exposes one radio target, and ignores taps while disabled. */
    @Test
    fun groupedChoiceRowSelectsOnceAndRejectsDisabledTaps() {
        val selected = mutableStateOf("system")
        var disabledTaps = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsGroup {
                    listOf("system", "light").forEach { mode ->
                        row(mode) { context ->
                            SettingsChoice(
                                context = context,
                                title = mode,
                                selected = selected.value == mode,
                                onClick = { selected.value = mode },
                                modifier = Modifier.testTag(mode),
                            )
                        }
                    }
                    row("locked") { context ->
                        SettingsChoice(
                            context = context,
                            title = "locked",
                            selected = false,
                            onClick = { disabledTaps++ },
                            enabled = false,
                            modifier = Modifier.testTag("locked"),
                        )
                    }
                }
            }
        }

        composeRule.onAllNodes(isSelectable(), useUnmergedTree = true).assertCountEquals(3)
        composeRule.onNodeWithTag("system").assertIsSelected()
        composeRule
            .onNodeWithTag("light")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
        composeRule.runOnIdle { assertEquals("light", selected.value) }
        composeRule.onNodeWithTag("light").assertIsSelected()
        composeRule.onNodeWithTag("locked").assertIsNotEnabled().performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, disabledTaps) }
    }

    private companion object {
        const val SWITCH = "settings-switch"
        const val LINK = "settings-link"
    }
}
