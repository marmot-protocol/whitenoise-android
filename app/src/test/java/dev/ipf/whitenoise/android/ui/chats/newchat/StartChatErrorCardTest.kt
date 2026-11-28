package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MissingKeyPackageReasonFfi
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

    /** A DM whose founding timer the recipient's key cannot carry asks them to update, named when known. */
    @Test
    fun incompatiblePackageAsksTheRecipientToUpdate() {
        val error = MarmotKitException.IncompatibleKeyPackage()

        val known = startChatErrorUiState("npub1alice", "alice", error, "Alice") { "ignored" }
        assertEquals(AppText.Resource(R.string.error_incompatible_key_package_for, listOf("Alice")), known.detail)
        assertEquals(AppText.Resource(R.string.toast_couldnt_start_chat), known.title)
        assertFalse(known.invitation)
        assertFalse(known.copyable)

        val unknown = startChatErrorUiState("npub1alice", "alice", error, " ") { "ignored" }
        assertEquals(AppText.Resource(R.string.error_incompatible_key_package), unknown.detail)
        assertFalse(unknown.invitation)
    }

    /** A recipient with no KeyPackage at all is someone to invite: named when known, never a failure title. */
    @Test
    fun missingKeyIsPresentedAsAnInvitationNamedWhenKnown() {
        val missing = MarmotKitException.MissingKeyPackage("alice", MissingKeyPackageReasonFfi.NOT_FOUND)
        val named = startChatErrorUiState("npub1alice", "alice", missing, "Alice") { "Alice" }
        assertTrue(named.invitation)
        assertEquals(AppText.Resource(R.string.start_chat_not_on_white_noise_for, listOf("Alice")), named.title)
        assertEquals(AppText.Resource(R.string.start_chat_not_on_white_noise_detail), named.detail)
        assertFalse(named.copyable)

        val unnamed = startChatErrorUiState("npub1alice", "alice", missing, null) { "npub1ali…ce" }
        assertTrue(unnamed.invitation)
        assertEquals(AppText.Resource(R.string.start_chat_not_on_white_noise), unnamed.title)
        assertEquals(AppText.Resource(R.string.start_chat_not_on_white_noise_detail), unnamed.detail)
    }

    /** Only a confirmed missing package becomes an invitation; other recipient failures keep today's copy. */
    @Test
    fun otherRecipientFailuresKeepCouldNotStartChat() {
        val failures =
            mapOf(
                MarmotKitException.MissingKeyPackage("alice", MissingKeyPackageReasonFfi.OBSOLETE_ONLY) to
                    R.string.error_obsolete_key_package_for,
                MarmotKitException.DirectoryLookupIncomplete("alice") to R.string.error_directory_lookup_incomplete_for,
                MarmotKitException.MissingMemberInboxRoute("alice") to R.string.error_missing_member_inbox_for,
            )
        failures.forEach { (failure, detail) ->
            val state = startChatErrorUiState("npub1alice", "alice", failure, "Alice") { "Alice" }
            assertFalse(state.invitation)
            assertEquals(AppText.Resource(R.string.toast_couldnt_start_chat), state.title)
            assertEquals(AppText.Resource(detail, listOf("Alice")), state.detail)
        }
    }

    @Test
    fun missingInboxCopiesRecipientInstructionsWithoutDiagnosticOrShare() {
        val error =
            startChatErrorUiState(
                npub = "npub1alice",
                progressHex = "alice",
                error = MarmotKitException.MissingMemberInboxRoute("alice"),
                recipientName = "Alice",
                displayName = { "Alice" },
            )
        var copied: String? = null
        var retries = 0
        assertFalse(error.invitation)
        assertFalse(error.copyable)
        assertEquals(AppText.Resource(R.string.error_missing_member_inbox_for, listOf("Alice")), error.detail)
        composeRule.setContent {
            StartChatErrorCard(error, onRetry = { retries++ }, onInvite = {}, onCopy = { copied = it })
        }
        composeRule.onNodeWithText(context.getString(R.string.error_missing_member_inbox_for, "Alice")).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.new_message_copy_inbox_instructions)).performClick()
        assertEquals(context.getString(R.string.new_message_inbox_instructions), copied)
        assertEquals(0, retries)
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        assertEquals(1, retries)
        composeRule.onNodeWithText(context.getString(R.string.share)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.copy)).assertDoesNotExist()
    }

    @Test
    fun incompleteLookupOffersRetryWithoutInboxInstructions() {
        val error =
            startChatErrorUiState(
                "npub1alice",
                "alice",
                MarmotKitException.DirectoryLookupIncomplete("alice"),
                "Alice",
                displayName = { "Alice" },
            )
        var retries = 0
        composeRule.setContent {
            StartChatErrorCard(error, onRetry = { retries++ }, onInvite = {}, onCopy = {})
        }
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.new_message_copy_inbox_instructions)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.share)).assertDoesNotExist()
        assertEquals(1, retries)
    }

    @Test
    fun invitationCardRendersSendInviteAndRetryActions() {
        var inviteTaps = 0
        var retryTaps = 0
        val error =
            startChatErrorUiState(
                npub = "npub1alice",
                progressHex = "deadbeef",
                error = MarmotKitException.MissingKeyPackage("alice", MissingKeyPackageReasonFfi.NOT_FOUND),
                recipientName = "Alice",
                displayName = { "Alice" },
            )

        composeRule.setContent {
            StartChatErrorCard(
                error = error,
                onRetry = { retryTaps++ },
                onInvite = { inviteTaps++ },
                onCopy = {},
            )
        }

        val title = context.getString(R.string.start_chat_not_on_white_noise_for, "Alice")
        composeRule.onNodeWithText(title).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.start_chat_not_on_white_noise_detail)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.copy)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.share)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.send_invite)).performClick()
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
