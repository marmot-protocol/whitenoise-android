package dev.ipf.whitenoise.android.ui.conversation

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_ACTION_MENU_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises one OS touchscreen stream across the real conversation's separate action-popup windows. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ConversationDragSelectionPopupAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Selection must be visible before UP, after actions were committed while the same finger stayed down. */
    @Test
    @Suppress("LongMethod") // One owner must retire the global input stream, window callback and controller together.
    fun heldPointerCrossesTheActionPopupAndSelectsAnAdjacentMessage() {
        val group = conversationTimelineTestGroup()
        val members = conversationTimelineMemberSnapshot()
        val records = (1..4).map { timelineRecord(it.toString().repeat(64), it.toULong(), "message-$it") }
        val subscription = ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))
        val scripted = ScriptedConversationLiveSubscriptions(listOf(subscription), group)
        val appState = conversationTimelineTestAppState(scripted.subscriptions)
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = members,
                startOnConstruction = false,
            )
        val surface = ConversationSurfaceState()
        val rawActions = CopyOnWriteArrayList<Int>()
        val selectionSizes = CopyOnWriteArrayList<Int>()
        val selectionObserver =
            Snapshot.registerApplyObserver { changed, _ ->
                if (changed.any { it === surface.selectedMessages }) selectionSizes += surface.selectedMessages.size
            }
        var originalCallback: Window.Callback? = null
        var held = false
        var last = Offset.Zero
        var downTime = 0L
        lateinit var hostView: View
        val bodyResult = runCatching {
            composeRule.runOnUiThread {
                val previousCallback = composeRule.activity.window.callback
                originalCallback = previousCallback
                composeRule.activity.window.callback =
                    object : Window.Callback by previousCallback {
                        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                            rawActions += event.actionMasked
                            return previousCallback.dispatchTouchEvent(event)
                        }
                    }
                runBlocking {
                    controller.applyTimelinePage(
                        page = timelinePage(*records.toTypedArray()),
                        replaceWindow = true,
                        updatePagination = true,
                    )
                }
                controller.markAuthoritativeTimelinePublishedForTest()
            }
            composeRule.setContent {
                hostView = LocalView.current
                WhiteNoiseTheme {
                    Box(Modifier.fillMaxSize().testTag("android-range-host")) {
                        ConversationScreen(
                            appState = appState,
                            chat = ChatListItem(group, null, null, 1, memberSnapshot = members),
                            controller = controller,
                            onBack = {},
                            surfaceState = surface,
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
            val rootScreenOrigin = IntArray(2)
            composeRule.runOnIdle { hostView.getLocationOnScreen(rootScreenOrigin) }
            fun screenPoint(id: String): Offset =
                composeRule.onNodeWithTag(messageBubbleRowTestTag(id)).fetchSemanticsNode().boundsInRoot.center +
                    Offset(rootScreenOrigin[0].toFloat(), rootScreenOrigin[1].toFloat())
            val anchor = records.last().messageIdHex
            val endpoint = records[records.lastIndex - 1].messageIdHex
            last = screenPoint(anchor)
            val adjacent = screenPoint(endpoint)
            downTime = SystemClock.uptimeMillis()
            inject(downTime, MotionEvent.ACTION_DOWN, last)
            held = true
            composeRule.mainClock.advanceTimeBy(1_000L)
            SystemClock.sleep(1_000L)
            composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertIsDisplayed()
            saveScreenshot("held-menu")
            last = adjacent
            inject(downTime, MotionEvent.ACTION_MOVE, last)
            try {
                composeRule.waitUntil(timeoutMillis = 3_000L) { surface.selectedMessages.size == 2 }
            } catch (failure: ComposeTimeoutException) {
                throw AssertionError("raw actions=$rawActions; applied selection sizes=$selectionSizes", failure)
            }
            // Keep the finger held across popup disposal and the selection-layout frames.
            composeRule.mainClock.advanceTimeBy(150L)
            SystemClock.sleep(150L)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    "raw actions=$rawActions; applied selection sizes=$selectionSizes",
                    setOf(anchor, endpoint),
                    surface.selectedMessages.keys.toSet(),
                )
            }
            composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertDoesNotExist()
            val resources = composeRule.activity.resources
            composeRule
                .onNodeWithText(resources.getString(R.string.conversation_select_messages))
                .assertIsDisplayed()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        resources.getQuantityString(R.plurals.message_selected_count, 2, 2),
                    ),
                )
            saveScreenshot("selected-before-up")
            inject(downTime, MotionEvent.ACTION_UP, last)
            held = false
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(setOf(anchor, endpoint), surface.selectedMessages.keys.toSet()) }
        }.recoverCatching { failure ->
            runCatching { saveScreenshot("failure") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw AssertionError("raw actions=$rawActions; applied selection sizes=$selectionSizes", failure)
        }
        // Attempt every owner cleanup, preserving the original failure and suppressing cleanup failures onto it.
        val cleanupResults =
            listOf(
                runCatching { if (held) inject(downTime, MotionEvent.ACTION_CANCEL, last) },
                runCatching { retireController(controller, originalCallback) },
                runCatching { selectionObserver.dispose() },
            )
        bodyResult.exceptionOrNull()?.let { primary ->
            cleanupResults.forEach { it.exceptionOrNull()?.let(primary::addSuppressed) }
        }
        bodyResult.getOrThrow()
        cleanupResults.forEach { it.getOrThrow() }
    }

    /** Restores Android dispatch before retiring the synthetic conversation, including a restoration failure. */
    private fun retireController(
        controller: ConversationController,
        originalCallback: Window.Callback?,
    ) {
        composeRule.runOnUiThread {
            try {
                originalCallback?.let { composeRule.activity.window.callback = it }
            } finally {
                controller.onCleared()
            }
        }
    }

    /** Retains actual popup/selection frames in the standard hosted instrumented-test artifact. */
    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = output ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)).path
        val directory = File(root, "conversation-held-selection")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Sends a global, stable-downTime touchscreen event through Android's window dispatcher. */
    private fun inject(
        downTime: Long,
        action: Int,
        point: Offset,
    ) {
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coordinates =
            MotionEvent.PointerCoords().apply {
                x = point.x
                y = point.y
                pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
                size = 1f
            }
        val event =
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                1,
                arrayOf(properties),
                arrayOf(coordinates),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }
}
