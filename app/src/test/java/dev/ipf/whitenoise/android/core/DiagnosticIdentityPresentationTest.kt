package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.ReceivedMessageFfi
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticIdentityPresentationTest {
    @Test
    fun hexAccountLabelAndSenderUseCanonicalNpubPresentation() {
        val accountNpub = IdentityFormatter.short(ACCOUNT_NPUB, prefix = 10, suffix = 8)
        val senderNpub = IdentityFormatter.short(SENDER_NPUB, prefix = 10, suffix = 8)
        val recipientNpub = IdentityFormatter.short(RECIPIENT_NPUB, prefix = 10, suffix = 8)
        val identity =
            DiagnosticIdentityPresentation(
                accountLabel = { label, accountIdHex ->
                    DiagnosticIdentityPresentation.accountLabel(label, accountIdHex, ::presentShortNpub)
                },
                publicIdentity = ::presentShortNpub,
            )

        val message =
            MarmotEventFfi.MessageReceived(
                RuntimeMessageReceivedFfi(
                    accountIdHex = ACCOUNT_HEX,
                    accountLabel = ACCOUNT_HEX,
                    message =
                        ReceivedMessageFfi(
                            messageIdHex = "message",
                            groupIdHex = "group",
                            sender = SENDER_HEX,
                            senderDisplayName = null,
                            plaintext = "hello",
                            contentTokens =
                                MarkdownDocumentFfi(
                                    truncated = false,
                                    blocks = emptyList(),
                                    blankLinesBefore = ByteArray(0),
                                ),
                            kind = 9uL,
                            tags = emptyList(),
                            sourceEpoch = 0uL,
                            retentionSeconds = null,
                            retentionExpiresAt = null,
                            recordedAt = 1_779_926_400uL,
                            receivedAt = 1_779_926_400uL,
                        ),
                ),
            )
        val welcome =
            MarmotEventFfi.WelcomeDeliveryPending(
                accountIdHex = ACCOUNT_HEX,
                accountLabel = ACCOUNT_HEX,
                groupIdHex = GROUP_HEX,
                messageIdHex = "welcome-message",
                recipientHex = RECIPIENT_HEX,
            )

        val describedMessage = DiagnosticFormatter.describe(message, identity)
        val describedWelcome = DiagnosticFormatter.describe(welcome, identity)

        assertEquals("[$accountNpub] msg from $senderNpub kind=9 len=5", describedMessage)
        assertEquals(
            "[$accountNpub] welcome pending for $recipientNpub in group ${IdentityFormatter.short(GROUP_HEX)}",
            describedWelcome,
        )
        assertFalse(describedMessage.contains(ACCOUNT_HEX))
        assertFalse(describedMessage.contains(SENDER_HEX))
        assertFalse(describedWelcome.contains(RECIPIENT_HEX))
    }

    private fun presentShortNpub(accountIdHex: String): String =
        when (accountIdHex) {
            ACCOUNT_HEX -> IdentityFormatter.short(ACCOUNT_NPUB, prefix = 10, suffix = 8)
            SENDER_HEX -> IdentityFormatter.short(SENDER_NPUB, prefix = 10, suffix = 8)
            RECIPIENT_HEX -> IdentityFormatter.short(RECIPIENT_NPUB, prefix = 10, suffix = 8)
            else -> error("unexpected account id $accountIdHex")
        }

    private companion object {
        const val ACCOUNT_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SENDER_HEX = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        const val RECIPIENT_HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val GROUP_HEX = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val ACCOUNT_NPUB = "npub1accountidentityaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SENDER_NPUB = "npub1senderidentitybbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val RECIPIENT_NPUB = "npub1recipientidentitycccccccccccccccccccccccccccccccccccccc"
    }
}
