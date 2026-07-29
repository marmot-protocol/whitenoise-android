package dev.ipf.whitenoise.android.ui.share

import android.content.Context
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
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
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.robolectric.shadows.ShadowDialog

/**
 * Behavioral Back coverage for the inbound share recipient sheet (issue #1721).
 * Composes production [ShareChatPickerSheet] with [ModalBottomSheet], exercises
 * committed and predictive Back through production callback seams,
 * and verifies request clearing plus route isolation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShareChatPickerSheetBackTest {
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
    fun backFromPartiallyExpandedIdleDismissesSheetAndClearsRequest() {
        val tracker = mountSharePicker()

        composeRule.onNodeWithText(shareToLabel).assertIsDisplayed()
        pressCommittedBack()

        assertEquals(1, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        assertEquals(0, tracker.stageCount)
        composeRule.onNodeWithText(shareToLabel).assertIsNotDisplayed()
    }

    @Test
    fun backFromExpandedIdleDismissesSheetAndClearsRequest() {
        val tracker = mountSharePicker()

        composeRule.onNodeWithText(searchLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(searchLabel).assertIsFocused()
        ShadowDialog.getLatestDialog().currentFocus?.clearFocus()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(searchLabel).assertIsNotFocused()

        pressCommittedBack()

        assertEquals(
            "Expanded idle Back must fully cancel the share request, not partial-expand",
            1,
            tracker.dismissCount,
        )
        assertEquals(0, tracker.routeBackCount)
        composeRule.onNodeWithText(shareToLabel).assertIsNotDisplayed()
    }

    @Test
    fun backWithSearchFocusedDismissesSheetAndClearsRequest() {
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
    fun repeatedBackWhileSheetIsSettlingDismissesRequestOnce() {
        val tracker = mountSharePicker()

        composeRule.mainClock.autoAdvance = false
        Espresso.pressBack()
        composeRule.mainClock.advanceTimeBy(100)
        Espresso.pressBack()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        assertEquals(1, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        assertEquals(0, tracker.stageCount)
    }

    @Test
    fun repeatedBackWithSearchFocusedWhileSheetIsSettlingDismissesRequestOnce() {
        val tracker = mountSharePicker()
        composeRule.onNodeWithText(searchLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(searchLabel).assertIsFocused()

        composeRule.mainClock.autoAdvance = false
        Espresso.pressBack()
        composeRule.mainClock.advanceTimeBy(100)
        Espresso.pressBack()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        assertEquals(1, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        assertEquals(0, tracker.stageCount)
    }

    @Test
    fun dismissActionClearsFocusHidesKeyboardAndHidesSheet() {
        var clearedFocus = false
        var hidKeyboard = false
        var hidSheet = false
        runShareChatPickerDismissal(
            clearFocus = { clearedFocus = true },
            hideKeyboard = { hidKeyboard = true },
            hideSheet = { hidSheet = true },
        )
        assertTrue(clearedFocus)
        assertTrue(hidKeyboard)
        assertTrue(hidSheet)
    }

    @Test
    fun canceledPredictiveBackRestoresSheetAndKeepsRequest() {
        val tracker = mountSharePicker()

        composeRule.onNodeWithText(shareToLabel).assertIsDisplayed()
        val initialWidth = sheetTitleWidth()
        val dispatcher = sheetOnBackPressedDispatcher()

        composeRule.runOnIdle {
            dispatcher.dispatchOnBackStarted(predictiveBackEvent(progress = 0f))
            dispatcher.dispatchOnBackProgressed(predictiveBackEvent(progress = 0.5f))
        }
        composeRule.waitForIdle()

        val progressedWidth = sheetTitleWidth()
        assertTrue(
            "Predictive progress should scale the sheet before cancel",
            progressedWidth < initialWidth * 0.98f,
        )

        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()

        assertEquals(initialWidth, sheetTitleWidth(), 1f)
        assertEquals(0, tracker.dismissCount)
        assertEquals(0, tracker.routeBackCount)
        composeRule.onNodeWithText(shareToLabel).assertIsDisplayed()
    }

    @Test
    fun committedPredictiveBackDismissesSheetOnceAndClearsRequest() {
        val tracker = mountSharePicker()

        composeRule.onNodeWithText(shareToLabel).assertIsDisplayed()
        val dispatcher = sheetOnBackPressedDispatcher()

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

    private fun mountSharePicker(): PickerTracker {
        val tracker = PickerTracker()
        val appState = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                BackHandler { tracker.routeBackCount++ }
                if (tracker.showPicker) {
                    ShareChatPickerSheet(
                        appState = appState,
                        payload = payload,
                        onDismiss = {
                            tracker.dismissCount++
                            tracker.showPicker = false
                        },
                        onStage = { tracker.stageCount++ },
                        overlayBackRegistrar =
                            ShareChatPickerOverlayBackRegistrar { priority, callback ->
                                tracker.overlayBackPriority = priority
                                tracker.overlayBackCallback = callback
                                { tracker.overlayBackCallback = null }
                            },
                    )
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

    private fun sheetOnBackPressedDispatcher(): OnBackPressedDispatcher {
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("Share picker must own a ComponentDialog", dialog is ComponentDialog)
        return (dialog as ComponentDialog).onBackPressedDispatcher
    }

    private fun sheetTitleWidth(): Float =
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
