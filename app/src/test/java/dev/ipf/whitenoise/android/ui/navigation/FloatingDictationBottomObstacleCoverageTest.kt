package dev.ipf.whitenoise.android.ui.navigation

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.settings.SettingsBottomAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses a production footer so omitting its registration fails the shell placement contract. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class FloatingDictationBottomObstacleCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A tall/wrapped footer keeps its actual actions below the real measured capsule. */
    @Test
    fun productionFooterRegistersItsWholeAreaAndDisposesIt() {
        val anchor = FloatingDictationAnchor()
        val visible = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFloatingDictationAnchor provides anchor) {
                    Box(Modifier.size(240.dp, 420.dp)) {
                        FloatingDictationLayout(
                            anchor = anchor,
                            content = {
                                Box(Modifier.fillMaxSize()) {
                                    if (visible.value) {
                                        SettingsBottomAction(Modifier.align(Alignment.BottomCenter)) {
                                            repeat(3) { index -> Button(onClick = {}) { Text("Action $index") } }
                                        }
                                    }
                                }
                            },
                            control = { Box(Modifier.size(168.dp, 48.dp).testTag("capsule")) },
                        )
                    }
                }
            }
        }
        val capsule = composeRule.onNodeWithTag("capsule").fetchSemanticsNode().boundsInRoot
        val firstAction = composeRule.onNodeWithText("Action 0").fetchSemanticsNode().boundsInRoot
        assertTrue(capsule.bottom <= firstAction.top)
        composeRule.runOnIdle { visible.value = false }
        composeRule.runOnIdle {
            assertNull(anchor.topInWindow)
            assertNull(anchor.scrollingCapsule)
        }
    }

    /** Form actions register their complete painted rail and retire its obstacle when removed. */
    @Test
    fun stickyFormActionBarRegistersItsWholeAreaAndDisposesIt() {
        val anchor = FloatingDictationAnchor()
        val visible = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFloatingDictationAnchor provides anchor) {
                    Box(Modifier.size(240.dp, 420.dp)) {
                        FloatingDictationLayout(
                            anchor = anchor,
                            content = {
                                Box(Modifier.fillMaxSize()) {
                                    if (visible.value) {
                                        StickyFormActionBar(Modifier.align(Alignment.BottomCenter)) {
                                            Button(onClick = {}) { Text("Save") }
                                        }
                                    }
                                }
                            },
                            control = { Box(Modifier.size(168.dp, 48.dp).testTag("capsule")) },
                        )
                    }
                }
            }
        }
        val capsule = composeRule.onNodeWithTag("capsule").fetchSemanticsNode().boundsInRoot
        val save = composeRule.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        assertTrue(capsule.bottom <= save.top)
        composeRule.runOnIdle { visible.value = false }
        composeRule.runOnIdle {
            assertNull(anchor.topInWindow)
            assertNull(anchor.scrollingCapsule)
        }
    }

    /** The form rail keeps its painted background behind system insets while dictation is present. */
    @Test
    fun formRailPaintIncludesTheConsumedNavigationInset() = assertInsetPaint(settings = false)

    /** Settings uses the same inside-Surface inset contract as the form rail. */
    @Test
    fun settingsRailPaintIncludesTheConsumedNavigationInset() = assertInsetPaint(settings = true)

    /** A red underlay exposes inset padding outside the green production Surface without a golden waiver. */
    private fun assertInsetPaint(settings: Boolean) {
        lateinit var view: View
        val anchor = FloatingDictationAnchor()
        composeRule.setContent {
            view = LocalView.current
            MaterialTheme(colorScheme = lightColorScheme(surface = Color.Green, surfaceTint = Color.Green)) {
                CompositionLocalProvider(LocalFloatingDictationAnchor provides anchor) {
                    Box(Modifier.size(240.dp, 420.dp).background(Color.Red).testTag("paint")) {
                        FloatingDictationLayout(
                            anchor = anchor,
                            content = {
                                Box(Modifier.fillMaxSize()) {
                                    if (settings) {
                                        SettingsBottomAction(Modifier.align(Alignment.BottomCenter)) {
                                            Button(onClick = {}) { Text("Save") }
                                        }
                                    } else {
                                        StickyFormActionBar(Modifier.align(Alignment.BottomCenter)) {
                                            Button(onClick = {}) { Text("Save") }
                                        }
                                    }
                                }
                            },
                            control = { Box(Modifier.size(168.dp, 48.dp).testTag("capsule")) },
                        )
                    }
                }
            }
        }
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        composeRule.waitForIdle()
        val root = composeRule.onNodeWithTag("paint")
        val save = composeRule.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        assertTrue(save.bottom <= root.fetchSemanticsNode().boundsInRoot.bottom - 48)
        val pixels = root.captureToImage().toPixelMap()
        val insetPixel = pixels[pixels.width / 2, pixels.height - 10]
        assertTrue(
            "the system-bar inset must retain the footer paint",
            insetPixel.green > 0.9f && insetPixel.red < 0.1f,
        )
    }

    /** The shell-edge capsule stays painted and reachable when adaptive actions occupy a narrower centered pane. */
    @Test
    @Config(qualifiers = "en-w1200dp-h780dp-mdpi")
    fun wideShortSettingsFooterKeepsTheCapsuleInsideItsFullWidthViewport() {
        val anchor = FloatingDictationAnchor()
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFloatingDictationAnchor provides anchor) {
                    Box(Modifier.size(1200.dp, 100.dp).testTag("wide.root")) {
                        FloatingDictationLayout(
                            anchor = anchor,
                            content = {
                                Box(Modifier.fillMaxSize()) {
                                    SettingsBottomAction(Modifier.align(Alignment.BottomCenter)) {
                                        repeat(3) { index ->
                                            Button(onClick = {}, modifier = Modifier.height(48.dp)) {
                                                Text("Wide action $index")
                                            }
                                        }
                                    }
                                }
                            },
                            control = {
                                Box(Modifier.size(168.dp, 48.dp).background(Color.Green).testTag("wide.capsule"))
                            },
                        )
                    }
                }
            }
        }
        val root = composeRule.onNodeWithTag("wide.root")
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val capsule =
            composeRule
                .onNodeWithTag("wide.capsule")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        composeRule.runOnIdle {
            assertEquals(rootBounds.width, requireNotNull(anchor.scrollingCapsule).second.width, 0.5f)
        }
        val pixels = root.captureToImage().toPixelMap()
        val painted =
            pixels[(capsule.center.x - rootBounds.left).toInt(), (capsule.center.y - rootBounds.top).toInt()]
        assertTrue(
            "the capsule must be painted outside the adaptive action pane",
            painted.green > 0.9f && painted.red < 0.1f,
        )
        composeRule.onNodeWithText("Wide action 2").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(requireNotNull(anchor.scrollingState).value > 0) }
    }

    /** Short windows scroll full-size controls together instead of giving actions a zero-height viewport. */
    @Test
    fun shortWindowSharesScrollOwnershipAndKeepsLastActionReachable() {
        val anchor = FloatingDictationAnchor()
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFloatingDictationAnchor provides anchor) {
                    Box(Modifier.size(240.dp, 100.dp)) {
                        FloatingDictationLayout(
                            anchor = anchor,
                            content = {
                                Box(Modifier.fillMaxSize()) {
                                    SettingsBottomAction(Modifier.align(Alignment.BottomCenter)) {
                                        repeat(3) { index ->
                                            Button(onClick = {}, modifier = Modifier.height(48.dp)) {
                                                Text("Action $index")
                                            }
                                        }
                                    }
                                }
                            },
                            control = { Box(Modifier.size(168.dp, 48.dp).testTag("capsule")) },
                        )
                    }
                }
            }
        }
        composeRule.runOnIdle { assertTrue(anchor.scrollingState != null) }
        composeRule.onNodeWithText("Action 2").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(requireNotNull(anchor.scrollingState).value > 0) }
    }
}
