package dev.ipf.whitenoise.android.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Popup windows survive a dismissal long enough to draw their exit, including interrupted entrance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class KeyboardSafePopupMotionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun dismissDuringEntranceRetainsContentUntilExitCompletes() {
        val expanded = mutableStateOf(false)
        var contentMounted = false
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                KeyboardSafePopup(expanded.value, { expanded.value = false }, BottomAnchoredPopupPositionProvider) {
                    DisposableEffect(Unit) {
                        contentMounted = true
                        onDispose { contentMounted = false }
                    }
                    Box(Modifier.size(120.dp).testTag("popup"))
                }
            }
        }
        rule.runOnIdle {
            expanded.value = true
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag("popup", useUnmergedTree = true).assertExists()
        rule.runOnIdle {
            expanded.value = false
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        // Exit intentionally clears accessibility descendants; composition must still retain the window body.
        rule.runOnIdle { assertEquals(true, contentMounted) }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertEquals(false, contentMounted) }
        rule.onNodeWithTag("popup", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun semanticScrimDismissalUsesTheSameExitBeforeDisposingItsConditionalCaller() {
        val shown = mutableStateOf(true)
        var dismissals = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                if (shown.value) {
                    KeyboardSafePopup(
                        expanded = true,
                        onDismissRequest = {
                            dismissals++
                            shown.value = false
                        },
                        popupPositionProvider = BottomAnchoredPopupPositionProvider,
                        scrimModifier =
                            Modifier.testTag("dismiss").semantics {
                                onClick {
                                    dismissals++
                                    shown.value = false
                                    true
                                }
                            },
                    ) { Box(Modifier.size(120.dp).testTag("popup")) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("dismiss").performClick()
        rule.runOnIdle { assertEquals(0, dismissals) }
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag("popup", useUnmergedTree = true).assertExists()
        rule.mainClock.advanceTimeBy(320)
        rule.onNodeWithTag("popup", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun retainedScrimUsesTheLatestDismissCallback() {
        val version = mutableStateOf(1)
        val dismissed = mutableListOf<Int>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val currentVersion = version.value
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                KeyboardSafePopup(
                    expanded = true,
                    onDismissRequest = { dismissed.add(currentVersion) },
                    popupPositionProvider = BottomAnchoredPopupPositionProvider,
                    scrimModifier = Modifier.testTag("latest-scrim"),
                ) { Box(Modifier.size(120.dp)) }
            }
        }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle {
            version.value = 2
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("latest-scrim").performTouchInput { click() }
        rule.runOnIdle { assertEquals(emptyList<Int>(), dismissed) }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertEquals(listOf(2), dismissed) }
    }

    @Test fun reducedMotionDismissesWithoutAnAnimatedWindowLifetime() {
        val expanded = mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides false) {
                KeyboardSafePopup(expanded.value, { expanded.value = false }, BottomAnchoredPopupPositionProvider) {
                    Box(Modifier.size(120.dp).testTag("popup"))
                }
            }
        }
        rule.onNodeWithTag("popup", useUnmergedTree = true).assertExists()
        rule.runOnIdle {
            expanded.value = false
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        rule.onNodeWithTag("popup", useUnmergedTree = true).assertDoesNotExist()
    }
}
