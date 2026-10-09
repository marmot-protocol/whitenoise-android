package dev.ipf.whitenoise.android.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Natural window frames and Android accessibility, without a Compose test clock or idling resource. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ClocklessDropdownMenuAndroidTest {
    @get:Rule val scenario = ActivityScenarioRule(ComponentActivity::class.java)

    @Test
    fun fittingAppMenuPaintsAndSelectsWithNaturalFrames() = exerciseMenu(appMenu = true)

    @Test
    fun fittingMaterialControlPaintsAndSelectsWithNaturalFrames() = exerciseMenu(appMenu = false)

    private fun exerciseMenu(appMenu: Boolean) {
        val expanded = mutableStateOf(false)
        val selected = mutableStateOf(-1)
        val density = renderMenu(appMenu, expanded, selected)
        DropdownMenuWindowProbe.clickText("Open menu")
        DropdownMenuWindowProbe.assertFirstRowPainted(density)
        DropdownMenuWindowProbe.clickText("Choice 2")
        DropdownMenuWindowProbe.waitForText("Open menu")
        scenario.scenario.onActivity { assertEquals(2, selected.value) }
        assertTrue(
            "selection must dismiss the actual popup",
            DropdownMenuWindowProbe.visibleText("Choice 2") == null,
        )
    }

    private fun renderMenu(
        appMenu: Boolean,
        expanded: MutableState<Boolean>,
        selected: MutableState<Int>,
    ): Float {
        var density = 0f
        scenario.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            density = activity.resources.displayMetrics.density
            activity.setContent {
                WhiteNoiseTheme {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Button(onClick = { expanded.value = true }) { Text("Open menu") }
                        if (appMenu) {
                            WhiteNoiseDropdownMenu(
                                expanded = expanded.value,
                                onDismissRequest = { expanded.value = false },
                                items =
                                    List(3) { index ->
                                        WhiteNoiseMenuItem(
                                            "Choice $index",
                                            onClick = { selected.value = index },
                                            modifier = Modifier.background(Color.Cyan),
                                        )
                                    },
                            )
                        } else {
                            DropdownMenu(expanded.value, onDismissRequest = { expanded.value = false }) {
                                repeat(3) { index ->
                                    DropdownMenuItem(
                                        text = { Text("Choice $index") },
                                        onClick = {
                                            selected.value = index
                                            expanded.value = false
                                        },
                                        modifier = Modifier.background(Color.Cyan),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return density
    }
}
