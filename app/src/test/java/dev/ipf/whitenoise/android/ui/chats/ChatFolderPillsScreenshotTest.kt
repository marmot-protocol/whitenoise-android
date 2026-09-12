package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual pill/header geometry across themes, logical direction and font scale using local fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatFolderPillsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun light() = capture("folder_pills_light")

    @Test fun dark() = capture("folder_pills_dark", dark = true)

    @Test fun amoled() = capture("folder_pills_amoled", dark = true, amoled = true)

    @Test fun scrolled() = capture("folder_pills_scrolled", scrolled = true)

    @Test fun largeRtl() = capture("folder_pills_rtl_200", dark = true, rtl = true)

    @Test fun empty() = capture("folder_pills_empty", empty = true)

    @Test fun renamedDefault() = capture("folder_pills_renamed_default", selected = "unread")

    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h780dp-xxhdpi")
    fun highDensity() = capture("folder_pills_amoled_xxhdpi", dark = true, amoled = true)

    @OptIn(ExperimentalMaterial3Api::class)
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scrolled: Boolean = false,
        empty: Boolean = false,
        selected: String? = null,
    ) {
        val chips =
            listOf(
                ChatFolderChipModel("unread", SystemFolderKind.UNREAD, "Catch up", 125),
                ChatFolderChipModel("work", null, "A long work folder name", 2),
                ChatFolderChipModel("archive", SystemFolderKind.ARCHIVED, "", 0),
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                val behavior = TopAppBarDefaults.pinnedScrollBehavior()
                behavior.state.heightOffsetLimit = -64f
                behavior.state.contentOffset = if (scrolled) -128f else 0f
                CompositionLocalProvider(
                    LocalWhiteNoiseHeaderScroll provides behavior,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column { ChatFolderPills(if (empty) emptyList() else chips, selected, {}, {}, {}) }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
