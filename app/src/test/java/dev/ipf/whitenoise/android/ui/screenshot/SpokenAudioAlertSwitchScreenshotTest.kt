package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.notifications.SpokenAudioAlertPreferences
import dev.ipf.whitenoise.android.ui.settings.SpokenAudioAlertControl
import dev.ipf.whitenoise.android.ui.settings.SpokenAudioAlertSwitch
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The "Quiet during podcasts" switch across themes, RTL and large text, and its stored value (#1717). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class SpokenAudioAlertSwitchScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    /** Starts from the default choice. */
    @Before
    fun setUp() = clearChoice()

    /** Leaves the shared preference file as other tests expect it. */
    @After
    fun tearDown() = clearChoice()

    /** On, light theme. */
    @Test fun light() = capture("light", checked = true, dark = false)

    /** On, dark theme. */
    @Test fun dark() = capture("dark", checked = true, dark = true)

    /** Off, AMOLED theme. */
    @Test fun amoledOff() = capture("amoled_off", checked = false, dark = true, amoled = true)

    /** On, right to left with doubled text. */
    @Test fun rtlLargeText() = capture("rtl_large", checked = true, dark = true, rtl = true, large = true)

    /** The control starts on and a tap stores the new value. */
    @Test
    fun theSwitchStartsOnAndATapIsStored() {
        composeRule.setContent { WhiteNoiseTheme { Column { SpokenAudioAlertControl() } } }
        val row = composeRule.onNodeWithTag("notification-quiet-spoken-audio")
        row.assertIsOn()
        row.performClick()
        row.assertIsOff()
        assertFalse(SpokenAudioAlertPreferences.enabled(context))
    }

    /** Renders the switch row in one configuration and records it. */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        checked: Boolean,
        dark: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
        large: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(LocalDensity.current.density, if (large) 2f else 1f),
                ) {
                    Surface(modifier = Modifier.width(360.dp).testTag("spoken-audio-switch")) {
                        Column { SpokenAudioAlertSwitch(checked = checked, onCheckedChange = {}) }
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag("spoken-audio-switch")
            .captureRoboImage("src/test/snapshots/notification_quiet_spoken_audio_$name.png")
    }

    /** Removes the stored choice so the default applies. */
    private fun clearChoice() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .remove(SpokenAudioAlertPreferences.KEY)
            .commit()
    }
}
