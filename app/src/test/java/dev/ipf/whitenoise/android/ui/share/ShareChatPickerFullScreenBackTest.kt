package dev.ipf.whitenoise.android.ui.share

import android.content.Context
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral Back coverage for the inbound share recipient screen (issues #1721 and #1922).
 * Composes production [ShareChatPickerFullScreen] for modal committed-Back coverage. Predictive
 * dispatcher tests compose [ShareChatPickerFullScreenContent] in the activity window because
 * Robolectric cannot dispatch predictive events to a Compose dialog's separate dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShareChatPickerFullScreenBackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val shareToLabel = app.getString(R.string.share_to)
    private val searchLabel = app.getString(R.string.share_search_chats)
    private val payload =
        SharePayload(
            text = "shared text",
            streamUris = emptyList(),
            intentMimeType = "text/plain",
        )

    @Test
    fun backFromIdleDismissesScreenAndClearsRequest() {
        val tracker = mountSharePicker()

        composeRule.onNodeWithText(shareToLabel).assertIsDisplayed()
        pressCommittedBack()

        assertEquals(1, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        assertEquals(0, tracker.stageCount)
        composeRule.onNodeWithText(shareToLabel).assertIsNotDisplayed()
    }

    @Test
    fun backWithSearchFocusedDismissesScreenAndClearsRequest() {
        val tracker = mountSharePicker()

        composeRule.onNodeWithText(searchLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(searchLabel).assertIsFocused()

        pressCommittedBack()

        assertEquals(1, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        composeRule.onNodeWithText(shareToLabel).assertIsNotDisplayed()
    }

    @Test
    fun overlayPriorityBackWinsOverUnderlyingRouteHandler() {
        val tracker = mountSharePicker()
        composeRule.onNodeWithText(searchLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(searchLabel).assertIsFocused()
        assertEquals(OnBackInvokedDispatcher.PRIORITY_OVERLAY, tracker.overlayBackPriority)

        checkNotNull(tracker.overlayBackCallback).onBackInvoked()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        assertEquals(1, tracker.dismissCount)
        assertEquals("Back must not reach the underlying route", 0, tracker.routeBackCount)
        assertEquals(null, tracker.overlayBackCallback)
        composeRule.onNodeWithText(searchLabel).assertIsNotDisplayed()
    }

    @Test
    fun closeActionDismissesRequestOnce() {
        val tracker = mountSharePicker()

        composeRule.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        composeRule.waitForIdle()

        assertEquals(1, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        assertEquals(0, tracker.stageCount)
    }

    @Test
    fun repeatedFocusedBackCallbackDismissesRequestOnce() {
        val tracker = mountSharePicker()
        composeRule.onNodeWithText(searchLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(searchLabel).assertIsFocused()

        val callback = checkNotNull(tracker.overlayBackCallback)
        composeRule.runOnIdle {
            callback.onBackInvoked()
            callback.onBackInvoked()
        }
        composeRule.waitForIdle()

        assertEquals(1, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        assertEquals(0, tracker.stageCount)
    }

    @Test
    fun dismissActionClearsFocusHidesKeyboardAndDismissesScreen() {
        var clearedFocus = false
        var hidKeyboard = false
        var hidSheet = false
        runShareChatPickerDismissal(
            clearFocus = { clearedFocus = true },
            hideKeyboard = { hidKeyboard = true },
            dismiss = { hidSheet = true },
        )
        assertTrue(clearedFocus)
        assertTrue(hidKeyboard)
        assertTrue(hidSheet)
    }

    @Test
    fun canceledPredictiveBackRestoresScreenAndKeepsRequest() {
        val tracker = mountSharePicker(modal = false)

        composeRule.onNodeWithText(shareToLabel).assertIsDisplayed()
        val initialWidth = screenTitleWidth()
        val dispatcher = screenOnBackPressedDispatcher()

        composeRule.runOnIdle {
            dispatcher.dispatchOnBackStarted(predictiveBackEvent(progress = 0f))
            dispatcher.dispatchOnBackProgressed(predictiveBackEvent(progress = 0.5f))
        }
        composeRule.waitForIdle()

        val progressedWidth = screenTitleWidth()
        assertTrue(
            "Predictive progress should scale the screen before cancel",
            progressedWidth < initialWidth * 0.98f,
        )

        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()

        assertEquals(initialWidth, screenTitleWidth(), 1f)
        assertEquals(0, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        composeRule.onNodeWithText(shareToLabel).assertIsDisplayed()
    }

    @Test
    fun committedPredictiveBackDismissesScreenOnceAndClearsRequest() {
        val tracker = mountSharePicker(modal = false)

        composeRule.onNodeWithText(shareToLabel).assertIsDisplayed()
        val dispatcher = screenOnBackPressedDispatcher()

        composeRule.runOnIdle {
            dispatcher.dispatchOnBackStarted(predictiveBackEvent(progress = 0f))
            dispatcher.dispatchOnBackProgressed(predictiveBackEvent(progress = 0.6f))
            dispatcher.onBackPressed()
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        assertEquals(1, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        composeRule.onNodeWithText(shareToLabel).assertIsNotDisplayed()
    }

    @Test
    fun overlayBackAnimationCallbackRestoresProgressOnCancel() {
        var progress = -1f
        var committed = false
        val callback =
            shareChatPickerOverlayBackAnimationCallback(
                onProgress = { progress = it },
                onCommit = { committed = true },
                onCancel = { progress = 0f },
            )

        callback.onBackStarted(platformBackEvent(progress = 0.1f))
        callback.onBackProgressed(platformBackEvent(progress = 0.7f))
        assertEquals(0.7f, progress, 0.01f)

        callback.onBackCancelled()
        assertEquals(0f, progress, 0.01f)
        assertFalse(committed)
    }

    private fun mountSharePicker(modal: Boolean = true): PickerTracker {
        val tracker = PickerTracker()
        val appState = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                BackHandler { tracker.routeBackCount++ }
                if (tracker.showPicker) {
                    val onDismiss = {
                        tracker.dismissCount++
                        tracker.showPicker = false
                    }
                    val onStage: (String, List<String>) -> Boolean = { _, _ ->
                        tracker.stageCount++
                        true
                    }
                    val registrar =
                        ShareChatPickerOverlayBackRegistrar { priority, callback ->
                            tracker.overlayBackPriority = priority
                            tracker.overlayBackCallback = callback
                            { tracker.overlayBackCallback = null }
                        }
                    if (modal) {
                        ShareChatPickerFullScreen(
                            appState = appState,
                            payload = payload,
                            onDismiss = onDismiss,
                            onStage = onStage,
                            overlayBackRegistrar = registrar,
                        )
                    } else {
                        ShareChatPickerFullScreenContent(
                            appState = appState,
                            payload = payload,
                            onDismiss = onDismiss,
                            onStage = onStage,
                            overlayBackRegistrar = registrar,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return tracker
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = app,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_HEX,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun pressCommittedBack() {
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    private fun screenOnBackPressedDispatcher(): OnBackPressedDispatcher = composeRule.activity.onBackPressedDispatcher

    private fun screenTitleWidth(): Float =
        composeRule
            .onNodeWithText(shareToLabel)
            .fetchSemanticsNode()
            .boundsInRoot
            .width

    private fun predictiveBackEvent(progress: Float): BackEventCompat =
        BackEventCompat(
            touchX = 0f,
            touchY = 0f,
            progress = progress,
            swipeEdge = BackEventCompat.EDGE_LEFT,
        )

    private fun platformBackEvent(progress: Float): BackEvent =
        BackEvent(
            0f,
            0f,
            progress,
            BackEvent.EDGE_LEFT,
        )

    private class PickerTracker {
        var showPicker by mutableStateOf(true)
        var dismissCount by mutableIntStateOf(0)
        var routeBackCount by mutableIntStateOf(0)
        var stageCount by mutableIntStateOf(0)
        var overlayBackPriority: Int? = null
        var overlayBackCallback: OnBackAnimationCallback? = null
    }

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_HEX = "aa".repeat(32)
    }
}
