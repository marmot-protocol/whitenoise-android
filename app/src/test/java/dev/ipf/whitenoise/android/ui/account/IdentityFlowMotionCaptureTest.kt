package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Opt-in actual-owner frame export; no identity operation, provider or network success is asserted. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class IdentityFlowMotionCaptureTest {
    @get:Rule val composeRule = createComposeRule()

    private var previousAnimatorScale: String? = null

    @Before fun enableMotion() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        previousAnimatorScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @After fun restoreMotion() {
        if (System.getenv("ANIMATION_CAPTURE_FRAMES") != "1") return
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousAnimatorScale)
    }

    /** Actual header owns selector removal, measured origin, dialog presentation and Back. */
    @Test fun captureIdentityHandoffAndBackFrames() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val app = render {}
        composeRule.onNodeWithTag("chats.switchProfile").performClick()
        composeRule.onNodeWithTag("profile_switcher.add_profile").assertExists()
        captureFrames("identity-flow/handoff") {
            composeRule.onNodeWithTag("profile_switcher.add_profile").performClick()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").assertExists()
        assertEquals("a", app.activeAccountRef)
        captureFrames("identity-flow/back") {
            composeRule.runOnIdle {
                (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
            }
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").assertDoesNotExist()
        composeRule.onNodeWithTag("chats.switchProfile").assertExists()
        assertEquals("a", app.activeAccountRef)
    }

    private fun captureFrames(
        name: String,
        action: () -> Unit,
    ) {
        check(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        composeRule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(48) { frame ->
            composeRule.mainClock.advanceTimeBy(16L)
            composeRule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }

    /** Composes the surface under test with the given fixture. */
    private fun render(onSettings: () -> Unit): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("identity-flow-motion-capture-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                listOf(
                    AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true, false),
                    AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true, false),
                ),
                "a",
                profileReader = { null },
                profileRefreshRequest = {},
                preferences = prefs,
            )
        app.updateQuickAccountSwitching(false)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    ChatListTopBar(
                        app,
                        false,
                        "",
                        remember { FocusRequester() },
                        {},
                        {},
                        {},
                        {},
                        onSettings,
                        {},
                        selfUpdateEnabled = false,
                    )
                }
            }
        }
        return app
    }
}
