package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.ui.navigation.WarmResumeLifecycleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** #3780: one first-accepted-input marker per foreground, closed by stop if no input arrives. */
@RunWith(RobolectricTestRunner::class)
class WarmResumeFirstAcceptedInputTest {
    @Before
    fun clearLog() {
        ShadowLog.clear()
    }

    private fun inputMarkers(): List<String> =
        ShadowLog.getLogsForTag("WNWarmResume").map { it.msg }.filter { "event=first-accepted-input" in it }

    @Test
    fun onlyTheFirstHandledInputOfAForegroundIsMarked() {
        val token = WarmResumeTrace.activityCreated(WarmResumeLifecycleClass.entries.first(), false, 0)
        WarmResumeTrace.foregroundStarted(token, 1, WarmResumeLifecycleClass.entries.first())

        WarmResumeInputTrace.firstAcceptedInput(WarmResumeRenderedSurface.ChatList)
        WarmResumeTrace.conversationPhase(WarmResumeConversationPhase.InputReceived)

        val markers = inputMarkers()
        assertEquals(1, markers.size)
        assertTrue(markers.single().startsWith("activity=$token "))
        assertTrue("epoch=1 surface=chat_list" in markers.single())
        WarmResumeTrace.foregroundStopped(token, 1)
    }

    @Test
    fun conversationInputMarksTheConversationSurface() {
        val token = WarmResumeTrace.activityCreated(WarmResumeLifecycleClass.entries.first(), false, 0)
        WarmResumeTrace.foregroundStarted(token, 1, WarmResumeLifecycleClass.entries.first())

        WarmResumeTrace.conversationPhase(WarmResumeConversationPhase.NavigationRequested)

        assertTrue("surface=conversation" in inputMarkers().single())
        WarmResumeTrace.foregroundStopped(token, 1)
    }

    @Test
    fun stopWithoutInputClosesTheForegroundAndLaterInputIsNotMarked() {
        val token = WarmResumeTrace.activityCreated(WarmResumeLifecycleClass.entries.first(), false, 0)
        WarmResumeTrace.foregroundStarted(token, 1, WarmResumeLifecycleClass.entries.first())
        WarmResumeTrace.foregroundStopped(token, 1)

        WarmResumeInputTrace.firstAcceptedInput(WarmResumeRenderedSurface.ChatList)
        assertEquals(emptyList<String>(), inputMarkers())

        WarmResumeTrace.foregroundStarted(token, 2, WarmResumeLifecycleClass.entries.first())
        WarmResumeInputTrace.firstAcceptedInput(WarmResumeRenderedSurface.ChatList)
        assertTrue("epoch=2 surface=chat_list" in inputMarkers().single())
        WarmResumeTrace.foregroundStopped(token, 2)
    }
}
