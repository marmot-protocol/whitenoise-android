package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The detector works and the decision is right; this asserts they are actually
 * connected to the message row, which is the part no behavioural test in this
 * module can reach without standing up a whole conversation.
 *
 * Scope, stated plainly: this reads source text. It cannot prove the gesture
 * fires on a device, only that the wiring a reviewer would look for is present
 * and has not silently drifted - which is the failure this guards, because a
 * detector nothing calls looks exactly like a working one from its own tests.
 */
class TtsQuickTransportWiringCoverageTest {
    @Test
    fun theMessageRowAttachesTheGesture() {
        val body = source("ui/conversation/messages/MessageBubble.kt")

        assertTrue("the row must attach the two-finger detector", ".twoFingerSwipeDown(" in body)
        assertTrue(
            "the detector must call the row's resolver",
            "onSwipe = ::quickTransportFromTwoFingerSwipe" in body,
        )
        assertTrue(
            "selection modes own the row, and a deleted message has nothing to read",
            "enabled = !selectionMode && !textSelectionMode && !deleted," in body,
        )
    }

    @Test
    fun theGestureIsClaimedBeforeTheReplySwipeAndLongPressCanSeeIt() {
        // Order in the modifier chain is the whole reason a two-finger drag
        // does not also swipe-to-reply: the detector has to receive the initial
        // pass first so it can consume from the second finger down.
        val body = source("ui/conversation/messages/MessageBubble.kt")
        val gesture = body.indexOf(".twoFingerSwipeDown(")
        val replySwipe = body.indexOf("detectHorizontalDragGestures(")
        val longPress = body.indexOf("Modifier.longPressOrVerticalDrag(")

        assertTrue("expected all three gestures on the row", gesture > 0 && replySwipe > 0 && longPress > 0)
        assertTrue("the two-finger detector must come before the reply swipe", gesture < replySwipe)
        assertTrue("the two-finger detector must come before the long press", gesture < longPress)
    }

    @Test
    fun eachResolvedActionRoutesToItsOwnTransportCall() {
        val body =
            source("ui/conversation/messages/MessageBubble.kt")
                .functionBody("quickTransportFromTwoFingerSwipe")

        assertTrue("the decision must come from the shared policy", "ttsQuickTransportActionFor(" in body)
        assertTrue(
            "pause must hold the controller",
            "TtsQuickTransportAction.Pause -> appState.ttsController.pause()" in body,
        )
        assertTrue(
            "resume must continue the controller",
            "TtsQuickTransportAction.Resume -> appState.ttsController.resume()" in body,
        )
        assertTrue(
            "starting must go through the same path the message action uses",
            "TtsQuickTransportAction.StartReadingMessage -> startSpeakAloud()" in body,
        )
        assertTrue(
            "an ignored gesture must do nothing at all",
            "if (action == TtsQuickTransportAction.Ignore) return" in body,
        )
    }

    @Test
    fun theRowDoesNotObservePlaybackJustToKnowWhatAGestureWouldMean() {
        // A conversation recomposes constantly while a message is read aloud.
        // Resolving the action from the current value at the moment the gesture
        // fires keeps every bubble out of that.
        val body =
            source("ui/conversation/messages/MessageBubble.kt")
                .functionBody("quickTransportFromTwoFingerSwipe")

        assertTrue(
            "the action must be resolved from the current value",
            "appState.ttsController.state.value" in body,
        )
        assertFalse("the row must not subscribe to playback state", "collectAsState" in body)
    }

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing source file: $relativePath")
}
