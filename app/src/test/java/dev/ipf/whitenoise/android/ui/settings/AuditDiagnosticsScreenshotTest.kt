package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AuditDeliveryBacklogV5Ffi
import dev.ipf.marmotkit.AuditDeliveryFailureKindV5Ffi
import dev.ipf.marmotkit.AuditDeliveryFailureV5Ffi
import dev.ipf.marmotkit.AuditDeliveryStatusV5Ffi
import dev.ipf.marmotkit.AuditLogRetentionFfi
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.state.AuditDeliveryState
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real Android settings entry point with synthetic MDK delivery and retention records. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AuditDiagnosticsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** A second profile's persisted pause is visible and only the explicit button runs an upload. */
    @Test
    fun pausedDeliveryAndExplicitResume() {
        val active = auditStatus("account")
        val paused =
            auditStatus("another-profile").copy(
                paused = true,
                consecutiveFailures = 2uL,
                lastFailure = AuditDeliveryFailureV5Ffi(AuditDeliveryFailureKindV5Ffi.RECEIVER_BLOCKED, 403u, 1uL),
                backlog = AuditDeliveryBacklogV5Ffi(8192uL, 1uL, 0uL, null, 1uL),
            )
        val audit = PrivacyAuditFixture(enabled = true, statuses = listOf(active, paused))
        render(audit, granted = true)
        scrollTo("Audit log delivery")
        composeRule.waitUntil(timeoutMillis = 5_000) { audit.deliveryReads > 0 }
        composeRule.onNodeWithText("Refresh").performScrollTo()
        composeRule.onNodeWithText("Paused · HTTP 403").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting to upload").assertIsDisplayed()
        composeRule.onNodeWithText("Resume delivery").assertIsDisplayed()
        assertEquals(0, audit.resumeCalls)
        composeRule
            .onNodeWithTag("audit.delivery.group")
            .captureRoboImage("src/test/snapshots/audit_delivery_paused_light.png")

        composeRule.onNodeWithText("Resume delivery").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { audit.resumeCalls == 1 && audit.deliveryReads >= 3 }
        composeRule.onNodeWithText("Active").assertIsDisplayed()
        composeRule.onNodeWithText("Resume delivery").assertDoesNotExist()
    }

    /** Shortening warns before any write, and the persisted policy keeps MDK's existing byte budget. */
    @Test
    fun shorterRetentionConfirmsBeforeWriting() {
        val audit = PrivacyAuditFixture(retention = AuditLogRetentionFfi(30u, 64uL * 1024uL * 1024uL))
        render(audit, darkLarge = true)
        scrollTo("Keep audit logs for")
        composeRule.waitUntil {
            composeRule.onAllNodes(hasText("30 days")).fetchSemanticsNodes().isNotEmpty()
        }
        chooseSevenDays()
        composeRule.onNodeWithText("Delete older audit logs?").assertIsDisplayed()
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/audit_retention_confirm_dark_large_rtl.png")
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, audit.retentionWrites)

        chooseSevenDays()
        composeRule.onNodeWithText("Delete older logs").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil { audit.retentionWrites == 1 }
        assertEquals(AuditLogRetentionFfi(7u, 64uL * 1024uL * 1024uL), audit.retention)
    }

    /** Clearing starts only after the visible confirmation has completed its shared exit. */
    @Test
    fun clearLogsWaitsForExitAndDispatchesOnce() {
        val audit = PrivacyAuditFixture()
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.DECLINED, audit = audit)
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                    DiagnosticsImprovementsScreen(state, onBack = {})
                }
            }
        }
        scrollTo("Clear Diagnostic Logs")
        composeRule.onNodeWithText("Clear Diagnostic Logs").performClick()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        composeRule.mainClock.advanceTimeBy(80)
        composeRule.runOnIdle { assertEquals(0, audit.clearInventoryReads) }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        // Native IO can finish after the first idle check while the animation clock is paused.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.waitForIdle()
            audit.clearInventoryReads == 1
        }
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.runOnIdle { assertEquals(1, audit.clearInventoryReads) }
    }

    /** A stale native pause cannot trigger reads or a manual send without Android's consent receipt. */
    @Test
    fun noConsentKeepsDeliveryOff() =
        runBlocking {
            val audit =
                PrivacyAuditFixture(
                    enabled = true,
                    statuses = listOf(auditStatus("account").copy(paused = true)),
                )
            val settings =
                privacyAppState(
                    UsageDiagnosticsDecisionFfi.DECLINED,
                    audit = audit,
                ).auditDiagnosticsSettings()
            settings.refreshDelivery()
            settings.resumeDelivery()
            assertEquals(AuditDeliveryState.OFF, settings.delivery?.getOrThrow()?.state)
            assertEquals(0, audit.deliveryReads)
            assertEquals(0, audit.resumeCalls)
        }

    /** A receiver still refusing delivery keeps the pause visible after an explicit retry. */
    @Test
    fun manualAttemptDoesNotClearAReceiverPauseOnItsOwn() =
        runBlocking {
            val audit =
                PrivacyAuditFixture(
                    enabled = true,
                    statuses = listOf(auditStatus("account").copy(paused = true)),
                    clearPauseOnResume = false,
                )
            val settings =
                privacyAppState(
                    UsageDiagnosticsDecisionFfi.DECLINED,
                    seedPreferences = { putInt("audit_upload_disclosure_revision", 2) },
                    audit = audit,
                ).auditDiagnosticsSettings()
            settings.refreshDelivery()
            settings.resumeDelivery()
            assertEquals(1, audit.resumeCalls)
            assertEquals(AuditDeliveryState.PAUSED, settings.delivery?.getOrThrow()?.state)
        }

    /** Opens the integrated Settings route with a disposable runtime and optional consent. */
    private fun render(
        audit: PrivacyAuditFixture,
        granted: Boolean = false,
        darkLarge: Boolean = false,
    ) {
        val state =
            privacyAppState(
                UsageDiagnosticsDecisionFfi.DECLINED,
                seedPreferences = { if (granted) putInt("audit_upload_disclosure_revision", 2) },
                audit = audit,
            )
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            val layoutDirection = if (darkLarge) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme(darkTheme = darkLarge, fontScale = if (darkLarge) 1.6f else 1f) {
                    DiagnosticsImprovementsScreen(state, onBack = {})
                }
            }
        }
    }

    /** Scrolls the actual diagnostics list to a localized row. */
    private fun scrollTo(label: String) {
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasText(label))
    }

    /** Chooses a shorter period through the same picker used by users. */
    private fun chooseSevenDays() {
        composeRule.onNodeWithText("Keep audit logs for").performClick()
        composeRule.onNodeWithText("7 days").performClick()
    }

    /** A configured account with no previous attempts. */
    private fun auditStatus(account: String) =
        AuditDeliveryStatusV5Ffi(
            accountRef = account,
            destinationConfigured = true,
            lastSuccessAtMs = null,
            lastFailure = null,
            consecutiveFailures = 0uL,
            rejectedBatches = 0uL,
            paused = false,
            backlog = null,
        )
}
