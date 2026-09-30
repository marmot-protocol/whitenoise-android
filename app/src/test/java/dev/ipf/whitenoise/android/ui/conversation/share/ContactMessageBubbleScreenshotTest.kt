package dev.ipf.whitenoise.android.ui.conversation.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Contact actions stay readable before download, during progress and after failure. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ContactMessageBubbleScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Normal card. */
    @Test fun readyLight() = capture("contact_card_ready_light")

    /** Dark card. */
    @Test fun readyDark() = capture("contact_card_ready_dark", dark = true)

    /** AMOLED card. */
    @Test fun readyAmoled() = capture("contact_card_ready_amoled", dark = true, amoled = true)

    /** Loading retains caption. */
    @Test fun loading() = capture("contact_card_loading", busy = true)

    /** Recoverable error retains actions. */
    @Test fun failed() = capture("contact_card_failed", error = "Could not load")

    /** Large RTL controls wrap without hiding Save VCF. */
    @Test
    @Config(sdk = [36], qualifiers = "ar-ldrtl-w320dp-h780dp-mdpi")
    fun largeRtl() = capture("contact_card_rtl_large", rtl = true, large = true)

    /** Captures synthetic contact data only. */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        busy: Boolean = false,
        error: String? = null,
        rtl: Boolean = false,
        large: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (large) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column {
                            ContactMessageBubble(
                                contact = SharedContact("Ada Example", "+123456789", "ada@example.org"),
                                busy = busy,
                                error = error,
                                onView = {},
                                onAdd = {},
                                onSave = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
