package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsEngineInfo
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationAutoReadArrivalTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = FakeSessionEngine()
    private lateinit var app: WhiteNoiseAppState
    private lateinit var controller: ConversationController
    private var entryUnreadCount by mutableIntStateOf(0)

    @Before
    fun prepareEmptyConversation() {
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
                accounts =
                    listOf(
                        AccountSummaryFfi(
                            label = "account",
                            accountIdHex = "aa",
                            localSigning = true,
                            externalSigning = false,
                            signedOut = false,
                            running = true,
                            unlinked = false,
                        ),
                    ),
                activeAccountRef = "account",
            )
        app.ttsController.attachEngine(engine)
        val resolutionField = WhiteNoiseAppState::class.java.getDeclaredField("ttsResolution\$delegate")
        resolutionField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val resolution = resolutionField.get(app) as androidx.compose.runtime.MutableState<TtsResolutionResult?>
        resolution.value =
            TtsResolutionResult(
                status = 0,
                engines = listOf(TtsEngineInfo("test", "Test", EngineTrust.Local)),
                defaultEnginePackage = "test",
                handle = null,
            )
        controller =
            ConversationController(
                appState = app,
                initialGroup = conversationTimelineTestGroup(),
                accountRefOverride = "account",
                startOnConstruction = false,
                markdownParser = { EMPTY_MARKDOWN_DOCUMENT },
            )
        composeRule.setContent {
            ConversationTtsAutoReadEffects(app, controller, "chat", entryUnreadCount, null, true)
        }
        composeRule.waitForIdle()
    }

    @After
    fun releaseSpeech() {
        if (::app.isInitialized) app.stopSpeaking()
        if (::controller.isInitialized) controller.onCleared()
    }

    @Test
    fun enablingWithAnUnreadBacklogStartsSpeechWhenIdle() {
        composeRule.runOnIdle {
            publishIncoming("unread")
            entryUnreadCount = 1
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.ON)
        }
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            engine.spoken.any { "Reply unread" in it.text }
        }
    }

    @Test
    fun enablingWithAnUnreadBacklogDoesNotReplaceManualSpeech() = assertManualSessionRetained(paused = false)

    @Test
    fun enablingWithAnUnreadBacklogDoesNotReplacePausedManualSpeech() = assertManualSessionRetained(paused = true)

    private fun assertManualSessionRetained(paused: Boolean) {
        composeRule.runOnIdle {
            publishIncoming("unread")
            entryUnreadCount = 1
            assertTrue(app.speakAloud(listOf(TtsSpeakableEntry("manual", "Sender", "Manual playback.")), Locale.US))
            if (paused) app.ttsController.pause()
        }
        composeRule.waitForIdle()
        val manual = app.ttsController.state.value
        assertTrue(if (paused) manual is TtsState.Paused else manual is TtsState.Speaking)
        composeRule.runOnIdle {
            app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.ON)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val current = app.ttsController.state.value
            assertEquals("Enabling auto-read must retain the manual session", manual.sessionId, current.sessionId)
            assertEquals(manual.javaClass, current.javaClass)
            assertFalse(app.ownsTtsAutoReadSession(controller.group.groupIdHex))
        }
    }

    @Test
    fun enablingInsideAnEmptyChatReadsTheNextReplyWithoutReopening() {
        composeRule.runOnIdle {
            app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.ON)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val state = composeRule.activity.lifecycle.currentState
            assertTrue(state.isAtLeast(Lifecycle.State.RESUMED))
            assertTrue(app.isConversationAutoRead(controller.group.groupIdHex))
            assertTrue(app.ttsHasUsableEngine)
        }
        composeRule.runOnIdle { publishIncoming("first") }
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            engine.spoken.any { "Reply first" in it.text }
        }
    }

    @Test
    fun backgroundArrivalsCannotRestartAnIdleReader() {
        composeRule.runOnIdle {
            app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.ON)
        }
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.runOnIdle { publishIncoming("private") }
        composeRule.waitForIdle()
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun returningToADisjointLiveWindowReanchorsWithoutReadingHistory() {
        assertDisjointLiveWindowReanchors(visitHistory = true)
    }

    @Test
    fun replacingTheLiveWindowReanchorsWithoutReadingHistory() {
        assertDisjointLiveWindowReanchors(visitHistory = false)
    }

    private fun assertDisjointLiveWindowReanchors(visitHistory: Boolean) {
        composeRule.runOnIdle {
            publishNativeWindow("anchor", hasMoreAfter = false)
            app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.ON)
        }
        composeRule.waitForIdle()
        if (visitHistory) {
            composeRule.runOnIdle { publishNativeWindow("older", hasMoreAfter = true) }
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle { publishNativeWindow("live", hasMoreAfter = false) }
        composeRule.waitForIdle()
        assertTrue(engine.spoken.isEmpty())
        composeRule.runOnIdle {
            publishNativeWindow("live", "next", hasMoreAfter = false)
        }
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            engine.spoken.any { "Reply next" in it.text }
        }
        assertFalse(engine.spoken.any { "Reply live" in it.text || "Reply older" in it.text })
    }

    private fun publishNativeWindow(
        vararg ids: String,
        hasMoreAfter: Boolean,
    ) {
        val timelineField = ConversationController::class.java.getDeclaredField("timeline\$delegate")
        timelineField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val timeline = timelineField.get(controller) as androidx.compose.runtime.MutableState<List<TimelineMessage>>
        timeline.value = ids.map { incoming(it, 0uL) }
        val pagingField = ConversationController::class.java.getDeclaredField("hasMoreAfter\$delegate")
        pagingField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val paging = pagingField.get(controller) as androidx.compose.runtime.MutableState<Boolean>
        paging.value = hasMoreAfter
    }

    @Test
    fun disablingInsideAnEmptyChatDoesNotReadTheNextReply() {
        composeRule.runOnIdle {
            app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.ON)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            app.setConversationAutoReadOverride(controller.group.groupIdHex, TtsAutoReadOverride.OFF)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { publishIncoming("silent") }
        composeRule.waitForIdle()
        assertTrue(engine.spoken.isEmpty())
    }

    // Feed the isolated screen without starting native subscriptions or widening production setters.
    private fun publishIncoming(id: String) {
        val field = ConversationController::class.java.getDeclaredField("timeline\$delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val timeline = field.get(controller) as androidx.compose.runtime.MutableState<List<TimelineMessage>>
        timeline.value = listOf(incoming(id, 1uL))
    }

    private object NoOpDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private fun incoming(
        id: String,
        order: ULong,
    ) = TimelineMessage(
        id = "msg:$id",
        record =
            timelineAppMessage(id).copy(
                direction = "received",
                plaintext = "Reply $id",
                groupIdHex = controller.group.groupIdHex,
            ),
        status = MessageStatus.Received,
        timelineOrder = order,
    )
}
