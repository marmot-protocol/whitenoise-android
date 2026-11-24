package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Recent chats and profiles on the blank search page (#4004), across themes, large text and RTL. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GlobalSearchRecentScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Light. */
    @Test fun light() = capture("light")

    /** Dark. */
    @Test fun dark() = capture("dark", dark = true)

    /** AMOLED. */
    @Test fun amoled() = capture("amoled", dark = true, amoled = true)

    /** Right-to-left at double font scale on a narrow screen. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun largeRtl() = capture("large_rtl", largeRtl = true)

    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface(Modifier.width(if (largeRtl) 320.dp else 360.dp).testTag("recents")) {
                        GlobalSearchRecentSection(
                            entries = DESTINATIONS.map { it.first },
                            onOpen = {},
                            onRemove = {},
                            onClear = {},
                        ) { entry ->
                            val title = DESTINATIONS.first { it.first == entry }.second
                            Avatar(title, entry.key, GlobalSearchRecentAvatarSize)
                            GlobalSearchRecentTitle(title)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recents").captureRoboImage("src/test/snapshots/global_search_recent_$name.png")
    }

    private companion object {
        val DESTINATIONS =
            listOf(
                GlobalSearchRecent.Chat("a1".repeat(16)) to "Weekend hikers",
                GlobalSearchRecent.Profile("npub1alice") to "Alice",
                GlobalSearchRecent.Chat("b2".repeat(16)) to "Bob",
                GlobalSearchRecent.Chat("c3".repeat(16)) to "Neighbourhood garden committee",
                GlobalSearchRecent.Profile("npub1dana") to "Dana",
            )
    }
}
