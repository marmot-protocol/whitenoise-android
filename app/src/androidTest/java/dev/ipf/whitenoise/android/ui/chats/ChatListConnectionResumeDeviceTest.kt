package dev.ipf.whitenoise.android.ui.chats

import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.state.ChatListConnectionPhase
import dev.ipf.whitenoise.android.state.ChatListConnectionState
import dev.ipf.whitenoise.android.state.beginReadinessRefresh
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Actual drawn frames of the retained production banner state machine, rather than committed SideEffects. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ChatListConnectionResumeDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Healthy retained foreground edges remain silent on every actual draw, even while validation is pending. */
    @Test
    fun healthyRetainedStopStartDrawsZeroConnectingFrames() {
        val fixture = mountFixture()
        val activity = composeRule.activity
        val process = Process.myPid()
        val validationCount = fixture.foregroundValidations
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.frames.clear()
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil { fixture.foregroundValidations > validationCount && fixture.frames.isNotEmpty() }

        assertSame(activity, composeRule.activity)
        assertEquals(process, Process.myPid())
        assertEquals(ChatListConnectionPhase.Validating, fixture.connection.phase)
        assertTrue(fixture.frames.all { it == ConnectivityBannerState.Hidden })
    }

    /** A new Activity paints the retained healthy readiness without inheriting or manufacturing a recovery flash. */
    @Test
    fun healthySameProcessActivityRecreationDrawsZeroConnectingFrames() {
        val fixture = mountFixture()
        val original = composeRule.activity
        val process = Process.myPid()
        fixture.frames.clear()
        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent { fixture.Content() } }
        composeRule.waitUntil { fixture.frames.isNotEmpty() }

        assertNotSame(original, composeRule.activity)
        assertEquals(process, Process.myPid())
        assertTrue(fixture.frames.all { it == ConnectivityBannerState.Hidden })
    }

    /** The frame oracle must fail the healthy contract when the production presentation is genuinely Attempting. */
    @Test
    fun renderedFrameNegativeControlDetectsConnecting() {
        val fixture = mountFixture()
        composeRule.runOnUiThread {
            fixture.frames.clear()
            fixture.connection = fixture.connection.beginReadinessRefresh(presentAttempt = true)
        }
        composeRule.waitUntil { ConnectivityBannerState.Connecting in fixture.frames }
        assertTrue(fixture.frames.any { it != ConnectivityBannerState.Hidden })
    }

    /** Recovery flashes only after a genuine offline edge and settles using the production timer. */
    @Test
    fun actualOfflineRecoveryDrawsOfflineConnectingConnectedThenHidden() {
        val fixture = mountFixture()
        composeRule.runOnUiThread { fixture.validatedInternet = false }
        composeRule.waitUntil { ConnectivityBannerState.Offline in fixture.frames }
        composeRule.runOnUiThread {
            fixture.connection = fixture.connection.beginReadinessRefresh(presentAttempt = true)
            fixture.validatedInternet = true
        }
        composeRule.waitUntil { ConnectivityBannerState.Connecting in fixture.frames }
        composeRule.runOnUiThread {
            fixture.connection = fixture.connection.copy(phase = ChatListConnectionPhase.Ready)
        }
        composeRule.waitUntil { ConnectivityBannerState.JustConnected in fixture.frames }
        composeRule.waitUntil { fixture.frames.lastOrNull() == ConnectivityBannerState.Hidden }
        val distinct = fixture.frames.distinct()
        val offline = distinct.indexOf(ConnectivityBannerState.Offline)
        val connecting = distinct.indexOf(ConnectivityBannerState.Connecting)
        val connected = distinct.indexOf(ConnectivityBannerState.JustConnected)
        assertTrue(offline < connecting)
        assertTrue(connecting < connected)
    }

    /** Installs the same retained presentation, foreground refresh effect and indicator used by ChatsScreen. */
    private fun mountFixture(): BannerFixture {
        lateinit var fixture: BannerFixture
        composeRule.runOnUiThread { fixture = BannerFixture() }
        composeRule.setContent { fixture.Content() }
        composeRule.waitUntil { fixture.frames.isNotEmpty() }
        return fixture
    }

    /** Controlled readiness inputs; every output frame is sampled only after drawContent. */
    private class BannerFixture {
        var connection by
            mutableStateOf(
                ChatListConnectionState(
                    accountRef = "fixture",
                    runtimeGeneration = 9,
                    bindEpoch = 2L,
                    sessionAttemptId = 3L,
                    evidenceEpoch = 4L,
                    phase = ChatListConnectionPhase.Ready,
                ),
            )
        var validatedInternet by mutableStateOf(true)
        var foregroundValidations = 0
        val frames = CopyOnWriteArrayList<ConnectivityBannerState>()

        /** Drives production presentation and samples the value whose indicator was painted in this traversal. */
        @Composable
        @Suppress("FunctionNaming")
        fun Content() {
            val foregroundEpoch = rememberConnectivityForegroundEpoch()
            ConnectivityEdgeRefreshEffects(
                effectOwner = this,
                activeAccountRef = "fixture",
                runtimeGeneration = 9,
                hasValidatedInternet = validatedInternet,
                relaysConnected = true,
                foregroundEpoch = foregroundEpoch,
                revalidateConnectionReadiness = {
                    foregroundValidations += 1
                    connection = connection.beginReadinessRefresh(presentAttempt = false)
                },
            )
            val target = connectivityBannerTarget(validatedInternet, "fixture", 9, connection)
            val painted = rememberConnectivityBannerPresentation(this, "fixture", 9, target).displayed
            WhiteNoiseTheme {
                Box(
                    Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        frames.add(painted)
                    },
                ) { ChatListInlineConnectivityIndicator(painted) }
            }
        }
    }
}
