package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.BackupRestorePhaseFfi
import dev.ipf.whitenoise.android.state.HistoryBackupAction
import dev.ipf.whitenoise.android.state.HistoryBackupProblem
import dev.ipf.whitenoise.android.state.HistoryBackupStatus
import dev.ipf.whitenoise.android.state.HistoryBackupsState
import dev.ipf.whitenoise.android.state.HistoryRestoreProgress
import dev.ipf.whitenoise.android.state.RecoveryWordsSetup
import dev.ipf.whitenoise.android.ui.settings.HistoryBackupsActions
import dev.ipf.whitenoise.android.ui.settings.HistoryBackupsContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class HistoryBackupsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offLight() = capture("history_backups_off_light", HistoryBackupsState(status = HistoryBackupStatus.Off))

    @Test fun onThisDeviceDark() =
        capture(
            "history_backups_on_dark",
            HistoryBackupsState(status = HistoryBackupStatus.On(thisDeviceWrites = true)),
            dark = true,
        )

    @Test fun onAnotherDeviceWithRestoreRunning() =
        capture(
            "history_backups_restore_running_light",
            HistoryBackupsState(
                status = HistoryBackupStatus.On(thisDeviceWrites = false),
                restore = progress(BackupRestorePhaseFfi.RESTORING, messages = 128),
            ),
            scrollTo = "history_backups.cancel_restore",
        )

    @Test fun restorePartialWithInvalidWordsProblem() =
        capture(
            "history_backups_restore_partial_dark",
            HistoryBackupsState(
                status = HistoryBackupStatus.Off,
                restore = progress(BackupRestorePhaseFfi.PARTIAL, messages = 42),
                problem = HistoryBackupProblem.InvalidRestoreWords,
            ),
            dark = true,
            scrollTo = "history_backups.restore",
        )

    @Test fun unavailableLight() =
        capture("history_backups_unavailable_light", HistoryBackupsState(status = HistoryBackupStatus.Unavailable))

    @Test fun wordsLight() = capture("history_backups_words_light", HistoryBackupsState(status = HistoryBackupStatus.Off, setup = setup()))

    @Test fun confirmWrongWordsDark() =
        capture(
            "history_backups_confirm_wrong_dark",
            HistoryBackupsState(
                status = HistoryBackupStatus.Off,
                setup = setup().confirming(),
                problem = HistoryBackupProblem.WrongWords,
            ),
            dark = true,
        )

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun wordsLargeRtl() =
        capture("history_backups_words_rtl_large", HistoryBackupsState(status = HistoryBackupStatus.Off, setup = setup()), rtl = true)

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun statusLargeRtl() =
        capture(
            "history_backups_on_rtl_large",
            HistoryBackupsState(status = HistoryBackupStatus.On(thisDeviceWrites = true), running = HistoryBackupAction.TurnOff),
            rtl = true,
        )

    /** Typed restore words are handed over once, then cleared, and never come back from saved state. */
    @Test fun restoreWordsAreClearedAndNeverRestoredFromSavedState() {
        val restored = mutableListOf<String>()
        val tester = StateRestorationTester(compose)
        tester.setContent {
            WhiteNoiseTheme {
                HistoryBackupsContent(
                    HistoryBackupsState(status = HistoryBackupStatus.Off),
                    HistoryBackupsActions(onRestore = { restored += it }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("history_backups.list").performScrollToNode(hasTestTag("history_backups.restore"))
        compose.onNodeWithTag("history_backups.restore_words").performTextInput("alpha beta")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history_backups.list").performScrollToNode(hasTestTag("history_backups.restore"))
        assertFalse(compose.onNodeWithTag("history_backups.restore_words").fetchSemanticsNode().config.toString().contains("alpha"))
        compose.onNodeWithTag("history_backups.restore_words").performTextInput("gamma delta")
        compose.onNodeWithTag("history_backups.restore").performClick()
        assertEquals(listOf("gamma delta"), restored)
        assertFalse(compose.onNodeWithTag("history_backups.restore_words").fetchSemanticsNode().config.toString().contains("gamma"))
    }

    /** Renders [state] in the requested theme and direction, scrolls to [scrollTo], and records [name]. */
    private fun capture(
        name: String,
        state: HistoryBackupsState,
        dark: Boolean = false,
        rtl: Boolean = false,
        scrollTo: String? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    HistoryBackupsContent(state, HistoryBackupsActions(), onBack = {})
                }
            }
        }
        if (scrollTo != null) {
            compose.onNodeWithTag("history_backups.list").performScrollToNode(hasTestTag(scrollTo))
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun setup() =
        RecoveryWordsSetup(
            words = listOf("orbit", "maple", "canyon", "velvet", "harbor", "pencil", "quartz", "meadow", "lantern", "ripple", "saddle", "thunder"),
            confirmPositions = listOf(3, 9),
        )

    private fun progress(
        phase: BackupRestorePhaseFfi,
        messages: Long,
    ) = HistoryRestoreProgress(
        phase = phase,
        operation = if (phase == BackupRestorePhaseFfi.RESTORING) "0a" else null,
        importedMessages = messages,
        failedParts = if (phase == BackupRestorePhaseFfi.PARTIAL) 1 else 0,
        revision = 1,
    )
}
