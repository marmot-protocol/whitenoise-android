package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.composer.BlockedDmComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.DisbandedGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.FrozenGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.RemovedMemberComposerNotice
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** All four composer replacement notices stacked, so padding and colour roles are compared in one frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h800dp-mdpi")
class ComposerNoticesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The default AMOLED theme keeps the outline and the error tone. */
    @Test
    fun composerNoticesAmoled() = capture("composer_notices_amoled", darkTheme = true, amoled = true)

    /** Light theme. */
    @Test
    fun composerNoticesLight() = capture("composer_notices_light", darkTheme = false)

    /** Large text in RTL keeps every notice readable. */
    @Test
    fun composerNoticesLargeTextRtl() {
        capture("composer_notices_large_rtl", darkTheme = false, fontScale = 2f, rtl = true)
    }

    private fun capture(
        name: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Column {
                        BlockedDmComposerNotice(unblockInFlight = false, onUnblock = {})
                        DisbandedGroupComposerNotice(disbanded = true)
                        FrozenGroupComposerNotice()
                        RemovedMemberComposerNotice()
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
