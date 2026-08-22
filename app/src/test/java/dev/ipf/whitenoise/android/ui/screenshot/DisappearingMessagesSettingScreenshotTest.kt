package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DisappearingMessagesSettingScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editableAdminRowInvokesTheExistingActionOnce() {
        var clicks = 0
        render(
            darkTheme = false,
            enabled = true,
            supportingText = EDITABLE,
            onClick = { clicks += 1 },
        )

        composeRule
            .onNode(hasText(TITLE) and hasClickAction())
            .assertHasClickAction()
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun readOnlyRowIsAReasonedDisabledButton() {
        var clicks = 0
        render(
            darkTheme = false,
            enabled = false,
            supportingText = READ_ONLY,
            disabledReason = READ_ONLY,
            onClick = { clicks += 1 },
        )

        val row = composeRule.onNode(hasText(TITLE) and hasClickAction())
        row
            .assertHasClickAction()
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, READ_ONLY))
            .performClick()
        assertEquals(0, clicks)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/disappearing_setting_read_only_light.png")
    }

    @Test
    fun inProgressRowIsDistinctAtLargeFontInDarkRtl() {
        render(
            darkTheme = true,
            enabled = false,
            supportingText = EDITABLE,
            inProgress = true,
            rtl = true,
            fontScale = 1.6f,
        )
        composeRule
            .onNode(hasText(TITLE) and hasClickAction())
            .assertHasClickAction()
            .assertIsNotEnabled()
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/disappearing_setting_progress_dark_large_rtl.png")
    }

    private fun render(
        darkTheme: Boolean,
        enabled: Boolean,
        supportingText: String,
        disabledReason: String? = null,
        inProgress: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        onClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        Column {
                            SettingsActionRow(
                                icon = Icons.Default.Schedule,
                                title = TITLE,
                                value = "1 day",
                                supportingText = supportingText,
                                enabled = enabled,
                                inProgress = inProgress,
                                disabledReason = disabledReason,
                                onClick = onClick,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TAG = "disappearing-message-setting"
        const val TITLE = "Disappearing messages"
        const val READ_ONLY = "Only admins can change this"
        const val EDITABLE = "Tap to set how long new messages last"
    }
}
