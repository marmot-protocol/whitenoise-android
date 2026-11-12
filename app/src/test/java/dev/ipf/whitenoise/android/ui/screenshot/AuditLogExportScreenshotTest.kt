package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AuditLogFileFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.AuditLogExportDestinationDialog
import dev.ipf.whitenoise.android.ui.settings.AuditLogPickerDialog
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

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

    /** The destination choice the reader sees after acknowledging the export. */
    @Test
    fun auditLogExportDestinationLight() {
        renderDestination(darkTheme = false)
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/audit_log_export_destination_light.png")
    }

    /** The same choice in dark theme. */
    @Test
    fun auditLogExportDestinationDark() {
        renderDestination(darkTheme = true)
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/audit_log_export_destination_dark.png")
    }

    /** The picker shows file metadata before any files are selected. */
    @Test
    fun auditLogPickerLight() =
        withUtc {
            renderPicker(darkTheme = false)
            composeRule.onNodeWithText("Export").assertIsNotEnabled()
            composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/audit_log_picker_light.png")
        }

    /** Selecting two files forwards exactly their paths and remains usable at larger text sizes. */
    @Test
    fun auditLogPickerDarkLarge() =
        withUtc {
            var selected: Set<String> = emptySet()
            renderPicker(darkTheme = true, fontScale = 1.5f) { selected = it }
            composeRule.onNodeWithText("audit-segment-03-v5.jsonl").performClick()
            composeRule.onNodeWithText("audit-segment-02-v5.jsonl").performClick()
            composeRule.mainClock.advanceTimeBy(250)
            composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/audit_log_picker_dark_large.png")
            composeRule.onNodeWithText("Export").performClick()
            assertEquals(setOf("/synthetic/3", "/synthetic/2"), selected)
        }

    /** Renders the picker with synthetic segment names, sizes and timestamps. */
    private fun renderPicker(
        darkTheme: Boolean,
        fontScale: Float = 1f,
        onExport: (Set<String>) -> Unit = {},
    ) {
        val files =
            (3 downTo 1).map { number ->
                AuditLogFileFfi(
                    accountRef = "synthetic",
                    path = "/synthetic/$number",
                    fileName = "audit-segment-0$number-v5.jsonl",
                    sizeBytes = 880_000uL,
                    modifiedAtMs = 1_762_856_400_000uL + number.toULong() * 3_600_000uL,
                )
            }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = darkTheme, fontScale = fontScale) {
                Surface {
                    Column(modifier = Modifier.width(360.dp)) {
                        AuditLogPickerDialog(
                            Result.success(files),
                            false,
                            onDismiss = {},
                            onExport = { paths -> onExport(paths) },
                            onRetry = {},
                        )
                    }
                }
            }
        }
    }

    /** Keeps the timestamp labels reproducible across screenshot runners. */
    private fun withUtc(block: () -> Unit) {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /**
     * Renders the destination dialog. It is captured through [isDialog] rather than a tag on a
     * parent, because Material 3 renders a dialog in its own window a parent tag cannot reach.
     */
    private fun renderDestination(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                // The host stays sized: an unconstrained dialog host renders into an unbounded
                // window, and capturing that allocates a bitmap large enough to exhaust the heap.
                Surface {
                    Column(modifier = Modifier.width(360.dp)) {
                        AuditLogExportDestinationDialog(onDismiss = {}, onSave = {}, onShare = {})
                    }
                }
            }
        }
    }

    /** Renders the stored-log actions as one settings group. */
    private fun render(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface {
                    Column(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        SettingsGroup {
                            row("select_audit_files") { context ->
                                SettingsAction(
                                    context = context,
                                    title = stringResource(R.string.choose_audit_files),
                                    onClick = {},
                                    subtitle = stringResource(R.string.choose_audit_files_subtitle),
                                )
                            }
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
