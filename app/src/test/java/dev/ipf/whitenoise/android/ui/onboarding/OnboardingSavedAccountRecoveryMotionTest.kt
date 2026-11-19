package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class OnboardingSavedAccountRecoveryMotionTest {
    @get:Rule val rule = createComposeRule()
    private val account = OnboardingSavedAccountUi("account", "account-id", "Marmot User", "npub1marmot", null, true)
    private val accounts = mutableStateOf(listOf(account))
    private val enabled = mutableStateOf(true)
    private val reactivating = mutableStateOf<String?>(null)
    private val callback = mutableStateOf<(String) -> Unit>({})
    private val recovered = mutableListOf<String>()

    private fun show() {
        callback.value = { recovered += it }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    OnboardingSavedAccountActions(accounts.value, reactivating.value, enabled.value, {}, callback.value)
                }
            }
        }
        rule.mainClock.advanceTimeBy(400)
    }

    private fun open() {
        rule.onNodeWithText("Recover sign-in setup for Marmot User").performClick()
        rule.mainClock.advanceTimeBy(400)
    }

    private fun confirm() = rule.onNodeWithText("Recover setup").performClick()

    @Test fun recoveryWaitsForExitAndUsesCurrentCallbackExactlyOnce() {
        show()
        open()
        confirm()
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle {
            assertEquals(emptyList<String>(), recovered)
            callback.value = { recovered += "current:$it" }
        }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(listOf("current:account"), recovered) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(listOf("current:account"), recovered) }
    }

    @Test fun disablingDuringExitCancelsRecovery() {
        show()
        open()
        confirm()
        rule.runOnIdle { enabled.value = false }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(emptyList<String>(), recovered) }
    }

    @Test fun replacingAccountDuringExitCancelsStaleRecovery() {
        show()
        open()
        confirm()
        rule.runOnIdle { accounts.value = listOf(account.copy(accountIdHex = "replacement-id")) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(emptyList<String>(), recovered) }
    }

    @Test fun reactivationDuringExitCancelsRecovery() {
        show()
        open()
        confirm()
        rule.runOnIdle { reactivating.value = account.label }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(emptyList<String>(), recovered) }
    }

    @Test fun captureNativeRecoveryForwardAndConfirmedReverse() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        show()
        capture("forward") { rule.onNodeWithText("Recover sign-in setup for Marmot User").performClick() }
        capture("reverse") { confirm() }
        rule.runOnIdle { assertEquals(listOf("account"), recovered) }
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun capture(
        direction: String,
        action: () -> Unit,
    ) {
        val root = System.getenv("ONBOARDING_RECOVERY_CAPTURE_DIR") ?: "build/animation-frames/onboarding-recovery"
        val folder = File(root, direction).apply { mkdirs() }
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        rule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(32) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }
}
