package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The stored audit logs group: a plain export action above the destructive delete action. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AuditLogExportScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme group. */
    @Test
    fun auditLogControlsLight() {
        render(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/audit_log_export_light.png")
    }

    /** Dark theme group. */
    @Test
    fun auditLogControlsDark() {
        render(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/audit_log_export_dark.png")
    }

    /** Renders the two stored-log actions as one settings group. */
    private fun render(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface {
                    Column(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        SettingsGroup {
                            row("export") { context ->
                                SettingsAction(
                                    context = context,
                                    title = stringResource(R.string.export_audit_logs),
                                    onClick = {},
                                    subtitle = stringResource(R.string.export_audit_logs_subtitle),
                                )
                            }
                            row("delete") { context ->
                                SettingsAction(
                                    context = context,
                                    title = stringResource(R.string.delete_audit_logs),
                                    onClick = {},
                                    subtitle = stringResource(R.string.delete_audit_logs_subtitle),
                                    destructive = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "audit-log-export"
    }
}
