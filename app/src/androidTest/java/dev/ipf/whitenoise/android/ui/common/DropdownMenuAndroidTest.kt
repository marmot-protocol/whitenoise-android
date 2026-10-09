package dev.ipf.whitenoise.android.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native popup paint and actions after opening, including the production menu's viewport modifier. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class DropdownMenuAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun configureWindow() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
    }

    @Test
    fun fittingAppMenuShowsItsItemsAndDismissesAfterSelection() = exerciseMenu(rows = 3, appMenu = true)

    @Test
    fun overflowingAppMenuKeepsVisibleItemsAndReachesItsLastAction() = exerciseMenu(rows = 30, appMenu = true)

    @Test
    fun fittingMaterialControlShowsItsItemsAndDismissesAfterSelection() = exerciseMenu(rows = 3, appMenu = false)

    private fun exerciseMenu(
        rows: Int,
        appMenu: Boolean,
    ) {
        val expanded = mutableStateOf(false)
        var selected = -1
        render(rows, appMenu, expanded) { selected = it }
        rule.onNodeWithText("Open menu").performClick()
        rule.onNodeWithText("Choice 0").assertIsDisplayed()
        val pixels = rule.onNodeWithTag("${ITEM_PREFIX}0").captureToImage().toPixelMap()
        val inset = with(rule.density) { 8.dp.roundToPx() }
        val painted = pixels[inset, pixels.height / 2]
        assertTrue("first action must be painted cyan: $painted", painted.green > 0.95f && painted.blue > 0.95f)
        assertTrue("first action must remain opaque cyan: $painted", painted.red < 0.05f)
        val last = rule.onNodeWithText("Choice ${rows - 1}")
        last.performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(rows - 1, selected) }
        rule.onNodeWithTag(MENU).assertDoesNotExist()
    }

    private fun render(
        rows: Int,
        appMenu: Boolean,
        expanded: MutableState<Boolean>,
        onSelected: (Int) -> Unit,
    ) {
        rule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Button(onClick = { expanded.value = true }) { Text("Open menu") }
                    if (appMenu) {
                        WhiteNoiseDropdownMenu(
                            expanded = expanded.value,
                            onDismissRequest = { expanded.value = false },
                            items =
                                List(rows) { index ->
                                    WhiteNoiseMenuItem(
                                        label = "Choice $index",
                                        onClick = { onSelected(index) },
                                        modifier = Modifier.background(Color.Cyan).testTag("$ITEM_PREFIX$index"),
                                    )
                                },
                            modifier = Modifier.testTag(MENU),
                        )
                    } else {
                        DropdownMenu(
                            expanded = expanded.value,
                            onDismissRequest = { expanded.value = false },
                            modifier = Modifier.testTag(MENU),
                        ) {
                            repeat(rows) { index ->
                                DropdownMenuItem(
                                    text = { Text("Choice $index") },
                                    onClick = {
                                        onSelected(index)
                                        expanded.value = false
                                    },
                                    modifier = Modifier.background(Color.Cyan).testTag("$ITEM_PREFIX$index"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MENU = "dropdown.native.menu"
        const val ITEM_PREFIX = "dropdown.native.item."
    }
}
