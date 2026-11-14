package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cards folding back into their anchor cover the live route, so a tap must not reach the controls under them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w400dp-h800dp-mdpi")
class SettingsCardMorphInputTest {
    @get:Rule val rule = createComposeRule()

    private object Anchor : SettingsMorphAnchor

    // One clock scenario compares the real entry and Back input admission boundaries.

    @Test
    @Suppress("LongMethod")
    fun fullScreenBackUsesCloseClockWhileEntryKeepsOpenClock() {
        val morph = SettingsCardMorph(fullScreen = true)
        val page = requireNotNull(morph.page)
        var target by mutableStateOf("parent")
        var forward by mutableStateOf(true)
        var measuredOrigin: Rect? = null
        var measuredHost: Rect? = null
        var beganTarget: String? = null
        var beginAdmitted = false
        var beginActive = false
        var measuredTarget: String? = null
        rule.setContent {
            androidx.compose.material3.MaterialTheme {
                CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
                    val view = LocalView.current
                    val (admitted, host) =
                        rememberSettingsCardMorphHost(
                            morph,
                            target,
                            Unit,
                            motion = true,
                            forward = forward,
                        )
                    // Capture begin separately from destination admission and drawing cancellation.
                    if (beganTarget != target) {
                        beganTarget = target
                        beginAdmitted = admitted
                        beginActive = page.active
                    }
                    val renderedTarget = target
                    val (native, _) = rememberSettingsNativeSurface(target, strokeColor = Color.White)
                    Box(
                        Modifier.fillMaxSize().then(host).onGloballyPositioned {
                            measuredHost =
                                it.settingsMorphBounds(view)
                        },
                    ) {
                        // Distinct native pages must lay out their destination before admission.
                        Box(
                            Modifier
                                .size(
                                    if (renderedTarget == "child") {
                                        240.dp
                                    } else {
                                        120.dp
                                    },
                                ).then(native)
                                .onGloballyPositioned {
                                    measuredOrigin = it.settingsMorphBounds(view)
                                    measuredTarget = renderedTarget
                                },
                        )
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            val host = requireNotNull(measuredHost)
            val native = requireNotNull(page.presentedContours(host, 1f))
            assertEquals("Initial route must report its outlined native group", 1, native.size)
            assertEquals(requireNotNull(measuredOrigin), native.single().bounds)
            assertTrue(
                "Measured origin must lie inside host: origin=$measuredOrigin host=$host",
                host.contains(requireNotNull(measuredOrigin).center),
            )
            assertEquals("parent", measuredTarget)
            // Match the screen coordinates used by the host and native surface, including window insets.
            page.pendingOrigin = requireNotNull(measuredOrigin).also { assertFalse(it.isEmpty) }
            target = "child"
            Snapshot.sendApplyNotifications()
        }
        // One frame commits the route, one admits the measured destination, one starts animateTo.
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle {
            assertEquals("child", beganTarget)
            assertTrue(
                "Entry begin rejected: activeAtBegin=$beginActive host=$measuredHost origin=$measuredOrigin " +
                    "captureInputs=${page.captureInputs?.invoke()}",
                beginAdmitted && beginActive,
            )
            assertEquals("Destination layout must report child before admission", "child", measuredTarget)
            assertTrue(
                "Entry must admit a measured transition; time=${morph.time()} native=${page.presentedContours(
                    requireNotNull(measuredHost),
                    1f,
                )}",
                page.active && morph.blocksPointerInput,
            )
        }
        rule.mainClock.advanceTimeBy(240)
        rule.runOnIdle { assertTrue("280 ms entry must still own input at 240 ms", morph.blocksPointerInput) }
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle {
            forward = false
            target = "parent"
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertTrue("Back must admit a return transition", page.active && morph.blocksPointerInput) }
        rule.mainClock.advanceTimeBy(240)
        rule.runOnIdle {
            assertFalse(
                "230 ms Back must release input by the next 16 ms frame",
                morph.blocksPointerInput,
            )
        }
    }

    @Test fun foldingCardsHoldBackTapsUntilTheyAreGone() {
        val morph = SettingsCardMorph().apply { anchor = Anchor }
        var routeOpen by mutableStateOf(true)
        var taps = 0
        rule.setContent {
            CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
                val colors = SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified)
                Column(Modifier.fillMaxSize().settingsCardMorphHost(morph, colors)) {
                    if (routeOpen) {
                        repeat(2) { Box(Modifier.settingsCardMorph().size(120.dp)) }
                    } else {
                        Box(
                            Modifier
                                .settingsCardMorph(Anchor)
                                .size(120.dp)
                                .testTag("send")
                                .clickable { taps++ },
                        )
                    }
                }
            }
        }
        rule.runOnIdle {
            morph.time = { 0.5f }
            morph.begin(animate = true)
            routeOpen = false
        }
        rule.onNodeWithTag("send").performClick()
        rule.runOnIdle { assertEquals(0, taps) }

        rule.runOnIdle { morph.time = { 1f } }
        rule.onNodeWithTag("send").performClick()
        rule.runOnIdle { assertEquals(1, taps) }
    }
}
