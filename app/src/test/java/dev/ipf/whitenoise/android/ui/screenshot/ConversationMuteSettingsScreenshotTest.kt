package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.group.ConversationMuteSettingsSwitch
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/** Mute settings copy stays visible and readable across normal, active, and timed states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ConversationMuteSettingsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    /** Fixes locale and time zone so timed-mute labels render the same in every CI environment. */
    @Before
    fun useDeterministicClockLabels() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Restores process-wide formatting defaults before another screenshot test runs. */
    @After
    fun restoreClockLabels() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    /** The unmuted subtitle promises the direct-mention exception before mute is selected. */
    @Test
    fun unmutedLightExplainsTheMentionException() {
        capture("conversation_mute_unmuted_light", isMuted = false)
        composeRule
            .onNodeWithText(
                "Silence ordinary messages. Direct mentions can still notify.",
                useUnmergedTree = true,
            ).assertExists()
    }

    /** The active durable-mute row names the only message type that can still notify. */
    @Test
    fun mutedDarkShowsOnlyDirectMentions() {
        capture("conversation_mute_muted_dark", isMuted = true, darkTheme = true)
        composeRule.onNodeWithText("Only direct mentions while muted.", useUnmergedTree = true).assertExists()
    }

    /** The timed-mute explanation remains visible at 200% text and in RTL. */
    @Test
    fun timedMuteRtlLargeTextKeepsTheExceptionVisible() {
        capture(
            "conversation_mute_timed_rtl_large",
            isMuted = true,
            expiryMillis = 1_893_456_000_000L,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
        )
        composeRule
            .onNodeWithText("direct mentions can notify.", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    /** Render the same settings row used by the production screen at a stable width. */
    private fun capture(
        name: String,
        isMuted: Boolean,
        expiryMillis: Long? = null,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(360.dp).testTag("conversation-mute-settings")) {
                        SettingsGroup {
                            row("mute") { rowContext ->
                                ConversationMuteSettingsSwitch(
                                    rowContext = rowContext,
                                    isMuted = isMuted,
                                    muteExpiryMillis = expiryMillis,
                                    muteCommandPending = false,
                                    onToggleMute = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("conversation-mute-settings").captureRoboImage("src/test/snapshots/$name.png")
    }
}
