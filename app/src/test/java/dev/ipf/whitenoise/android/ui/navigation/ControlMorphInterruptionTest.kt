package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFabContent
import dev.ipf.whitenoise.android.ui.common.ControlMorphSurface
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoffHost
import dev.ipf.whitenoise.android.ui.common.controlMorphSurface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production geometry clock and actual relay-warning/FAB admission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class ControlMorphInterruptionTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher(scheduler))
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var oldScale: String? = null

    @Before fun enableMotion() {
        oldScale = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @After fun restoreMotion() {
        rule.mainClock.autoAdvance = true
        Settings.Global.putString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, oldScale)
    }

    @Test fun reversingAnIntermediateFrameKeepsItsBoundsAndCornerShape() = exerciseIntermediateFrame(false)

    @Test fun movingComposerTargetKeepsTheSameFractionBounds() = exerciseIntermediateFrame(true)

    private fun exerciseIntermediateFrame(retarget: Boolean) {
        val state = MeasuredControlHandoff("pen", morphSurface = true, followMovingTarget = retarget)
        var destination by mutableStateOf("pen")
        rule.setContent {
            MaterialTheme {
                MeasuredControlHandoffHost(state, destination, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                                .size(if (destination == "pen") 56.dp else 280.dp, 56.dp)
                                .controlMorphSurface(
                                    state,
                                    destination,
                                    ControlMorphSurface(
                                        Color.Black,
                                        Color.White,
                                        if (destination ==
                                            "pen"
                                        ) {
                                            16f
                                        } else {
                                            24f
                                        },
                                        1f,
                                        decorationAlpha = if (destination == "pen") 1f else 0f,
                                    ),
                                ),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            destination = "editor"
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle {
            assertTrue(state.active)
            val bounds = state.renderedBounds
            val radius = state.renderedRadius
            val penAlpha = state.renderedDecorationAlpha
            if (retarget) {
                val target = requireNotNull(state.targetBounds)
                state.report("editor", Any(), target.translate(Offset(0f, -72f)))
                assertTrue(state.active)
            } else {
                state.prepare("pen", true)
            }
            assertEquals(bounds, state.renderedBounds)
            assertEquals(radius, state.renderedRadius, 0f)
            assertEquals(penAlpha, state.renderedDecorationAlpha, 0f)
            state.finish()
            assertFalse(state.active)
            assertNull(state.renderedBounds)
        }
    }

    @Test fun relayWarningIsNotAPenAndItsLaterReplacementCannotReviveANavigation() {
        val state = MeasuredControlHandoff(ComposerFabEndpoint.NewMessage, morphSurface = true)
        var warning by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalComposerFabHandoff provides state) {
                    ChatsNewMessageFabContent(missingRelays = warning) {}
                }
            }
        }
        rule.runOnIdle {
            assertNull(state.captureOrigin())
            state.prepare(ComposerFabEndpoint.Composer, true)
            warning = false
        }
        rule.runOnIdle {
            assertFalse(state.active)
            assertNull(state.renderedBounds)
        }
    }
}
