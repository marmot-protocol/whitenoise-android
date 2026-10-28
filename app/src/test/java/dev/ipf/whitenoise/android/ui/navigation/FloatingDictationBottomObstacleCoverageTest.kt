package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.settings.SettingsBottomAction
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses a production footer so omitting its registration fails the shell placement contract. */
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
