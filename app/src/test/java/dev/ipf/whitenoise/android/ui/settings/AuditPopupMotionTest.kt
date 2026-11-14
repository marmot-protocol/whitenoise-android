package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.AuditLogFileFfi
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Audit dialogs keep their asynchronous work owner until the shared exit completes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AuditPopupMotionTest {
    @get:Rule val rule = createComposeRule()
    private val owner = mutableStateOf("first")
    private val exporting = mutableStateOf(false)
    private var dismissed = 0
    private var completed = 0
    private var finishExport: (() -> Unit)? = null

    @Test
    fun cancelRetainsTheDialogUntilItsExitFinishes() {
        render()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(0, dismissed) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNode(isDialog()).assertExists()
        rule.mainClock.advanceTimeBy(320)
        rule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun completedExportDispatchesOnceAfterExit() {
        render()
        rule.onNodeWithText("audit-segment-01-v5.jsonl").performClick()
        rule.onNodeWithText("Export").performClick()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { checkNotNull(finishExport).invoke() }
        rule.runOnIdle { assertEquals(0, completed) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNode(isDialog()).assertExists()
        rule.mainClock.advanceTimeBy(320)
        rule.waitForIdle()
        assertEquals(1, completed)
        rule.runOnIdle { checkNotNull(finishExport).invoke() }
        rule.mainClock.advanceTimeBy(320)
        assertEquals(1, completed)
    }

    @Test
    fun ownerReplacementCancelsAnAcceptedExportCompletion() {
        render()
        rule.onNodeWithText("audit-segment-01-v5.jsonl").performClick()
        rule.onNodeWithText("Export").performClick()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            checkNotNull(finishExport).invoke()
            owner.value = "replacement"
        }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        assertEquals(0, completed)
        rule.onNode(isDialog()).assertExists()
    }

    @Test
    fun busyExportKeepsBothTerminalButtonsDisabled() {
        render()
        rule.onNodeWithText("audit-segment-01-v5.jsonl").performClick()
        rule.runOnIdle { exporting.value = true }
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
        rule.onNodeWithText("Export").assertIsNotEnabled()
        assertEquals(0, dismissed)
    }

    private fun render() {
        val file =
            AuditLogFileFfi("synthetic", "/synthetic/1", "audit-segment-01-v5.jsonl", 880_000uL, 1_762_856_400_000uL)
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                    AuditLogPickerDialog(
                        result = Result.success(listOf(file)),
                        exporting = exporting.value,
                        onDismiss = { dismissed++ },
                        onExport = { finishExport = { close { completed++ } } },
                        onRetry = {},
                        ownerKey = owner.value,
                    )
                }
            }
        }
        rule.waitForIdle()
    }
}
