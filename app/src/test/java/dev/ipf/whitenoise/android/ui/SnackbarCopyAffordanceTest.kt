package dev.ipf.whitenoise.android.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.NoticeTier
import dev.ipf.whitenoise.android.state.privacySafeErrorPresentation
import dev.ipf.whitenoise.android.ui.common.LocalTopNoticeHost
import dev.ipf.whitenoise.android.ui.common.TOP_NOTICE_REGION_TAG
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.TopNoticeHost
import dev.ipf.whitenoise.android.ui.common.TopNoticeSlot
import dev.ipf.whitenoise.android.ui.common.snackbarShowsCopyAffordance
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #796: the snackbar Copy affordance must be gated by the explicit
 * explicit privacy-safe report set at the toast's emit site — not by a
 * heuristic over the message body or a legacy `copyable` flag alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class SnackbarCopyAffordanceTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * In the owning top region below a header, an actionable error keeps its Copy and Dismiss actions reachable,
     * Copy places only the privacy-safe report on the clipboard, and Dismiss retires exactly that snackbar (#3312).
     */
    @Test
    fun anActionableErrorInTheTopRegionKeepsCopyAndDismissBelowTheHeader() {
        val host = TopNoticeHost(SnackbarHostState(), notice = { null })
        val report = "operation=MESSAGE_SEND\nerror=CONNECTIVITY"
        val result = mutableStateOf<SnackbarResult?>(null)
        composeRule.setContent {
            CompositionLocalProvider(LocalTopNoticeHost provides host) {
                WhiteNoiseTheme {
                    LaunchedEffect(Unit) {
                        result.value =
                            host.snackbarHostState.showSnackbar(
                                ToastSnackbarVisuals(message = ERROR_TEXT, copyable = true, copyText = report),
                            )
                    }
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(64.dp).testTag(HEADER_TAG))
                        TopNoticeSlot()
                        Box(Modifier.fillMaxWidth().weight(1f).testTag(BODY_TAG))
                    }
                }
            }
        }

        val header = composeRule.onNodeWithTag(HEADER_TAG).getUnclippedBoundsInRoot()
        val copy = composeRule.onNodeWithContentDescription("Copy").assertIsDisplayed()
        assertTrue("the banner sits below the header", copy.getUnclippedBoundsInRoot().top >= header.bottom)
        assertTrue(
            "the body begins below the banner",
            composeRule.onNodeWithTag(BODY_TAG).getUnclippedBoundsInRoot().top >
                composeRule.onNodeWithTag(TOP_NOTICE_REGION_TAG).getUnclippedBoundsInRoot().top,
        )
        copy.performClick()
        val clipboard =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(ClipboardManager::class.java)
        assertEquals(
            report,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                .toString(),
        )

        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Copy").assertDoesNotExist()
        assertEquals(SnackbarResult.Dismissed, result.value)
    }

    @Test
    fun copyableErrorToastShowsCopyAffordance() {
        assertTrue(
            snackbarShowsCopyAffordance(
                ToastSnackbarVisuals(
                    message = "Couldn't send message\nTry again",
                    copyable = true,
                    copyText = "operation=MESSAGE_SEND\nerror=CONNECTIVITY",
                ),
            ),
        )
    }

    @Test
    fun deletionDiagnosticsAreOnlyAvailableBehindDetails() {
        val visuals =
            ToastSnackbarVisuals(
                message = "Couldn't delete chat",
                copyable = true,
                copyText = "operation=CHAT_LOCAL_DELETE",
                details = "Retry guidance\noperation=CHAT_LOCAL_DELETE",
                actionLabel = "Retry",
            )
        assertFalse(snackbarShowsCopyAffordance(visuals))
        assertFalse(visuals.message.contains("CHAT_LOCAL_DELETE"))
        assertEquals(SnackbarDuration.Indefinite, visuals.duration)
        assertTrue(visuals.withDismissAction)
    }

    @Test
    fun copyableFlagWithoutSafeReportDoesNotCopyVisibleText() {
        assertFalse(
            snackbarShowsCopyAffordance(
                ToastSnackbarVisuals(message = "Validation failed for alice@example.test", copyable = true),
            ),
        )
    }

    @Test
    fun nonCopyableToastHidesCopyAffordanceRegardlessOfBody() {
        // Not flagged at the emit site: no copy icon, even for a long,
        // diagnostic-looking body — the flag decides, not the message text.
        assertFalse(
            snackbarShowsCopyAffordance(
                ToastSnackbarVisuals(message = "npub1qqqsyqcyq5rqwzqfpqyp3zxw", copyable = false),
            ),
        )
        assertFalse(snackbarShowsCopyAffordance(ToastSnackbarVisuals(message = "Copied")))
    }

    @Test
    fun plainShowSnackbarMessagesNeverShowCopyAffordance() {
        // Direct hostState.showSnackbar("...") calls produce the default
        // Material visuals, which carry no copyable flag.
        assertFalse(snackbarShowsCopyAffordance(fakeVisuals(actionLabel = null)))
    }

    @Test
    fun actionableSnackbarsNeverShowCopyAffordance() {
        // The action slot (e.g. the chat-list "Undo") must stay untouched.
        assertFalse(snackbarShowsCopyAffordance(fakeVisuals(actionLabel = "Undo")))
    }

    @Test
    fun appNoticesUseTierAppropriateLifetimeAndCopyPayload() {
        val confirmation = ToastSnackbarVisuals(message = "Saved")
        val error =
            ToastSnackbarVisuals(
                message = "Couldn\'t save\nTry again",
                copyable = true,
                tier = NoticeTier.ActionableError,
                copyText = "operation=SAVE\nerror=CONNECTIVITY",
            )

        assertEquals(SnackbarDuration.Short, confirmation.duration)
        assertEquals(SnackbarDuration.Indefinite, error.duration)
        assertEquals("operation=SAVE\nerror=CONNECTIVITY", error.copyText)
        assertTrue(error.withDismissAction)
    }

    @Test
    fun migratedFailureShowsDiagnosticCopy() {
        val presentation =
            privacySafeErrorPresentation(
                operationCode = "MEDIA_LIBRARY_FILE_OPEN",
                throwable = java.io.IOException("token=private https://user:pass@example.test"),
                message = AppText.Plain("Couldn't open this file."),
                appVersion = "test",
                androidVersion = "test",
                occurredAtUtc = "2026-08-15T12:00:00Z",
            )
        val visuals =
            ToastSnackbarVisuals(
                message = (presentation.message as AppText.Plain).value,
                copyable = true,
                copyText = presentation.report,
            )

        assertTrue(snackbarShowsCopyAffordance(visuals))
        assertTrue(visuals.copyText?.contains("operation=MEDIA_LIBRARY_FILE_OPEN") == true)
        assertFalse(visuals.copyText?.contains("user:pass") == true)
        assertFalse(visuals.copyText?.contains("private") == true)
    }

    private fun fakeVisuals(actionLabel: String?): SnackbarVisuals =
        object : SnackbarVisuals {
            override val message: String = "Something happened"
            override val actionLabel: String? = actionLabel
            override val withDismissAction: Boolean = false
            override val duration: SnackbarDuration = SnackbarDuration.Short
        }

    private companion object {
        const val HEADER_TAG = "screen-header"
        const val BODY_TAG = "screen-body"
        const val ERROR_TEXT = "Couldn't send message"
    }
}
