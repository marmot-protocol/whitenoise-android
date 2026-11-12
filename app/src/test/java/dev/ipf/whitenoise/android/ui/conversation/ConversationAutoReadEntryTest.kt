package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.state.TtsAutoReadPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.conversation.messages.forceUsableTtsResolutionForTests
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The header updates future policy without introducing an idle live-arrival reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationAutoReadEntryTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = FakeSessionEngine()
    private lateinit var app: WhiteNoiseAppState
    private lateinit var controller: ConversationController

    @Before
    fun prepare() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        app =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(NoOpDraftPersistence),
                accountIdHexResolver = { null },
                accounts = listOf(AccountSummaryFfi("account", "aa", true, false, false, true, false)),
                activeAccountRef = "account",
            )
        app.forceUsableTtsResolutionForTests()
        app.ttsController.attachEngine(engine)
        controller =
            ConversationController(
                appState = app,
                initialGroup = conversationTimelineTestGroup(),
                accountRefOverride = "account",
                startOnConstruction = false,
            )
    }

    @After
    fun release() {
        app.stopSpeaking()
        controller.onCleared()
    }

    @Test
    fun enablingMidSessionDoesNotReplayEntryBacklogOrStartOnArrival() {
        publish("unread")
        mount()
        rule.runOnIdle { app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.ON) }
        rule.waitForIdle()
        rule.runOnIdle { publish("later") }
        rule.waitForIdle()
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun enabledOnEntryStillReadsItsUnreadBacklog() {
        app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.ON)
        publish("unread")
        mount()
        rule.waitUntil(10_000) { engine.spoken.any { "Reply unread" in it.text } }
    }

    @Test
    fun rejectedEntryDoesNotReplayAfterCaptureReleaseAndForegroundReturn() {
        app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.ON)
        val capture = Any()
        app.microphoneCaptureCoordinator.tryAcquire(capture)
        publish("private")
        mount()
        assertTrue(engine.spoken.isEmpty())
        assertTrue(app.microphoneCaptureCoordinator.isOwnedBy(capture))
        app.microphoneCaptureCoordinator.release(capture)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertTrue(engine.spoken.isEmpty())
    }

    private fun mount() {
        rule.setContent { ConversationTtsAutoReadEffects(app, controller, "chat", 1, null, true) }
        rule.waitForIdle()
    }

    /** Isolated projection input; no subscriptions, disk state or production setters are changed. */
    private fun publish(id: String) {
        val field =
            ConversationController::class.java.getDeclaredField("timeline\$delegate").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val timeline = field.get(controller) as MutableState<List<TimelineMessage>>
        timeline.value =
            listOf(
                TimelineMessage(
                    id = "msg:$id",
                    record = timelineAppMessage(id).copy(direction = "received", plaintext = "Reply $id"),
                    status = MessageStatus.Received,
                    timelineOrder = 1uL,
                ),
            )
    }

    private object NoOpDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
