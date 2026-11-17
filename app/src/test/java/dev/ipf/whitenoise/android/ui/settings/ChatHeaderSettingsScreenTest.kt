package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.HeaderShortcut
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chat header shortcut list: check-mark rows, the two-shortcut limit and deterministic visuals. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatHeaderSettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    /** Records the default list with every shortcut unchecked. */
    @Test fun light() = screen("light")

    /** Records the default list with dark-theme surface and text contrast. */
    @Test fun dark() = screen("dark", dark = true)

    /** Records the default list against the AMOLED surface tokens. */
    @Test fun amoled() = screen("amoled", dark = true, amoled = true)

    /** Records a full header: two checked rows and the third disabled with the limit note. */
    @Test fun full() = screen("full", shortcuts = FULL)

    /** Records the full state mirrored at twice the default text scale. */
    @Test fun fullLargeRtl() = screen("full_large_rtl", shortcuts = FULL, scale = 2f, rtl = true)

    /** Each row is a whole-row check box that reports its state and toggles its own shortcut. */
    @Test fun rowsAreCheckBoxesThatToggleTheirShortcut() {
        val toggled = mutableListOf<HeaderShortcut>()
        rule.setContent {
            WhiteNoiseTheme { ChatHeaderSettingsContent(listOf(HeaderShortcut.Search), { toggled += it }, {}) }
        }
        rule
            .onNodeWithTag("chatHeader.search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()
        rule
            .onNodeWithTag("chatHeader.read_aloud")
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        assertEquals(listOf(HeaderShortcut.Search, HeaderShortcut.ReadAloud), toggled)
    }

    /** A full header disables only the unchecked row; checked rows stay available to make room. */
    @Test fun fullHeaderDisablesOnlyTheUncheckedRow() {
        val toggled = mutableListOf<HeaderShortcut>()
        rule.setContent { WhiteNoiseTheme { ChatHeaderSettingsContent(FULL, { toggled += it }, {}) } }
        rule
            .onNodeWithTag("chatHeader.read_aloud")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        rule.onNodeWithTag("chatHeader.mute").assertIsEnabled().performClick()
        assertEquals(listOf(HeaderShortcut.MuteUnmute), toggled)
    }

    /** Without a speech engine Read aloud is not offered, unless it is still checked and holding a slot. */
    @Test fun readAloudNeedsASpeechEngineUnlessAlreadyChecked() {
        val toggled = mutableListOf<HeaderShortcut>()
        var shortcuts by mutableStateOf(listOf(HeaderShortcut.Search))
        rule.setContent {
            WhiteNoiseTheme {
                ChatHeaderSettingsContent(shortcuts, { toggled += it }, {}, readAloudAvailable = false)
            }
        }
        rule.onNodeWithTag("chatHeader.mute").assertExists()
        rule.onNodeWithTag("chatHeader.read_aloud").assertDoesNotExist()
        shortcuts = listOf(HeaderShortcut.Search, HeaderShortcut.ReadAloud)
        rule
            .onNodeWithTag("chatHeader.read_aloud")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf(HeaderShortcut.ReadAloud), toggled)
    }

    /** Mounts the stateless screen under controlled theme, direction, text scale and chosen shortcuts. */
    private fun screen(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
        shortcuts: List<HeaderShortcut> = emptyList(),
    ) {
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    ChatHeaderSettingsContent(shortcuts, {}, {})
                }
            }
        }
        rule.onRoot().captureRoboImage("src/test/snapshots/chat_header_settings_$name.png")
    }

    private companion object {
        val FULL = listOf(HeaderShortcut.Search, HeaderShortcut.MuteUnmute)
    }
}
