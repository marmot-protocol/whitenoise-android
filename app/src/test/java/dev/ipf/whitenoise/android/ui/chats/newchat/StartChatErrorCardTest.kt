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
import org.junit.Assert.assertNull
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
    fun inviteStateKeepsKnownNameAndUsesGenericFallback() {
        val error = MarmotKitException.InvalidKeyPackageEvent("unsupported cipher suite")

        val known =
            startChatErrorUiState(
                npub = "npub1alice",
                progressHex = "deadbeef",
                error = error,
                recipientName = "Alice",
                displayName = { "ignored" },
            )
        assertEquals(AppText.Resource(R.string.invite_to_white_noise_description, listOf("Alice")), known.detail)
        assertEquals("Alice", known.recipientName)
        assertEquals(true, known.invitation)

        val unknown =
            startChatErrorUiState(
                npub = "npub1unknown",
                progressHex = "cafebabe",
                error = error,
                recipientName = null,
                displayName = { "ignored" },
            )
        assertEquals(AppText.Resource(R.string.unknown_invite_to_white_noise_description), unknown.detail)
        assertNull(unknown.recipientName)
        assertEquals(true, unknown.invitation)
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
                copyable = false,
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
    fun inviteShareIntentCarriesLocalizedCopyAsPlainText() {
        val message = context.getString(R.string.invite_message)
        val intent = inviteShareIntent(message)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(message, intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
