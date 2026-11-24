package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.BackupRestorePhaseFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.HistoryBackupAction
import dev.ipf.whitenoise.android.state.HistoryBackupProblem
import dev.ipf.whitenoise.android.state.HistoryBackupStatus
import dev.ipf.whitenoise.android.state.HistoryBackupsState
import dev.ipf.whitenoise.android.state.HistoryRestoreProgress
import dev.ipf.whitenoise.android.state.RecoveryWordsSetup
import dev.ipf.whitenoise.android.ui.group.ChatHistoryBackupHintCard
import dev.ipf.whitenoise.android.ui.settings.HistoryBackupsActions
import dev.ipf.whitenoise.android.ui.settings.HistoryBackupsContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** Captures the off state and its setup entry point. */
    @Test fun offLight() = capture("history_backups_off_light", HistoryBackupsState(status = HistoryBackupStatus.Off))

    /** Captures the writer status in dark theme. */
    @Test fun onThisDeviceDark() =
        capture(
            "history_backups_on_dark",
            HistoryBackupsState(status = HistoryBackupStatus.On(thisDeviceWrites = true)),
            dark = true,
        )

    /** Shows restore progress alongside the reader status. */
    @Test fun onAnotherDeviceWithRestoreRunning() =
        capture(
            "history_backups_restore_running_light",
            HistoryBackupsState(
                status = HistoryBackupStatus.On(thisDeviceWrites = false),
                restore = progress(BackupRestorePhaseFfi.RESTORING, messages = 128),
            ),
            scrollTo = "history_backups.cancel_restore",
        )

    /** Shows validation guidance while editing the restore phrase. */
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

    /** Captures the explanation shown without backup support. */
    @Test fun unavailableLight() =
        capture(
            "history_backups_unavailable_light",
            HistoryBackupsState(status = HistoryBackupStatus.Unavailable),
        )

    /** Captures the numbered words and copy control. */
    @Test fun wordsLight() =
        capture(
            "history_backups_words_light",
            HistoryBackupsState(status = HistoryBackupStatus.Off, setup = setup()),
        )

    /** Captures a recoverable confirmation error in dark theme. */
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

    /** Checks that numbered words fit in larger RTL text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun wordsLargeRtl() =
        capture(
            "history_backups_words_rtl_large",
            HistoryBackupsState(status = HistoryBackupStatus.Off, setup = setup()),
            rtl = true,
            revealWords = true,
        )

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun statusLargeRtl() =
        capture(
            "history_backups_on_rtl_large",
            HistoryBackupsState(
                status = HistoryBackupStatus.On(thisDeviceWrites = true),
                running = HistoryBackupAction.TurnOff,
            ),
            rtl = true,
        )

    /** Captures the recovery hint on Chat Info in light theme. */
    @Test fun chatInfoHintLight() = captureHint("history_backups_chat_hint_light", dark = false, rtl = false)

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun chatInfoHintDarkLargeRtl() = captureHint("history_backups_chat_hint_dark_rtl_large", dark = true, rtl = true)

    private fun captureHint(
        name: String,
        dark: Boolean,
        rtl: Boolean,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface { ChatHistoryBackupHintCard(Modifier.padding(16.dp)) }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Restore words survive refusal, clear when restore starts, and stay out of saved state. */
    @Test fun restoreWordsAreClearedAndNeverRestoredFromSavedState() {
        val restored = mutableListOf<String>()
        val state = mutableStateOf(HistoryBackupsState(status = HistoryBackupStatus.Off))
        val tester = StateRestorationTester(compose)
        tester.setContent {
            WhiteNoiseTheme {
                HistoryBackupsContent(
                    state.value,
                    HistoryBackupsActions(onRestore = { restored += it }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("history_backups.list").performScrollToNode(hasTestTag("history_backups.open_restore"))
        compose.onNodeWithTag("history_backups.open_restore").performClick()
        compose.onNodeWithTag("history_backups.list").performScrollToNode(hasTestTag("history_backups.restore"))
        val button = compose.onNodeWithTag("history_backups.restore")
        button.assertIsNotEnabled()
        compose.onNodeWithTag("history_backups.restore_words").performTextInput("alpha beta")
        button.assertIsNotEnabled()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history_backups.list").performScrollToNode(hasTestTag("history_backups.restore"))
        assertFalse(restoreFieldContains("alpha"))
        val fixture = setup().words.joinToString(" ")
        compose.onNodeWithTag("history_backups.restore_words").performTextInput(fixture)
        button.performClick()
        assertEquals(listOf(fixture), restored)
        compose.runOnIdle { state.value = state.value.copy(problem = HistoryBackupProblem.InvalidRestoreWords) }
        val error =
            ApplicationProvider
                .getApplicationContext<android.app.Application>()
                .getString(R.string.history_backups_problem_restore_words)
        compose.onNodeWithText(error).assertIsDisplayed()
        assertTrue(restoreFieldContains("orbit"))
        button.performClick()
        compose.runOnIdle {
            state.value = state.value.copy(problem = null, restore = progress(BackupRestorePhaseFfi.RESTORING, 0))
        }
        compose.onNodeWithTag("history_backups.list").performScrollToNode(hasTestTag("history_backups.cancel_restore"))
        compose.runOnIdle {
            state.value = state.value.copy(restore = progress(BackupRestorePhaseFfi.COMPLETE, 1))
        }
        compose.onNodeWithTag("history_backups.list").performScrollToNode(hasTestTag("history_backups.restore"))
        assertFalse(restoreFieldContains("orbit"))
    }

    /** Checks whether the restore field still contains the supplied word. */
    private fun restoreFieldContains(word: String): Boolean =
        compose
            .onNodeWithTag("history_backups.restore_words")
            .fetchSemanticsNode()
            .config
            .toString()
            .contains(word)

    /** Renders [state] in the requested theme and direction, scrolls to [scrollTo], and records [name]. */
    private fun capture(
        name: String,
        state: HistoryBackupsState,
        dark: Boolean = false,
        rtl: Boolean = false,
        scrollTo: String? = null,
        revealWords: Boolean = false,
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
        if (revealWords) {
            val word = state.setup!!.words.first()
            compose.onNodeWithText(word).assertDoesNotExist()
            compose.onNodeWithTag("history_backups.copy_words").assertDoesNotExist()
            compose.onNodeWithTag("history_backups.reveal_words").performClick()
            compose.onNodeWithText(word).assertIsDisplayed()
            compose.onNodeWithTag("history_backups.copy_words").assertIsDisplayed()
        }
        if (scrollTo != null) {
            compose.onNodeWithTag("history_backups.list").performScrollToNode(hasTestTag(scrollTo))
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun setup() =
        RecoveryWordsSetup(
            words =
                listOf(
                    "orbit",
                    "maple",
                    "canyon",
                    "velvet",
                    "harbor",
                    "pencil",
                    "quartz",
                    "meadow",
                    "lantern",
                    "ripple",
                    "saddle",
                    "thunder",
                ),
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
