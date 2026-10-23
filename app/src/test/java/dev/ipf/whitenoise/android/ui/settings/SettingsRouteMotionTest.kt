package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsRouteMotionTest {
    @get:Rule val rule = createComposeRule()

    /** Existing Back parents determine logical depth; RTL mirroring is exercised by the mounted transition below. */
    @Test fun nestedDepthAndRtlUseTheExistingBackHierarchy() {
        assertEquals(0, settingsDetailDepth(null))
        assertEquals(1, settingsDetailDepth(SettingsDetail.Appearance))
        assertEquals(2, settingsDetailDepth(SettingsDetail.ChatBubbleColors))
        assertEquals(1, settingsRouteDirection(0, 1))
        assertEquals(-1, settingsRouteDirection(2, 1))
    }

    /** Rapid Back retires the old destination immediately, and locking removes the new protected route. */
    @Test fun rapidBackDisposesOutgoingDestinationImmediatelyAndLockRemovesContent() {
        val destination = mutableStateOf(0)
        val unlocked = mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SettingsRouteMotion(
                target = destination.value,
                depth = destination.value,
                ownerKey = "account",
                contentVisible = unlocked.value,
                animationsEnabled = true,
            ) { Text("Destination $it", Modifier.testTag("destination.$it")) }
        }
        rule.runOnIdle { destination.value = 1 }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("destination.0").assertDoesNotExist()
        rule.onNodeWithTag("destination.1").assertExists()
        rule.runOnIdle { destination.value = 0 }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("destination.1").assertDoesNotExist()
        rule.onNodeWithTag("destination.0").assertExists()
        rule.runOnIdle { unlocked.value = false }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("destination.0").assertDoesNotExist()
    }

    /** The real draw layer approaches its final bounds and live reduced motion settles it immediately. */
    @Test fun destinationMovesThenSettlesAndLiveMotionDisableSnaps() {
        val destination = mutableStateOf(0)
        val motion = mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SettingsRouteMotion(destination.value, destination.value, "account", animationsEnabled = motion.value) {
                Text("Destination $it", Modifier.testTag("moving.destination"))
            }
        }
        rule.runOnIdle { destination.value = 1 }
        rule.mainClock.advanceTimeByFrame()
        val first =
            rule
                .onNodeWithTag("moving.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue("New destination should travel from the logical forward edge", first > 0f)
        rule.mainClock.advanceTimeBy(96)
        val middle =
            rule
                .onNodeWithTag("moving.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue("Midpoint should approach its final position", middle >= 0f && middle < first)
        rule.runOnIdle { motion.value = false }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(
            0f,
            rule
                .onNodeWithTag("moving.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left,
            0.1f,
        )
        rule.mainClock.advanceTimeBy(300)
        assertEquals(
            0f,
            rule
                .onNodeWithTag("moving.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left,
            0.1f,
        )
    }

    /** Changing layout direction mid-flight mirrors the same logical Back movement. */
    @Test fun liveRtlChangeMirrorsTheSameBackTransition() {
        val destination = mutableStateOf(2)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                SettingsRouteMotion(destination.value, destination.value, "account", animationsEnabled = true) {
                    Text("Destination $it", Modifier.fillMaxWidth().testTag("back.destination"))
                }
            }
        }
        rule.runOnIdle { destination.value = 1 }
        rule.mainClock.advanceTimeBy(32)
        assertTrue(
            rule
                .onNodeWithTag("back.destination")
                .getUnclippedBoundsInRoot()
                .left.value < 0f,
        )
        rule.runOnIdle { direction.value = LayoutDirection.Rtl }
        rule.mainClock.advanceTimeByFrame()
        assertTrue(
            rule
                .onNodeWithTag("back.destination")
                .getUnclippedBoundsInRoot()
                .left.value > 0f,
        )
        rule.mainClock.advanceTimeBy(300)
        assertEquals(
            0f,
            rule
                .onNodeWithTag("back.destination")
                .getUnclippedBoundsInRoot()
                .left.value,
            0.1f,
        )
    }

    /** Disabled motion always presents exactly the current owner and destination. */
    @Test fun disabledMotionAndAccountReplacementHaveOneDestination() {
        val owner = mutableStateOf("first")
        val destination = mutableStateOf(0)
        rule.setContent {
            SettingsRouteMotion(destination.value, destination.value, owner.value, animationsEnabled = false) {
                Text(owner.value, Modifier.testTag("${owner.value}.$it"))
            }
        }
        rule.runOnIdle { destination.value = 1 }
        rule.onNodeWithTag("first.0").assertDoesNotExist()
        rule.onNodeWithTag("first.1").assertExists()
        rule.runOnIdle { owner.value = "second" }
        rule.onNodeWithTag("first.1").assertDoesNotExist()
        rule.onNodeWithTag("second.1").assertExists()
    }

    /** Account/runtime changes reset motion only; the default Chats slot retains saveable state and effects. */
    @Test fun quickAccountSwitchKeepsDefaultSlotMountedAndLockStillDisposesIt() {
        val owner = mutableStateOf("first" to 1)
        val visible = mutableStateOf(true)
        var launches = 0
        var disposals = 0
        var rememberedIdentities = 0
        rule.setContent {
            SettingsRouteMotion(0, 0, owner.value, contentVisible = visible.value, animationsEnabled = true) {
                val identity = rememberSaveable { mutableStateOf(++rememberedIdentities) }
                LaunchedEffect(Unit) { launches++ }
                DisposableEffect(Unit) { onDispose { disposals++ } }
                Text("Chats slot ${identity.value}")
            }
        }
        rule.onNodeWithText("Chats slot 1").assertExists()
        rule.runOnIdle { owner.value = "second" to 1 }
        rule.onNodeWithText("Chats slot 1").assertExists()
        rule.runOnIdle { owner.value = "second" to 2 }
        rule.onNodeWithText("Chats slot 1").assertExists()
        rule.runOnIdle {
            assertEquals(1, launches)
            assertEquals(1, rememberedIdentities)
            assertEquals(0, disposals)
            visible.value = false
        }
        rule.onNodeWithText("Chats slot 1").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, disposals) }
    }

    /** External routing immediately settles an in-flight destination instead of replaying its motion. */
    @Test fun externalRouteSuppressionSnapsAnInFlightDestination() {
        val destination = mutableStateOf(0)
        val suppress = mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SettingsRouteMotion(
                destination.value,
                destination.value,
                "account",
                suppress.value,
                animationsEnabled = true,
            ) {
                Text("Destination $it", Modifier.testTag("external.destination"))
            }
        }
        rule.runOnIdle { destination.value = 1 }
        rule.mainClock.advanceTimeByFrame()
        assertTrue(
            rule
                .onNodeWithTag("external.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left > 0f,
        )
        rule.runOnIdle { suppress.value = true }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(
            0f,
            rule
                .onNodeWithTag("external.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left,
            0.1f,
        )
        rule.runOnIdle { destination.value = 2 }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(
            0f,
            rule
                .onNodeWithTag("external.destination")
                .fetchSemanticsNode()
                .boundsInRoot.left,
            0.1f,
        )
    }

    /** The actual settings destination stays present across account change and disappears on a mid-motion lock. */
    @Test fun mountedSettingsAccountReplacementAndLockKeepTheRealDestinationPrivate() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val appState = settingsAppState(app)
        val detail = mutableStateOf<SettingsDetail?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme {
                SettingsScreen(appState, {}, {}, {}, detail.value, { detail.value = it }, SettingsHomeViewport.Top, {})
            }
        }
        rule.runOnIdle { detail.value = SettingsDetail.Gestures }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("gestures.MessageLeft").assertExists()
        rule.runOnIdle { replaceSettingsState(appState, "setActiveAccountRef", String::class.java, "second") }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("gestures.MessageLeft").assertExists()
        rule.runOnIdle {
            replaceSettingsState(appState, "setAppLockScreenVisible", Boolean::class.javaPrimitiveType!!, true)
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("gestures.MessageLeft").assertDoesNotExist()
        rule.runOnIdle {
            replaceSettingsState(appState, "setAppLockScreenVisible", Boolean::class.javaPrimitiveType!!, false)
        }
        // Unlock remounts the LazyColumn. Allow layout frames, never a full 200 ms route animation.
        rule.mainClock.advanceTimeUntil(timeoutMillis = 64) {
            rule.onAllNodesWithTag("gestures.MessageLeft").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("gestures.MessageLeft").assertExists()
    }

    /** Uses the same no-native settings fixture as the real-screen screenshot suite. */
    private fun settingsAppState(app: Application): WhiteNoiseAppState =
        WhiteNoiseAppState(
            app,
            DraftStore(
                object : DraftPersistence {
                    override fun read(): Map<String, String> = emptyMap()

                    override fun write(
                        key: String,
                        value: String?,
                    ) = Unit
                },
            ),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = null,
        )

    /** Changes actual observable owner/lock state without invoking unrelated native session services. */
    private fun replaceSettingsState(
        appState: WhiteNoiseAppState,
        method: String,
        type: Class<*>,
        value: Any,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod(method, type)
            .apply { isAccessible = true }
            .invoke(appState, value)
    }
}
