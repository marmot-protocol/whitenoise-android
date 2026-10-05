package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartChatErrorCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun invalidPackageKeepsKnownNameWithoutSuggestingInstallation() {
        val error = MarmotKitException.InvalidKeyPackageEvent("unsupported cipher suite")

        val known =
            startChatErrorUiState(
                npub = "npub1alice",
                progressHex = "deadbeef",
                error = error,
                recipientName = "Alice",
                displayName = { "ignored" },
            )
        assertEquals(AppText.Resource(R.string.error_invalid_key_package_for, listOf("Alice")), known.detail)
        assertEquals("Alice", known.recipientName)
        assertFalse(known.invitation)
        assertEquals(AppText.Resource(R.string.toast_couldnt_start_chat), known.title)

        val unknown =
            startChatErrorUiState(
                npub = "npub1unknown",
                progressHex = "cafebabe",
                error = error,
                recipientName = null,
                displayName = { "ignored" },
            )
        assertEquals(AppText.Resource(R.string.error_invalid_key_package), unknown.detail)
        assertNull(unknown.recipientName)
        assertFalse(unknown.invitation)
    }

    @Test
    fun missingKeyKeepsFailureTitleAndOptionalShareWithoutAssumingInstallation() {
        val error =
            startChatErrorUiState(
                npub = "npub1alice",
                progressHex = "alice",
                error = MarmotKitException.MissingKeyPackage("alice"),
                recipientName = "Alice",
                displayName = { "Alice" },
            )
        assertTrue(error.invitation)
        assertEquals(AppText.Resource(R.string.toast_couldnt_start_chat), error.title)
        assertEquals(AppText.Resource(R.string.error_missing_key_package_for, listOf("Alice")), error.detail)
    }

    @Test
    fun missingInboxShowsSettingsGuidanceWithoutShare() {
        val error =
            startChatErrorUiState(
                npub = "npub1alice",
                progressHex = "alice",
                error = MarmotKitException.MissingMemberInboxRoute("alice"),
                recipientName = "Alice",
                displayName = { "Alice" },
            )
        assertFalse(error.invitation)
        assertEquals(AppText.Resource(R.string.error_missing_member_inbox_for, listOf("Alice")), error.detail)
        composeRule.setContent {
            StartChatErrorCard(error, onRetry = {}, onInvite = {}, onCopy = {})
        }
        composeRule.onNodeWithText(context.getString(R.string.error_missing_member_inbox_for, "Alice")).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.retry)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.share)).assertDoesNotExist()
    }

    @Test
    fun invitationCardRendersShareAndRetryActions() {
        var inviteTaps = 0
        var retryTaps = 0
        val error =
            StartChatErrorUiState(
                npub = "npub1alice",
                progressHex = "deadbeef",
                detail = AppText.Resource(R.string.invite_to_white_noise_description, listOf("Alice")),
                diagnosticReport = null,
                recipientName = "Alice",
                invitation = true,
                title = AppText.Resource(R.string.invite_to_white_noise),
            )

        composeRule.setContent {
            StartChatErrorCard(
                error = error,
                onRetry = { retryTaps++ },
                onInvite = { inviteTaps++ },
                onCopy = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.invite_to_white_noise)).assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.invite_to_white_noise_description, "Alice"))
            .assertExists()
        composeRule.onNodeWithText(context.getString(R.string.copy)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.share)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()

        assertEquals(1, inviteTaps)
        assertEquals(1, retryTaps)
    }

    @Test
    fun technicalFailureCopiesSafeReportInsteadOfVisibleDetail() {
        val secret = "relay failure for alice@example.test"
        val error =
            startChatErrorUiState(
                npub = "npub1alice",
                progressHex = "deadbeef",
                error = IllegalStateException(secret),
                recipientName = "Alice",
                displayName = { "ignored" },
            )
        var copied: String? = null

        assertTrue(error.copyable)
        assertFalse(error.diagnosticReport.orEmpty().contains(secret))
        composeRule.setContent {
            StartChatErrorCard(
                error = error,
                onRetry = {},
                onInvite = {},
                onCopy = { copied = it },
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.copy)).performClick()

        assertEquals(error.diagnosticReport, copied)
    }

    @Test
    fun inviteShareIntentCarriesLocalizedCopyAsPlainText() {
        val message = context.getString(R.string.invite_message)
        val intent = inviteShareIntent(message)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(message, intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
