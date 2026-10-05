package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeRow
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeSection
import dev.ipf.whitenoise.android.ui.settings.SettingsHubSection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures production Settings rows and editor checklist across content, theme, direction and font states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class ProfileReadinessScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Every profile state keeps the editor action; optional fields never receive warning styling. */
    @Test fun readinessMatrix() {
        val empty = profileEditMetadata("", "", "", "", "", "")
        val full = profileEditMetadata("Alice", "Bio", "picture", "banner", "address", "lightning")
        val states =
            listOf(
                "loading" to ProfileReadiness.Loading,
                "unavailable" to ProfileReadiness.Unavailable,
                "empty" to profileReadiness(empty),
                "partial" to profileReadiness(empty.copy(name = "Alice")),
                "full" to profileReadiness(full),
            )
        val frame = mutableStateOf(states.first().second)
        val theme = mutableStateOf("light")
        var opened: SettingsDetail? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (theme.value == "large") 2f else 1f),
                LocalLayoutDirection provides if (theme.value == "rtl") LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(
                    darkTheme = theme.value == "dark" || theme.value == "amoled",
                    amoled = theme.value == "amoled",
                ) {
                    Surface {
                        Column(Modifier.width(if (theme.value == "narrow") 280.dp else 360.dp).padding(16.dp)) {
                            SettingsHubSection(
                                SettingsHomeGroup(
                                    SettingsHomeSection.Account,
                                    requireNotNull(SettingsHomeSection.Account.groupTitleRes),
                                    listOf(SettingsHomeRow.Profile),
                                ),
                                { opened = it },
                                {},
                                frame.value,
                            )
                            ProfileReadinessChecklist(frame.value)
                        }
                    }
                }
            }
        }
        for (style in listOf("light", "dark", "amoled", "large", "narrow", "rtl")) {
            for ((name, readiness) in states) {
                composeRule.runOnIdle {
                    theme.value = style
                    frame.value = readiness
                }
                composeRule.onNodeWithText("Profile").assertHasClickAction()
                captureDisclosure(name, readiness, style)
            }
        }
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.runOnIdle { assertEquals(SettingsDetail.Profile, opened) }
    }

    /** Verify expansion exposes optionality, then restore the compact editor state for the next frame. */
    private fun captureDisclosure(
        name: String,
        readiness: ProfileReadiness,
        style: String,
    ) {
        if (name == "partial") {
            composeRule.onRoot().captureRoboImage("src/test/snapshots/profile_readiness_collapsed_$style.png")
            composeRule.onNodeWithText("Bio · Optional").assertDoesNotExist()
        }
        if (readiness.fields.isNotEmpty()) {
            toggleChecklist()
            val lightningStatus = if (name == "full") "Added" else "Optional"
            composeRule.onNodeWithText("Lightning address · $lightningStatus").assertExists()
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/profile_readiness_${name}_$style.png")
        if (readiness.fields.isNotEmpty()) {
            toggleChecklist()
        }
    }

    /** Exercise the accessibility click action without capturing a transient pointer ripple. */
    private fun toggleChecklist() {
        composeRule.onNodeWithTag("profile.readiness.toggle").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.waitForIdle()
    }
}
