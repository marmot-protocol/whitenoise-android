package dev.ipf.whitenoise.android.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.replaceActiveAccountForTest
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class StartingAccountIndicatorScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** Captures the initiating-account disclosure in the light theme. */
    @Test fun identityDisclosureLight() = capture("light")

    /** Captures the initiating-account disclosure in the dark theme. */
    @Test fun identityDisclosureDark() = capture("dark", dark = true)

    /** Captures the initiating-account disclosure on the AMOLED surface. */
    @Test fun identityDisclosureAmoled() = capture("amoled", dark = true, amoled = true)

    /** Captures the initiating-account disclosure with large text and RTL layout. */
    @Test fun identityDisclosureLargeRtl() = capture("large_rtl", rtl = true)

    /** Rejects disclosure state after account, runtime or account-cleanup ownership changes. */
    @Test fun capturedOwnerCannotDisplayAnotherAccountOrRuntime() {
        val state =
            emptyAppState(
                accounts = listOf(testAccount("Alice", "a".repeat(64)), testAccount("Bob", "b".repeat(64))),
                activeAccountRef = "Alice",
            )
        val runtime = state.runtimeGeneration
        assertEquals("a".repeat(64), startingAccount(state, "Alice", runtime)?.accountIdHex)
        assertNull(startingAccount(state, "Alice", runtime + 1))
        state.replaceActiveAccountForTest("Bob")
        assertNull(startingAccount(state, "Alice", runtime))
        assertEquals("b".repeat(64), startingAccount(state, "Bob", state.runtimeGeneration)?.accountIdHex)
        state.signOutInProgress = true
        assertNull(startingAccount(state, "Bob", state.runtimeGeneration))
        state.signOutInProgress = false
        state.wipeInProgress = true
        assertNull(startingAccount(state, "Bob", state.runtimeGeneration))
    }

    /** Renders the disclosure in the requested theme and records its screenshot. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    Surface {
                        Box {
                            StartingAccountIndicator(StartingAccount("a".repeat(64), "Carol", null, "npub1carol…test"))
                        }
                    }
                }
            }
        }
        rule
            .onNodeWithTag("creation.starting_account")
            .assertContentDescriptionEquals("Starting as, Carol")
            .performClick()
        rule.onNodeWithText("Starting as Carol").assertExists()
        rule.onNodeWithText("npub1carol…test", substring = true).assertExists()
        rule
            .onNodeWithTag("creation.starting_account.disclosure")
            .captureRoboImage("src/test/snapshots/starting_account_$name.png")
    }
}
