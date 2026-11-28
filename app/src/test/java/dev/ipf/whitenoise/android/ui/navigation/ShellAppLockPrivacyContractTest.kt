package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.WipeFailureItem
import dev.ipf.whitenoise.android.state.WipeReport
import dev.ipf.whitenoise.android.state.WipeStage
import dev.ipf.whitenoise.android.state.WipeStageReport
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the production root may draw while the app is locked: the lock screen, and none of the surfaces that sit
 * beside or above the shell. Each surface is shown first, so its absence under the lock is the lock's doing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellAppLockPrivacyContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val app = MountedContractApp(composeRule)

    /** Releases the process-wide player so one test's session cannot reach the next. */
    @After
    fun resetPlayer() {
        VoicePlaybackController.stop()
    }

    /** The chat list and its rows are replaced by the lock screen, and return when it lifts. */
    @Test
    fun theChatListIsReplacedByTheLockScreen() {
        app.withMountedApp { fixture, _ ->
            val row = "chat.row.${fixture.target.groupIdHex}"
            assertTrue(app.shown(row))
            assertTrue(app.reads(fixture.target.title))
            assertFalse(app.shown(MountedContractApp.LOCK_SCREEN_TAG))

            app.lock(fixture.appState)
            assertTrue(app.shown(MountedContractApp.LOCK_SCREEN_TAG))
            assertFalse("no chat row may be composed under the lock", app.shown(row))
            assertFalse(app.reads(fixture.target.title))

            app.unlock(fixture.appState)
            assertTrue(app.shown(row))
        }
    }

    /** A finished wipe's report, which is drawn by the root above the shell, is not composed under the lock. */
    @Test
    fun theWipeReportIsNotComposedUnderTheLock() {
        app.withMountedApp { fixture, _ ->
            val failure = WipeFailureItem(subject = null, reason = WIPE_FAILURE)
            val stage = WipeStageReport(WipeStage.entries.first(), completedCount = null, failures = listOf(failure))
            composeRule.runOnIdle { fixture.appState.pendingWipeReport = WipeReport(listOf(stage)) }
            app.awaitCondition { app.reads(WIPE_FAILURE) }

            app.lock(fixture.appState)
            assertFalse("the wipe report may not be composed under the lock", app.reads(WIPE_FAILURE))

            app.unlock(fixture.appState)
            app.awaitCondition { app.reads(WIPE_FAILURE) }
        }
    }

    /** The player bar, which names the chat a voice message came from, is not composed under the lock. */
    @Test
    fun thePlayerBarIsNotComposedUnderTheLock() {
        app.withMountedApp { fixture, _ ->
            composeRule.runOnIdle { startVoiceSession() }
            app.awaitCondition { app.shown(VOICE_BAR_TAG) }
            assertTrue(app.reads(VOICE_SOURCE_TITLE))

            app.lock(fixture.appState)
            assertFalse("the player bar may not be composed under the lock", app.shown(VOICE_BAR_TAG))
            assertFalse(app.reads(VOICE_SOURCE_TITLE))

            app.unlock(fixture.appState)
            assertTrue(app.shown(VOICE_BAR_TAG))
        }
    }

    /** Publishes a ready voice session owned by the fixture's account, without any platform codec work. */
    @Suppress("UNCHECKED_CAST")
    private fun startVoiceSession() {
        val field = VoicePlaybackController::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val state = field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
        val source =
            VoicePlaybackSource(ConversationTimelineTestIds.ACCOUNT_REF, "group", "message", VOICE_SOURCE_TITLE)
        state.value =
            VoicePlaybackController.PlaybackState(key = "voice", ready = true, sessionId = 10, source = source)
    }

    private companion object {
        const val VOICE_BAR_TAG = "voice-transport"
        const val VOICE_SOURCE_TITLE = "Voice source only title"
        const val WIPE_FAILURE = "wipe failure only reason"
    }
}
