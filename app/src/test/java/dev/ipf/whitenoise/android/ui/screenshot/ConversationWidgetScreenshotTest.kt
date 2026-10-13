package dev.ipf.whitenoise.android.ui.screenshot

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.widget.ConversationWidgetChoice
import dev.ipf.whitenoise.android.widget.ConversationWidgetConfigurationContent
import dev.ipf.whitenoise.android.widget.ConversationWidgetRow
import dev.ipf.whitenoise.android.widget.WidgetAccountPresentation
import dev.ipf.whitenoise.android.widget.widgetProfileTitle
import org.junit.Assert.assertEquals
import dev.ipf.whitenoise.android.widget.conversationWidgetViews
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h1000dp-mdpi")
class ConversationWidgetScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val profiles = listOf(
        WidgetAccountPresentation("account-a", "seed-a", "Curious Penguin", picture = portrait(), active = true),
        WidgetAccountPresentation("account-b", "seed-b", "Loyal Otter"),
    )

    @Test fun configurationDefaults() = configuration("widget_configuration_defaults.png")

    @Test fun configurationDark() = configuration("widget_configuration_dark.png", Appearance(dark = true))

    @Test fun configurationAmoled() =
        configuration(
            "widget_configuration_amoled.png",
            Appearance(dark = true, amoled = true),
        )

    @Test fun configurationLargeRtl() =
        configuration(
            "widget_configuration_large_rtl.png",
            Appearance(rtl = true, scale = 2f),
        )

    @Test fun configurationUnavailable() = configuration("widget_configuration_unavailable.png", accounts = emptyList())

    @Test fun activeAccountDefaultsToItsStableReference() {
        val saved = mutableListOf<ConversationWidgetChoice>()
        renderConfiguration(accounts = profiles.reversed(), save = { saved.add(it) })
        composeRule.onNodeWithText("Curious Penguin").assertIsDisplayed()
        composeRule.onNodeWithText("account-a").assertDoesNotExist()
        composeRule.onNodeWithTag("widget.save").performClick()
        assertEquals(listOf(ConversationWidgetChoice("account-a")), saved)
    }

    @Test fun selectionSurvivesProfileRefreshAndSavesFilterChoices() {
        val accounts = mutableStateOf(profiles)
        val saved = mutableListOf<ConversationWidgetChoice>()
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationWidgetConfigurationContent(accounts.value, {}, { saved.add(it) })
            }
        }
        composeRule.onNodeWithTag("widget.profile.account-b").performClick()
        composeRule.runOnIdle { accounts.value = profiles.map { it.copy(name = "Updated ${it.name}") } }
        composeRule.onNodeWithText("Updated Loyal Otter").assertIsDisplayed()
        composeRule.onNodeWithText("All accounts").performClick()
        composeRule.onNodeWithText("Unread only").performClick()
        composeRule.onNodeWithTag("widget.save").performClick()
        assertEquals(listOf(ConversationWidgetChoice("account-b", true, false, true)), saved)
    }

    @Test fun disappearingSelectedAccountRequiresAnotherChoice() {
        val accounts = mutableStateOf(profiles)
        composeRule.setContent {
            WhiteNoiseTheme { ConversationWidgetConfigurationContent(accounts.value, {}, {}) }
        }
        composeRule.onNodeWithTag("widget.profile.account-b").performClick()
        composeRule.runOnIdle { accounts.value = profiles.take(1) }
        composeRule.onNodeWithTag("widget.save").assertIsNotEnabled()
        composeRule.onNodeWithTag("widget.profile.account-a").performClick()
        composeRule.onNodeWithTag("widget.save").performClick()
    }

    @Test fun unavailableConfigurationOffersTheAppWithoutSaving() {
        var opened = 0
        val saved = mutableListOf<ConversationWidgetChoice>()
        renderConfiguration(accounts = emptyList(), open = { opened++ }, save = { saved.add(it) })
        composeRule.onNodeWithTag("widget.save").assertDoesNotExist()
        composeRule.onNodeWithText("Open White Noise").performClick()
        assertEquals(1, opened)
        assertEquals(emptyList<ConversationWidgetChoice>(), saved)
    }

    @Test fun unresolvedIdentitiesUseProfileTitles() {
        assertEquals("Profile 1", widgetProfileTitle("npub1abcdef…", "Profile", 0))
        assertEquals("Profile 2", widgetProfileTitle("a".repeat(64), "Profile", 1))
        assertEquals("Curious Penguin", widgetProfileTitle("Curious Penguin", "Profile", 0))
    }

    private data class Appearance(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val rtl: Boolean = false,
        val scale: Float = 1f,
    )

    private fun configuration(
        name: String,
        appearance: Appearance = Appearance(),
        accounts: List<WidgetAccountPresentation> = profiles,
    ) {
        renderConfiguration(accounts, appearance)
        capture(name)
    }

    private fun renderConfiguration(
        accounts: List<WidgetAccountPresentation> = profiles,
        appearance: Appearance = Appearance(),
        open: () -> Unit = {},
        save: (ConversationWidgetChoice) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (appearance.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(
                    darkTheme = appearance.dark,
                    amoled = appearance.amoled,
                    fontScale = appearance.scale,
                ) {
                    Box(Modifier.size(360.dp, 700.dp).testTag("widget-fixture")) {
                        ConversationWidgetConfigurationContent(accounts, open, save)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun portrait(): ImageBitmap = ImageBitmap(48, 48).also {
        Canvas(it).drawCircle(Offset(24f, 24f), 24f, Paint().apply { color = Color(0xFF006A6A) })
        Canvas(it).drawCircle(Offset(24f, 18f), 8f, Paint().apply { color = Color.White })
        Canvas(it).drawCircle(Offset(24f, 42f), 14f, Paint().apply { color = Color.White })
    }

    @Test fun redactedNarrow() =
        views(
            "widget_redacted_narrow.png",
            listOf(
                ConversationWidgetRow("Alice", "first", "Family", true),
                ConversationWidgetRow("Alice", "second", "Friends", false),
            ),
            ConversationWidgetChoice("Alice"),
        )

    @Test fun unavailableRtl() = views("widget_unavailable_rtl.png", emptyList(), null, rtl = true)

    private fun views(
        name: String,
        rows: List<ConversationWidgetRow>,
        choice: ConversationWidgetChoice?,
        rtl: Boolean = false,
    ) {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        composeRule.setContent {
            Box(Modifier.size(220.dp, 260.dp).testTag("widget-fixture")) {
                AndroidView(factory = {
                    conversationWidgetViews(context, 7, rows, choice)
                        .apply(context, null)
                        .apply { layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
                })
            }
        }
        capture(name)
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("widget-fixture").captureRoboImage("src/test/snapshots/$name")
    }
}
