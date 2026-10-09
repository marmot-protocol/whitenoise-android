package dev.ipf.whitenoise.android.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
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

    @Test
    fun overflowingAppMenuPaintsAndSelectsWithNaturalFrames() = exerciseMenu(appMenu = true, rows = 30)

    @Test
    fun overflowingMaterialControlPaintsAndSelectsWithNaturalFrames() = exerciseMenu(appMenu = false, rows = 30)

    @Test
    fun actionSizedAppAnchorPaintsAndSelectsWithNaturalFrames() = exerciseMenu(appMenu = true, actionSizedAnchor = true)

    @Test
    fun actionSizedMaterialAnchorPaintsAndSelectsWithNaturalFrames() = exerciseMenu(
        appMenu = false,
        actionSizedAnchor = true,
    )

    @Test
    fun ordinaryLayerMaterialAnchorPaintsAndSelectsWithNaturalFrames() =
        exerciseMenu(appMenu = false, actionSizedAnchor = true, controlLayer = ControlLayer.PLAIN)

    @Test
    fun shadowOutsetMaterialAnchorPaintsAndSelectsWithNaturalFrames() =
        exerciseMenu(appMenu = false, actionSizedAnchor = true, controlLayer = ControlLayer.SHADOW_OUTSETS)

    @Test
    fun offscreenMaterialAnchorPaintsAndSelectsWithNaturalFrames() = exerciseMenu(
        appMenu = false,
        actionSizedAnchor = true,
        controlLayer = ControlLayer.OFFSCREEN,
    )

    @Test
    fun cachedDrawMaterialAnchorPaintsAndSelectsWithNaturalFrames() = exerciseMenu(
        appMenu = false,
        actionSizedAnchor = true,
        controlLayer = ControlLayer.CACHED_DRAW,
    )

    @Test
    fun zeroFadeMaterialAnchorPaintsAndSelectsWithNaturalFrames() = exerciseMenu(
        appMenu = false,
        actionSizedAnchor = true,
        controlLayer = ControlLayer.ZERO_FADE,
    )

    @Test
    fun fixedFadeMaterialAnchorPaintsAndSelectsWithNaturalFrames() = exerciseMenu(
        appMenu = false,
        actionSizedAnchor = true,
        controlLayer = ControlLayer.FIXED_FADE,
    )

    private fun exerciseMenu(
        appMenu: Boolean,
        rows: Int = 3,
        actionSizedAnchor: Boolean = false,
        controlLayer: ControlLayer = ControlLayer.NONE,
    ) {
        val expanded = mutableStateOf(false)
        val selected = mutableStateOf(-1)
        val configuration = MenuConfiguration(appMenu, rows, actionSizedAnchor, controlLayer)
        val density = renderMenu(configuration, expanded, selected)
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
        configuration: MenuConfiguration,
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
                        Box(if (configuration.actionSizedAnchor) Modifier else Modifier.fillMaxSize()) {
                            Button(onClick = { expanded.value = true }) { Text("Open menu") }
                            if (configuration.appMenu) {
                                WhiteNoiseDropdownMenu(
                                    expanded = expanded.value,
                                    onDismissRequest = { expanded.value = false },
                                    items =
                                        List(configuration.rows) { index ->
                                            WhiteNoiseMenuItem(
                                                "Choice $index",
                                                onClick = { selected.value = index },
                                                modifier = Modifier.background(Color.Cyan),
                                            )
                                        },
                                )
                            } else {
                                MaterialControl(configuration, expanded, selected)
                            }
                        }
                    }
                }
            }
        }
        return density
    }

    @Composable
    private fun MaterialControl(
        configuration: MenuConfiguration,
        expanded: MutableState<Boolean>,
        selected: MutableState<Int>,
    ) {
        DropdownMenu(
            expanded = expanded.value,
            onDismissRequest = { expanded.value = false },
            scrollState = rememberScrollState(),
            modifier = controlModifier(configuration.controlLayer),
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            border = amoledOutlineBorder(),
        ) {
            repeat(configuration.rows) { index ->
                DropdownMenuItem(
                    text = { Text("Choice $index") },
                    onClick = {
                        selected.value = index
                        expanded.value = false
                    },
                    modifier = Modifier.background(Color.Cyan),
                    colors =
                        MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.onSurface,
                        ),
                )
            }
        }
    }

    private fun controlModifier(layer: ControlLayer): Modifier =
        when (layer) {
            ControlLayer.NONE -> Modifier
            ControlLayer.PLAIN -> Modifier.graphicsLayer()
            ControlLayer.SHADOW_OUTSETS -> Modifier.graphicsLayer {
                outsets = LayerOutsets(left = 30.dp, right = 30.dp)
            }
            ControlLayer.OFFSCREEN -> Modifier.graphicsLayer {
                outsets = LayerOutsets(left = 30.dp, right = 30.dp)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            ControlLayer.CACHED_DRAW ->
                Modifier
                    .graphicsLayer {
                        outsets = LayerOutsets(left = 30.dp, right = 30.dp)
                    }.drawWithCache { onDrawWithContent { drawContent() } }
            ControlLayer.ZERO_FADE -> Modifier.verticalEdgeFade(0.dp, 0.dp)
            ControlLayer.FIXED_FADE -> Modifier.verticalEdgeFade(0.dp, 28.dp)
        }

    private enum class ControlLayer {
        NONE,
        PLAIN,
        SHADOW_OUTSETS,
        OFFSCREEN,
        CACHED_DRAW,
        ZERO_FADE,
        FIXED_FADE,
    }

    private data class MenuConfiguration(
        val appMenu: Boolean,
        val rows: Int,
        val actionSizedAnchor: Boolean,
        val controlLayer: ControlLayer,
    )
}
