package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class EntityPickerSheetScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun adjacentChoicesAmoled() = captureChoices("amoled", dark = true, amoled = true)

    @Test fun adjacentChoicesDark() = captureChoices("dark", dark = true)

    @Test fun adjacentChoicesLight() = captureChoices("light")

    @Test
    @Config(sdk = [36], qualifiers = "ar-ldrtl-w360dp-h780dp-mdpi")
    fun filteredChoicesAmoledLargeRtl() =
        captureChoices("filtered_amoled_large_rtl", dark = true, amoled = true, filtered = true)

    private fun captureChoices(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        filtered: Boolean = false,
    ) {
        val direction = if (filtered) LayoutDirection.Rtl else LayoutDirection.Ltr
        rule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (filtered) 2f else 1f) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    WhiteNoiseEntityPickerSheet(
                        title = "Included Chats",
                        items =
                            listOf(
                                WhiteNoisePickerItem("garden", "Garden planning"),
                                WhiteNoisePickerItem("book", "Book club"),
                                WhiteNoisePickerItem("film", "Film club"),
                                WhiteNoisePickerItem("archived", "Hiking club", enabled = false),
                            ),
                        selectedIds = setOf("book"),
                        multiple = true,
                        onSelect = {},
                        onDone = {},
                        onDismiss = {},
                    )
                }
            }
        }
        if (filtered) rule.onNodeWithTag("entity.search").performClick().performTextInput("club")
        rule.waitForIdle()
        rule.onNodeWithTag("sheet.surface").captureRoboImage("src/test/snapshots/entity_picker_choices_$name.png")
    }
}
