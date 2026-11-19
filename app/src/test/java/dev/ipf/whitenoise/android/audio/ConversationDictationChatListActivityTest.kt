package dev.ipf.whitenoise.android.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat list tells speaking apart from transcribing, only for the conversation that owns the session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
internal class ConversationDictationChatListActivityTest {
    @Test
    fun chatListActivityFollowsCaptureTranscriptionAndDelivery() =
        runTest {
            val delivery = CompletableDeferred<Unit>()
            val host = DictationForegroundTestHost(scope = this, onDispatchClaimed = { delivery.await() })
            val controller = host.conversationDictation
            try {
                assertEquals(ConversationDictationActivity.Listening, controller.chatListActivity("account", "group"))
                assertEquals(ConversationDictationActivity.Listening, controller.chatListActivity("account", "GROUP"))
                assertNull(controller.chatListActivity("account", "other"))
                assertNull(controller.chatListActivity("other", "group"))

                controller.send()
                assertEquals(ConversationDictationActivity.Transcribing, controller.chatListActivity("account", "group"))

                host.platform.listener.onResult("dictated")
                runCurrent()
                assertEquals(ConversationDictationActivity.Transcribing, controller.chatListActivity("account", "group"))

                delivery.complete(Unit)
                runCurrent()
                assertNull(controller.chatListActivity("account", "group"))
            } finally {
                delivery.complete(Unit)
                runCurrent()
            }
        }

    @Test
    fun failedSessionShowsNoChatListActivity() =
        runTest {
            val host = DictationForegroundTestHost(scope = this)
            host.failRecognition()
            runCurrent()
            assertNull(host.conversationDictation.chatListActivity("account", "group"))
        }
}
