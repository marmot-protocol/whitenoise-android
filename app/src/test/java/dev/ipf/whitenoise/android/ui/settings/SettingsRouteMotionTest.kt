package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.AppPageTravel
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

    @Test fun backBeforeTheFirstEntryLayerUpdatePreservesThePresentedZero() {
        val target = mutableStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val page = target.value
            val motion = rememberPageHandoff(page, "account", forward = page == 1, enabled = true)
            // Commit the entry, but withhold its layer just as when Back wins before the first draw.
            Box(Modifier.fillMaxSize().then(if (page == 1) Modifier else motion)) {
                Text("Page $page", Modifier.fillMaxWidth().testTag("page.$page"))
            }
        }
        rule.runOnIdle { target.value = 1 }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("page.1").assertExists()
        rule.runOnIdle { target.value = 0 }
        rule.mainClock.advanceTimeByFrame()
        val returning = rule.onNodeWithTag("page.0")
        assertEquals(0f, returning.fetchSemanticsNode().positionInRoot.x, 0.1f)
        rule.mainClock.advanceTimeBy(64)
        assertEquals(0f, returning.fetchSemanticsNode().positionInRoot.x, 0.1f)
    }

    @Test fun backDuringEntryKeepsThePresentedLayerOffsetOnReplacement() = checkReplacementOffset(false)

    @Test fun densityAndDirectionChangeKeepThePresentedPixelsOnReplacement() = checkReplacementOffset(true)

    private fun checkReplacementOffset(changeConfiguration: Boolean) {
        val target = mutableStateOf(0)
        val density = mutableStateOf(1f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var outgoing: LayoutCoordinates? = null
        var beforeReplacement: Float? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val page = target.value
            // Sample the outgoing node on the actual composition boundary. The paused clock can
            // deliver another opening tick between the test's Back request and this composition.
            val prior = remember(page) { outgoing?.takeIf { it.isAttached && page == 0 }?.positionInRoot()?.x }
            if (prior != null) SideEffect { beforeReplacement = prior }
            CompositionLocalProvider(
                LocalDensity provides Density(density.value),
                LocalLayoutDirection provides direction.value,
            ) {
                SettingsRouteMotion(page, page, "account", animationsEnabled = true) { destination ->
                    Text(
                        "Page $destination",
                        Modifier.fillMaxWidth().testTag("page.$destination").onGloballyPositioned {
                            if (destination == 1) outgoing = it
                        },
                    )
                }
            }
        }
        rule.runOnIdle { target.value = 1 }
        rule.mainClock.advanceTimeBy(64)
        // Semantics positions are physical pixels. Comparing dp would mask a density change.
        val opening =
            rule
                .onNodeWithTag("page.1")
                .fetchSemanticsNode()
                .positionInRoot.x
        assertTrue(opening > 0f && opening < AppPageTravel.value)
        rule.runOnIdle {
            target.value = 0
            if (changeConfiguration) {
                density.value = 2f
                direction.value = LayoutDirection.Rtl
            }
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        val page = rule.onNodeWithTag("page.0")
        val returning = page.fetchSemanticsNode().positionInRoot.x
        assertEquals(
            "The replacement must keep the last drawn pixel translation",
            requireNotNull(beforeReplacement),
            returning,
            0.5f,
        )
        rule.mainClock.advanceTimeBy(96)
        assertTrue(page.fetchSemanticsNode().positionInRoot.x in 0f..returning)
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0f, page.fetchSemanticsNode().positionInRoot.x, 0.1f)
    }

    @Test fun outlinedLauncherToBorderlessDestinationUsesThePageHandoff() {
        val target = mutableStateOf(0)
        val contourDecisions = mutableListOf<Boolean>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val morph = remember { SettingsCardMorph(fullScreen = true) }
                val (contour, host) = rememberSettingsCardMorphHost(morph, target.value, "account", motion = true)
                SideEffect {
                    if (target.value == 1 && contourDecisions.lastOrNull() != contour) contourDecisions += contour
                }
                Box(Modifier.fillMaxSize().then(host)) {
                    CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
                        if (target.value == 0) {
                            val (native, _) = rememberSettingsNativeSurface("home", strokeColor = Color.White)
                            val (source, click) = rememberSettingsRouteClick { target.value = 1 }
                            Text(
                                "Open",
                                Modifier
                                    .fillMaxWidth()
                                    .then(native)
                                    .then(source)
                                    .clickable(onClick = click),
                            )
                        } else {
                            Text("Plain page", Modifier.fillMaxWidth().testTag("plain"))
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Open").performClick()
        rule.mainClock.advanceTimeBy(64)
        rule.runOnIdle {
            assertEquals("Candidate began, then lost its measured match", listOf(true, false), contourDecisions)
        }
        val page = rule.onNodeWithTag("plain")
        val moving = page.getUnclippedBoundsInRoot().left.value
        assertTrue("Rejected contour should hand off to the native page", moving > 0f && moving <= AppPageTravel.value)
        rule.mainClock.advanceTimeBy(320)
        assertEquals(0f, page.getUnclippedBoundsInRoot().left.value, 0.1f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun interruptedPageRetiresDisplacedActionsUntilSettlement() {
        val target = mutableStateOf(0)
        lateinit var inputMode: InputModeManager
        var activations = 0
        var back: OnBackPressedDispatcher? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            inputMode = LocalInputModeManager.current
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler(enabled = target.value == 1) { target.value = 0 }
            SettingsRouteMotion(target.value, target.value, "account", animationsEnabled = true) { page ->
                key(page) {
                    Text("Page $page", Modifier.fillMaxWidth().testTag("action.$page").clickable { activations++ })
                }
            }
        }
        rule.runOnIdle { target.value = 1 }
        rule.mainClock.advanceTimeBy(64)
        rule.runOnIdle { requireNotNull(back).onBackPressed() }
        rule.mainClock.advanceTimeByFrame()
        val action = rule.onNodeWithTag("action.0", useUnmergedTree = true)
        val hiddenAncestor = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        action.assert(hiddenAncestor)
        action.performTouchInput { click() }
        rule.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        action.performSemanticsAction(SemanticsActions.RequestFocus)
        action.assertIsFocused()
        action.performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(0, activations) }
        rule.mainClock.advanceTimeBy(300)
        action.assert(!hiddenAncestor)
        action.performTouchInput { click() }
        rule.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        action.performSemanticsAction(SemanticsActions.RequestFocus)
        action.performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(2, activations) }
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
            replaceSettingsState(appState.appLock, "setScreenVisible", Boolean::class.javaPrimitiveType!!, true)
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("gestures.MessageLeft").assertDoesNotExist()
        rule.runOnIdle {
            replaceSettingsState(appState.appLock, "setScreenVisible", Boolean::class.javaPrimitiveType!!, false)
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
        owner: Any,
        method: String,
        type: Class<*>,
        value: Any,
    ) {
        owner.javaClass
            .getDeclaredMethod(method, type)
            .apply { isAccessible = true }
            .invoke(owner, value)
    }
}
