package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.state.TtsAutoReadPreferences
import dev.ipf.whitenoise.android.state.TtsWarningPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.conversation.messages.forceUsableTtsResolutionForTests
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Header behavior has its own fixture and does not depend on composer geometry or private delegates. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class ConversationHeaderAutoReadActionTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var app: WhiteNoiseAppState
    private lateinit var controller: ConversationController
    private var visible by mutableStateOf(true)
    private val group get() = controller.group.groupIdHex
    private val speaker get() = rule.onNodeWithContentDescription(context.getString(R.string.tts_auto_read_title))

    @Before
    fun prepare() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences(TtsWarningPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
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
        controller =
            ConversationController(
                appState = app,
                initialGroup = conversationTimelineTestGroup(),
                accountRefOverride = "account",
                startOnConstruction = false,
            )
        rule.setContent { WhiteNoiseTheme { if (visible) ConversationAutoReadAction(app, controller) } }
    }

    @After
    fun release() {
        app.stopSpeaking()
        controller.onCleared()
    }

    @Test
    fun conversationOverrideControlsOnlyItsOwnHeaderAndPersistsAcrossReentry() {
        speaker.assertDoesNotExist()
        rule.runOnIdle { app.setConversationAutoReadOverride(group, TtsAutoReadOverride.ON) }
        speaker.assertIsDisplayed().assertIsOn()
        rule.runOnIdle { visible = false }
        speaker.assertDoesNotExist()
        rule.runOnIdle { visible = true }
        speaker.assertIsOn()
        rule.mainClock.autoAdvance = false
        speaker.performClick()
        rule.mainClock.advanceTimeByFrame()
        speaker.assertIsOff().assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(250)
        speaker.assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1_000)
        speaker.assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.runOnIdle {
            val saved = TtsAutoReadPreferences(context)
            assertFalse(saved.isHeaderShortcutVisibleFor("account", group))
            assertEquals(TtsAutoReadOverride.OFF, saved.overrideFor("account", group))
            assertNull(saved.overrideFor("another", group))
            assertNull(saved.overrideFor("account", "another"))
        }
    }

    @Test
    fun globalDefaultKeepsTheHeaderAvailableWithConversationSpeechOff() {
        rule.runOnIdle { app.setTtsAutoReadGlobalDefault(true) }
        speaker.assertIsOn().performClick().assertIsOff()
        speaker.performClick().assertIsOn()
        rule.runOnIdle { app.setConversationAutoReadOverride(group, null) }
        speaker.assertIsOn()
    }

    @Test
    fun visibilityOptInDoesNotEnableSpeech() {
        rule.runOnIdle { app.setTtsHeaderShortcutVisible(true) }
        speaker.assertIsDisplayed().assertIsOff()
        rule.runOnIdle { app.setTtsHeaderShortcutVisible(false) }
        speaker.assertDoesNotExist()
        rule.runOnIdle { assertFalse(app.isConversationAutoRead(group)) }
    }

    @Test
    fun enablingWithAnUnknownEngineRequiresExplicitConsent() {
        rule.runOnIdle {
            app.forceUsableTtsResolutionForTests(EngineTrust.Unknown, "unknown.header")
            app.setTtsHeaderShortcutVisible(true)
        }
        speaker.performClick()
        rule.onNodeWithText(context.getString(R.string.tts_warning_title)).assertIsDisplayed()
        rule.runOnIdle { assertFalse(app.isConversationAutoRead(group)) }
        rule.onNodeWithText(context.getString(R.string.tts_warning_proceed)).performClick()
        rule.runOnIdle {
            assertTrue(app.ttsWarningPreferences.hasAcknowledged("unknown.header"))
            assertTrue(app.isConversationAutoRead(group))
        }
    }

    @Test
    fun confirmingAfterAnEngineChangeDoesNotAcknowledgeOrEnableEitherEngine() {
        rule.runOnIdle {
            app.forceUsableTtsResolutionForTests(EngineTrust.Unknown, "unknown.header")
            app.setTtsHeaderShortcutVisible(true)
        }
        speaker.performClick()
        rule.runOnIdle { app.forceUsableTtsResolutionForTests(EngineTrust.Unknown, "replacement.header") }
        rule.onNodeWithText(context.getString(R.string.tts_warning_proceed)).performClick()
        rule.runOnIdle {
            assertFalse(app.ttsWarningPreferences.hasAcknowledged("unknown.header"))
            assertFalse(app.ttsWarningPreferences.hasAcknowledged("replacement.header"))
            assertFalse(app.isConversationAutoRead(group))
        }
    }

    @Test
    fun offStopsSameConversationManualSpeech() = assertManualStop(paused = false, sameGroup = true)

    @Test
    fun offStopsSameConversationPausedSpeech() = assertManualStop(paused = true, sameGroup = true)

    @Test
    fun offPreservesAnotherConversationsSpeech() = assertManualStop(paused = false, sameGroup = false)

    private fun assertManualStop(
        paused: Boolean,
        sameGroup: Boolean,
    ) {
        rule.runOnIdle {
            app.ttsController.attachEngine(FakeSessionEngine())
            app.setConversationAutoReadOverride(group, TtsAutoReadOverride.ON)
            assertTrue(app.speakAloud(listOf(TtsSpeakableEntry("manual", "Sender", "Manual playback.")), Locale.US))
            app.ttsHistorySession.onConversationSessionStarted("account", if (sameGroup) group else "another")
            if (paused) app.ttsController.pause()
        }
        val before = app.ttsController.state.value
        speaker.performClick()
        rule.runOnIdle {
            val after = app.ttsController.state.value
            assertEquals(sameGroup, after is TtsState.Idle)
            if (!sameGroup) {
                assertEquals(before.sessionId, after.sessionId)
                assertEquals(before.javaClass, after.javaClass)
            }
        }
    }

    private object NoOpDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
