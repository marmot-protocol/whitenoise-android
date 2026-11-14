package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.marmotkit.AuditLogFileFfi
import dev.ipf.marmotkit.AuditLogRetentionFfi
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.group.MUTE_CUSTOM_DATE_CANCEL_TAG
import dev.ipf.whitenoise.android.ui.group.MuteDurationDialog
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateCustomStage
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateFilterDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Opt-in exports of actual new popup boundaries; synthetic data never invokes sharing or storage. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AdditionalPopupMotionCaptureTest {
    @get:Rule val rule = createComposeRule()

    @Test fun captureAuditPicker() {
        assumeCapture()
        val shown = mutableStateOf(false)
        val file =
            AuditLogFileFfi("synthetic", "/synthetic/1", "audit-segment-01-v5.jsonl", 880_000uL, 1_762_856_400_000uL)
        render {
            if (shown.value) {
                AuditLogPickerDialog(
                    result = Result.success(listOf(file)),
                    exporting = false,
                    onDismiss = { shown.value = false },
                    onExport = {},
                    onRetry = {},
                )
            }
        }
        capture("audit-picker/forward") { rule.runOnIdle { shown.value = true } }
        capture("audit-picker/reverse") { rule.onNodeWithText("Cancel").performClick() }
    }

    @Test fun captureAuditDestination() {
        assumeCapture()
        val shown = mutableStateOf(false)
        render {
            if (shown.value) {
                AuditLogExportDestinationDialog(
                    onDismiss = { shown.value = false },
                    onSave = { shown.value = false },
                    onShare = { shown.value = false },
                )
            }
        }
        capture("audit-destination/forward") { rule.runOnIdle { shown.value = true } }
        capture("audit-destination/reverse") { rule.onNodeWithText("Save to device").performClick() }
    }

    @Test fun captureAuditConsent() {
        assumeCapture()
        val shown = mutableStateOf(false)
        render {
            if (shown.value) {
                AuditLogExportConsentDialog(onDismiss = { shown.value = false }, onConfirm = {
                    shown.value =
                        false
                })
            }
        }
        capture("audit-consent/forward") { rule.runOnIdle { shown.value = true } }
        capture("audit-consent/reverse") { rule.onNodeWithText("Continue").performClick() }
    }

    @Test fun captureActualClearConfirmation() {
        assumeCapture()
        renderDiagnostics(PrivacyAuditFixture())
        rule.onNodeWithTag("settings.list").performScrollToNode(hasText("Clear Diagnostic Logs"))
        capture("audit-clear/forward") { rule.onNodeWithText("Clear Diagnostic Logs").performClick() }
        capture("audit-clear/reverse") { rule.onNodeWithTag("confirmation.cancel").performClick() }
    }

    @Test fun captureActualShorterRetentionConfirmation() {
        assumeCapture()
        renderDiagnostics(PrivacyAuditFixture(retention = AuditLogRetentionFfi(30u, 64uL * 1024uL * 1024uL)))
        rule.onNodeWithTag("settings.list").performScrollToNode(hasText("Keep audit logs for"))
        rule.waitUntil { rule.onAllNodes(hasText("30 days")).fetchSemanticsNodes().isNotEmpty() }
        capture("audit-shorter-retention/forward", midpoint = { rule.onNodeWithText("7 days").performClick() }) {
            rule.onNodeWithText("Keep audit logs for").performClick()
        }
        capture("audit-shorter-retention/reverse") { rule.onNodeWithTag("confirmation.cancel").performClick() }
    }

    @Test fun captureSearchCalendar() {
        assumeCapture()
        val stage = mutableStateOf<GlobalSearchDateCustomStage?>(null)
        render {
            GlobalSearchDateFilterDialog(
                selection = GlobalSearchDateFilterSelection.AnyTime,
                customStage = stage.value,
                onDismiss = {},
                onApply = {},
                onCustomStageChange = { stage.value = it },
                nowMillis = { NOW },
                zoneId = { ZoneOffset.UTC },
            )
        }
        capture("search-calendar/forward") { rule.onNodeWithText("Custom Range").performClick() }
        capture("search-calendar/reverse") { rule.onNodeWithText("Cancel").performClick() }
    }

    @Test fun captureMuteCalendar() {
        assumeCapture()
        render {
            MuteDurationDialog(
                onDismiss = {},
                onSelect = {},
                nowMillis = { NOW },
                zoneId = { ZoneOffset.UTC },
                initialCustomDateTime = LocalDateTime.of(2026, 8, 11, 18, 30),
            )
        }
        capture("mute-calendar/forward") { rule.onNodeWithText("Custom time").performScrollTo().performClick() }
        capture("mute-calendar/reverse") { rule.onNodeWithTag(MUTE_CUSTOM_DATE_CANCEL_TAG).performClick() }
    }

    private fun renderDiagnostics(audit: PrivacyAuditFixture) {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.DECLINED, audit = audit)
        runBlocking { state.refreshSecurityPrivacySettings() }
        render { DiagnosticsImprovementsScreen(state, onBack = {}) }
    }

    private fun render(content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun capture(
        name: String,
        midpoint: (() -> Unit)? = null,
        action: () -> Unit,
    ) {
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        rule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(if (midpoint == null) 48 else 96) { frame ->
            if (frame == 24) midpoint?.invoke()
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }

    private fun assumeCapture() = assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")

    private companion object {
        const val NOW = 1_786_451_400_000L
    }
}
