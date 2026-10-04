package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.StartProfileChatNoActiveAccountException
import dev.ipf.whitenoise.android.state.groupCreateFailureDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureCopyable
import dev.ipf.whitenoise.android.state.startProfileChatFailureDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureIsMissingSetup
import dev.ipf.whitenoise.android.state.startProfileChatInviteDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteFailurePresentationTest {
    @Test
    fun startProfileChatFailureMapsMissingSetupToHumanCopy() {
        val missing = MarmotKitException.MissingKeyPackage("deadbeef")

        assertEquals(
            AppText.Resource(
                R.string.error_missing_key_package_for,
                listOf("Alice"),
            ),
            startProfileChatFailureDetail(missing) { "Alice" },
        )
        assertFalse(startProfileChatFailureCopyable(missing))

        val missingBlankAccount = MarmotKitException.MissingKeyPackage("  ")
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package),
            startProfileChatFailureDetail(missingBlankAccount) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(missingBlankAccount))
    }

    @Test
    fun startProfileChatDistinguishesInvalidRecipientFromUnusableKeyPackage() {
        val invalidIdentity = MarmotKitException.InvalidIdentity("bad npub")

        assertFalse(startProfileChatFailureIsMissingSetup(invalidIdentity))
        assertEquals(
            AppText.Resource(R.string.error_invalid_identity_reference),
            startProfileChatFailureDetail(invalidIdentity) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(invalidIdentity))
        assertEquals(
            AppText.Resource(R.string.error_invalid_identity_reference),
            groupCreateFailureDetail(invalidIdentity) { "ignored" },
        )

        val invalidKeyPackage = MarmotKitException.InvalidKeyPackageEvent("unsupported cipher suite")
        assertFalse(startProfileChatFailureIsMissingSetup(invalidKeyPackage))
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            startProfileChatFailureDetail(invalidKeyPackage) { "ignored" },
        )
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            groupCreateFailureDetail(invalidKeyPackage) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(invalidKeyPackage))
    }

    @Test
    fun groupFailureNamesTheAffectedInboxRecipientAndNeverExposesNativeDetails() {
        val missing = MarmotKitException.MissingMemberInboxRoute(" bob ")
        assertEquals(
            AppText.Resource(R.string.error_missing_member_inbox_for, listOf("Bob")),
            groupCreateFailureDetail(missing) { account ->
                assertEquals("bob", account)
                "Bob"
            },
        )
        assertEquals(
            AppText.Resource(R.string.error_missing_member_inbox),
            groupCreateFailureDetail(MarmotKitException.MissingMemberInboxRoute("  ")) { error("No account") },
        )
        val invalid = MarmotKitException.InvalidKeyPackageEvent("secret relay URL and identity")
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            groupCreateFailureDetail(invalid) { error("No typed affected account") },
        )
    }

    @Test
    fun aHydrationPendingGroupReadsAsStillLoadingNotAsACreateFailure() {
        val pending = MarmotKitException.GroupHydrationPending("7c3bdc38")

        assertFalse(startProfileChatFailureIsMissingSetup(pending))
        assertEquals(
            AppText.Resource(R.string.toast_chat_still_loading),
            groupCreateFailureDetail(pending) { "ignored" },
        )
        assertEquals(
            AppText.Resource(R.string.toast_chat_still_loading),
            startProfileChatFailureDetail(pending) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(pending))
    }

    @Test
    fun startProfileChatInviteCopyUsesKnownNameOrGenericFallback() {
        assertEquals(
            AppText.Resource(R.string.invite_to_white_noise_description, listOf("Alice")),
            startProfileChatInviteDetail("Alice"),
        )
        assertEquals(
            AppText.Resource(R.string.unknown_invite_to_white_noise_description),
            startProfileChatInviteDetail("  "),
        )
        assertTrue(startProfileChatFailureIsMissingSetup(MarmotKitException.MissingKeyPackage("deadbeef")))
    }

    @Test
    fun startProfileChatFailureDistinguishesTechnicalFailures() {
        val publishFailure = MarmotKitException.Publish("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(publishFailure) { "ignored" },
        )
        assertTrue(startProfileChatFailureCopyable(publishFailure))
        val runtimeFailure = MarmotKitException.Runtime("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(runtimeFailure) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(runtimeFailure))
        val unexpectedFailure = RuntimeException("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(unexpectedFailure) { "ignored" },
        )
        assertTrue(startProfileChatFailureCopyable(unexpectedFailure))
    }

    @Test
    fun startProfileChatFailureMapsNoActiveAccountToLocalizedCopy() {
        val noActiveAccount = StartProfileChatNoActiveAccountException()

        assertEquals(
            AppText.Resource(R.string.toast_no_active_account),
            startProfileChatFailureDetail(noActiveAccount) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(noActiveAccount))
    }
}
