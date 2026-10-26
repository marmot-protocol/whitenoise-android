package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Notification affordances share the controller's explicit choice and dispatch ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
internal class ConversationDictationNotificationChoiceTest : ConversationDictationForegroundTestCase() {
    @Test
    fun latestNotificationChoiceWinsBeforeDeliveryForEitherStoredPreference() =
        runTest {
            ConversationDictationDeliveryMode.entries.forEach { preference ->
                ConversationDictationDeliveryMode.entries.forEach { finalChoice ->
                    val harness = DictationForegroundTestHost(this, preference)
                    ConversationDictationForegroundService.hostResolver = { harness }
                    val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
                    try {
                        val service = lifecycle.get()
                        service.onStartCommand(startIntent(service, harness), 0, 1)
                        val first = if (finalChoice == ConversationDictationDeliveryMode.SendOnFinish) 1 else 2
                        service.onStartCommand(actionCommand(service, harness, notificationActions[first]), 0, 2)
                        assertCompactButtonsEnabled(service, service.foreground.foregroundNotification())
                        val last = if (finalChoice == ConversationDictationDeliveryMode.SendOnFinish) 2 else 1
                        val lastIntent = actionCommand(service, harness, notificationActions[last])
                        service.onStartCommand(lastIntent, 0, 3)
                        service.onStartCommand(lastIntent, 0, 4)
                        assertEquals(finalChoice, harness.conversationDictation.processingDeliveryMode)
                        assertCompactButtonsEnabled(service, service.foreground.foregroundNotification())
                        val listener = harness.platform.listener
                        listener.onResult("latest notification choice")
                        runCurrent()
                        listener.onResult("stale duplicate")
                        val send = finalChoice == ConversationDictationDeliveryMode.SendOnFinish
                        assertEquals(
                            if (send) listOf("latest notification choice") else emptyList<String>(),
                            harness.sent,
                        )
                        assertEquals(if (send) "" else "latest notification choice", harness.draft.text)
                        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                    } finally {
                        lifecycle.destroy()
                    }
                }
            }
        }

    @Test
    fun dispatchClaimDisablesAllNotificationActionsUntilDeliveryCompletes() =
        runTest {
            val delivery = CompletableDeferred<Unit>()
            val harness = DictationForegroundTestHost(scope = this, onDispatchClaimed = { delivery.await() })
            ConversationDictationForegroundService.hostResolver = { harness }
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            try {
                val service = lifecycle.get()
                service.onStartCommand(startIntent(service, harness), 0, 1)
                val commands = notificationActions.map { actionCommand(service, harness, it) }
                service.onStartCommand(commands.last(), 0, 2)
                harness.platform.listener.onResult("claimed dispatch")
                runCurrent()
                assertTrue(harness.conversationDictation.deliveryInProgress)
                assertFalse(harness.conversationDictation.completionActionsEnabled)
                assertCompactButtonsDisabled(service, service.foreground.foregroundNotification())
                commands.forEachIndexed { index, command -> service.onStartCommand(command, 0, index + 3) }
                runCurrent()
                assertTrue(harness.conversationDictation.deliveryInProgress)
                assertEquals(listOf("claimed dispatch"), harness.sent)
                assertEquals(TextFieldValue(""), harness.draft)
                delivery.complete(Unit)
                runCurrent()
                assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                assertEquals(listOf("claimed dispatch"), harness.sent)
            } finally {
                delivery.complete(Unit)
                runCurrent()
                lifecycle.destroy()
            }
        }
}
